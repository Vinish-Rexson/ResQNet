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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.resqnet.app.R
import com.resqnet.app.ResQNetApplication
import com.resqnet.app.navigation.pack.OfflinePackManager
import com.resqnet.app.navigation.pack.OfflinePackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collect
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
                if (next == observedHazards) return@collect
                observedHazards = next
                if (!ignoreHazardsForSession && route != null) {
                    lastOrigin?.let { origin -> destination?.let { calculateRoute(origin, it, rerouting = true) } }
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
        startForeground(NOTIFICATION_ID, notification("Waiting for a usable location fix"))
        publish(NavigationState.WaitingForFix(target))
        requestUpdates()
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates() {
        if (!locationManager.isLocationEnabled) {
            publish(NavigationState.Failed("System location is turned off")); stopNavigation(); return
        }
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this)
        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let(::onLocationChanged)
    }

    override fun onLocationChanged(location: Location) {
        val target = destination ?: return
        val point = GeoPoint(location.latitude, location.longitude)
        lastOrigin = point
        val currentRoute = route
        val decision = evaluator.evaluate(point, location.accuracy, location.time, System.currentTimeMillis(), target, currentRoute?.geometry.orEmpty())
        when (decision) {
            FixDecision.Ignored -> Unit
            FixDecision.Arrived -> { publish(NavigationState.Arrived(target)); vibrate(); stopNavigation() }
            FixDecision.Reroute -> calculateRoute(point, target, rerouting = true)
            FixDecision.KeepRoute -> currentRoute?.let { publishProgress(point, target, it) } ?: calculateRoute(point, target, rerouting = false)
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
            val avoidanceAreas = if (ignoreHazardsForSession) emptyList() else (application as ResQNetApplication).hazards.activeNow()
                .map(HazardReport::toAvoidanceArea)
            route = engine.calculateRoute(NavigationRouteRequest(origin, target, avoidanceAreas))
            maneuverIndex = -1
            publishProgress(origin, target, route!!)
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
        publish(NavigationState.Active(target, instruction, plan.distanceMeters, plan.durationSeconds))
    }

    private fun publish(next: NavigationState) {
        mutableState.value = next
        val text = (next as? NavigationState.Active)?.nextInstruction ?: when (next) {
            is NavigationState.WaitingForFix -> "Waiting for location"
            is NavigationState.Arrived -> "Arrived"
            is NavigationState.Failed -> next.message
            else -> "Navigation stopped"
        }
        if (next !is NavigationState.Idle) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun stopNavigation() {
        runCatching { locationManager.removeUpdates(this) }
        engine.close()
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
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Navigation guidance", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, NavigationForegroundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_nav_navigate).setContentTitle("ResQNet navigation")
            .setContentText(text).setOngoing(true).addAction(0, "Stop", stop).build()
    }

    override fun onDestroy() { runCatching { locationManager.removeUpdates(this) }; engine.close(); scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.resqnet.app.navigation.START"
        const val ACTION_STOP = "com.resqnet.app.navigation.STOP"
        const val EXTRA_DESTINATION_LATITUDE = "destination_latitude"
        const val EXTRA_DESTINATION_LONGITUDE = "destination_longitude"
        const val EXTRA_IGNORE_HAZARDS = "ignore_hazards"
        private const val CHANNEL = "resqnet_navigation"
        private const val NOTIFICATION_ID = 48
        private val mutableState = MutableStateFlow<NavigationState>(NavigationState.Idle)
        val state = mutableState.asStateFlow()

        fun start(context: Context, destination: GeoPoint, ignoreHazards: Boolean = false) {
            ContextCompat.startForegroundService(context, Intent(context, NavigationForegroundService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_DESTINATION_LATITUDE, destination.latitude).putExtra(EXTRA_DESTINATION_LONGITUDE, destination.longitude)
                .putExtra(EXTRA_IGNORE_HAZARDS, ignoreHazards))
        }
        fun stop(context: Context) = context.startService(Intent(context, NavigationForegroundService::class.java).setAction(ACTION_STOP))
    }
}
