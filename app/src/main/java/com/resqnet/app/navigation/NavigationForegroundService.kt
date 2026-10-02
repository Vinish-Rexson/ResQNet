package com.resqnet.app.navigation

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.resqnet.app.R
import com.resqnet.app.ResQNetApplication
import com.resqnet.app.navigation.pack.OfflinePackManager
import com.resqnet.app.navigation.pack.OfflinePackState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch

/** Foreground-only navigation owner. It deliberately returns START_NOT_STICKY. */
@OptIn(FlowPreview::class)
class NavigationForegroundService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val evaluator = NavigationProgressEvaluator()
    private lateinit var locationManager: LocationManager
    private lateinit var engine: ValhallaRoutingEngine
    private var destination: GeoPoint? = null
    private var route: RoutePlan? = null
    private var maneuverIndex = -1
    private var lastOrigin: GeoPoint? = null
    private var ignoreHazardsForSession = false
    private var observedHazards: List<AvoidanceArea> = emptyList()

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        engine = ValhallaRoutingEngine(applicationContext)
        createChannel()
        scope.launch {
            (application as ResQNetApplication).hazards.observeActive().debounce(600).collect { reports ->
                val next = reports.map(HazardReport::toAvoidanceArea)
                if (next != observedHazards) {
                    observedHazards = next
                    if (!ignoreHazardsForSession && route != null) {
                        lastOrigin?.let { origin -> destination?.let { calculateRoute(origin, it, rerouting = true) } }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopNavigation()
            return START_NOT_STICKY
        }
        val latitude = intent?.getDoubleExtra(EXTRA_DESTINATION_LATITUDE, Double.NaN) ?: Double.NaN
        val longitude = intent?.getDoubleExtra(EXTRA_DESTINATION_LONGITUDE, Double.NaN) ?: Double.NaN
        val target = GeoPoint(latitude, longitude)
        if (runCatching { target.requireValid() }.isFailure || !hasLocationPermission()) {
            publish(NavigationState.Failed("Location permission or destination is unavailable"))
            stopSelf(); return START_NOT_STICKY
        }
        destination = target
        ignoreHazardsForSession = intent?.getBooleanExtra(EXTRA_IGNORE_HAZARDS, false) == true
        startForeground(NOTIFICATION_ID, notification(NavigationState.WaitingForFix(target)))
        publish(NavigationState.WaitingForFix(target))

        val originLat = intent?.getDoubleExtra(EXTRA_ORIGIN_LATITUDE, Double.NaN) ?: Double.NaN
        val originLon = intent?.getDoubleExtra(EXTRA_ORIGIN_LONGITUDE, Double.NaN) ?: Double.NaN
        val origin = if (originLat.isFinite() && originLon.isFinite()) {
            runCatching { GeoPoint(originLat, originLon).requireValid() }.getOrNull()
        } else null

        if (origin != null) {
            calculateRoute(origin, target, rerouting = false)
        }
        requestUpdates()
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates() {
        if (!locationManager.isLocationEnabled) {
            publish(NavigationState.Failed("System location is turned off")); stopNavigation(); return
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        providers.filter { locationManager.isProviderEnabled(it) }.forEach { provider ->
            runCatching { locationManager.requestLocationUpdates(provider, 1_000L, 0f, this) }
        }
        val lastFix = providers.mapNotNull { p ->
            runCatching { locationManager.getLastKnownLocation(p) }.getOrNull()
        }.minByOrNull { it.accuracy }
        lastFix?.let(::onLocationChanged)
    }

    override fun onLocationChanged(location: Location) {
        val target = destination ?: return
        val point = GeoPoint(location.latitude, location.longitude)
        lastOrigin = point
        val currentRoute = route
        if (currentRoute == null) {
            calculateRoute(point, target, rerouting = false)
            return
        }
        val decision = evaluator.evaluate(point, location.accuracy, location.time, System.currentTimeMillis(), target, currentRoute.geometry)
        when (decision) {
            FixDecision.Ignored -> Unit
            FixDecision.Arrived -> { publish(NavigationState.Arrived(target)); vibrate(); stopNavigation() }
            FixDecision.Reroute -> calculateRoute(point, target, rerouting = true)
            FixDecision.KeepRoute -> publishProgress(point, target, currentRoute)
        }
    }

    private fun calculateRoute(origin: GeoPoint, target: GeoPoint, rerouting: Boolean) = scope.launch {
        try {
            if (!engine.isInitialized()) {
                val manager = OfflinePackManager(applicationContext)
                val pack = (manager.state.value as? OfflinePackState.Ready)?.pack
                    ?: throw RoutingException.PackMissing("No offline pack is active")
                engine.initialize(pack)
            }
            val avoidanceAreas = if (ignoreHazardsForSession) emptyList() else {
                (application as ResQNetApplication).hazards.activeNow().map(HazardReport::toAvoidanceArea)
            }
            val calculatedRoute = engine.calculateRoute(NavigationRouteRequest(origin, target, avoidanceAreas))
            route = calculatedRoute
            maneuverIndex = -1
            publishProgress(origin, target, calculatedRoute)
            if (rerouting) vibrate()
        } catch (error: Throwable) {
            publish(NavigationState.Failed(error.message ?: "Could not calculate route"))
        }
    }

    private fun publishProgress(point: GeoPoint, target: GeoPoint, plan: RoutePlan) {
        val closest = plan.geometry.indices.minByOrNull { evaluator.distanceMeters(point, plan.geometry[it]) } ?: 0
        val next = plan.maneuvers.indexOfFirst { it.endShapeIndex >= closest }.coerceAtLeast(0)
        if (maneuverIndex >= 0 && next > maneuverIndex) vibrate()
        maneuverIndex = next
        val instruction = plan.maneuvers.getOrNull(next)?.instruction ?: "Continue to destination"
        publish(NavigationState.Active(target, instruction, plan.distanceMeters, plan.durationSeconds, plan.geometry, point))
    }

    private var initialDistanceMeters: Double = 0.0

    private data class NotifData(
        val instruction: String,
        val eta: String,
        val arrowRes: Int,
        val progress: Int
    )

    private fun getArrowDrawable(instruction: String): Int {
        val lower = instruction.lowercase()
        return when {
            lower.contains("u-turn") || lower.contains("uturn") || lower.contains("roundabout") -> R.drawable.ic_notif_arrow_uturn
            lower.contains("slight right") || lower.contains("bear right") || lower.contains("keep right") -> R.drawable.ic_notif_arrow_slight_right
            lower.contains("sharp right") || lower.contains("turn right") || lower.contains("right") -> R.drawable.ic_notif_arrow_right
            lower.contains("slight left") || lower.contains("bear left") || lower.contains("keep left") -> R.drawable.ic_notif_arrow_slight_left
            lower.contains("sharp left") || lower.contains("turn left") || lower.contains("left") -> R.drawable.ic_notif_arrow_left
            lower.contains("arrived") || lower.contains("destination") -> R.drawable.ic_notif_pin
            else -> R.drawable.ic_notif_arrow_straight
        }
    }

    private fun publish(next: NavigationState) {
        mutableState.value = next
        if (next !is NavigationState.Idle) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(next))
        }
    }

    private fun stopNavigation() {
        runCatching { locationManager.removeUpdates(this) }
        engine.close()
        initialDistanceMeters = 0.0
        if (mutableState.value !is NavigationState.Arrived) mutableState.value = NavigationState.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun vibrate() {
        getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        // Clean up legacy low-priority channel so notifications are never trapped in the "Silent" section
        runCatching { manager.deleteNotificationChannel("resqnet_navigation") }

        val channel = NotificationChannel(CHANNEL, "Navigation guidance", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Live turn-by-turn navigation guidance"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun notification(state: NavigationState): Notification {
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, NavigationForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.resqnet.app.ui.NavigateActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notifData = when (state) {
            is NavigationState.Active -> {
                if (initialDistanceMeters <= 0.0 || state.distanceMeters > initialDistanceMeters) {
                    initialDistanceMeters = state.distanceMeters
                }
                val distStr = if (state.distanceMeters >= 1000) {
                    "%.1f km".format(state.distanceMeters / 1000.0)
                } else {
                    "${state.distanceMeters.toInt()} m"
                }
                val title = "$distStr · ${state.nextInstruction}"
                val arrivalMillis = System.currentTimeMillis() + (state.durationSeconds * 1000).toLong()
                val etaFormatted = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(arrivalMillis)).lowercase()
                val eta = "Arrive $etaFormatted"
                val arrow = getArrowDrawable(state.nextInstruction)
                val prog = if (initialDistanceMeters > 0) {
                    (((initialDistanceMeters - state.distanceMeters) / initialDistanceMeters) * 100).toInt().coerceIn(5, 95)
                } else 25
                NotifData(title, eta, arrow, prog)
            }
            is NavigationState.WaitingForFix -> {
                NotifData("Starting navigation…", "Acquiring route and GPS…", R.drawable.ic_notif_arrow_straight, 5)
            }
            is NavigationState.Arrived -> {
                NotifData("Arrived at destination", "You have reached safety", R.drawable.ic_notif_pin, 100)
            }
            is NavigationState.Failed -> {
                NotifData("Navigation failed", state.message, R.drawable.ic_notif_pin, 0)
            }
            else -> {
                NotifData("Navigation stopped", "ResQNet", R.drawable.ic_notif_arrow_straight, 0)
            }
        }

        val remoteSmall = RemoteViews(packageName, R.layout.notification_navigation_small).apply {
            setTextViewText(R.id.notifInstruction, notifData.instruction)
            setTextViewText(R.id.notifEta, notifData.eta)
            setImageViewResource(R.id.notifArrow, notifData.arrowRes)
        }

        val remoteExpanded = RemoteViews(packageName, R.layout.notification_navigation_expanded).apply {
            setTextViewText(R.id.notifInstruction, notifData.instruction)
            setTextViewText(R.id.notifEta, notifData.eta)
            setImageViewResource(R.id.notifArrow, notifData.arrowRes)
            setProgressBar(R.id.notifProgress, 100, notifData.progress, false)
            setOnClickPendingIntent(R.id.notifExitButton, stop)
        }

        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_nav_navigate)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(remoteSmall)
            .setCustomBigContentView(remoteExpanded)
            .setContentTitle(notifData.instruction)
            .setContentText(notifData.eta)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp)
            .addAction(0, "Exit navigation", stop)
            .build()
    }


    override fun onDestroy() { runCatching { locationManager.removeUpdates(this) }; engine.close(); scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.resqnet.app.navigation.START"
        const val ACTION_STOP = "com.resqnet.app.navigation.STOP"
        const val EXTRA_DESTINATION_LATITUDE = "destination_latitude"
        const val EXTRA_DESTINATION_LONGITUDE = "destination_longitude"
        const val EXTRA_ORIGIN_LATITUDE = "origin_latitude"
        const val EXTRA_ORIGIN_LONGITUDE = "origin_longitude"
        const val EXTRA_IGNORE_HAZARDS = "ignore_hazards"
        private const val CHANNEL = "resqnet_navigation_v2"
        private const val NOTIFICATION_ID = 48
        private val mutableState = MutableStateFlow<NavigationState>(NavigationState.Idle)
        val state = mutableState.asStateFlow()

        fun start(context: Context, destination: GeoPoint, origin: GeoPoint? = null, ignoreHazards: Boolean = false) {
            val intent = Intent(context, NavigationForegroundService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_DESTINATION_LATITUDE, destination.latitude)
                .putExtra(EXTRA_DESTINATION_LONGITUDE, destination.longitude)
            if (origin != null) {
                intent.putExtra(EXTRA_ORIGIN_LATITUDE, origin.latitude)
                intent.putExtra(EXTRA_ORIGIN_LONGITUDE, origin.longitude)
            }
            intent.putExtra(EXTRA_IGNORE_HAZARDS, ignoreHazards)
            ContextCompat.startForegroundService(context, intent)
        }
        fun stop(context: Context) = context.startService(Intent(context, NavigationForegroundService::class.java).setAction(ACTION_STOP))
    }
}
