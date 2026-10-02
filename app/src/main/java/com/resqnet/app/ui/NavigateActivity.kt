package com.resqnet.app.ui

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.util.Rational
import java.util.Locale
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.resqnet.app.R
import com.resqnet.app.ResQNetApplication
import com.resqnet.app.navigation.AvoidanceArea
import com.resqnet.app.navigation.GeoPoint
import com.resqnet.app.navigation.HazardReport
import com.resqnet.app.navigation.HazardType
import com.resqnet.app.navigation.InstalledRegionPack
import com.resqnet.app.navigation.NavigationForegroundService
import com.resqnet.app.navigation.NavigationRouteRequest
import com.resqnet.app.navigation.NavigationState
import com.resqnet.app.navigation.Shelter
import com.resqnet.app.navigation.ShelterRepository
import com.resqnet.app.navigation.ShelterRouteSelector
import com.resqnet.app.navigation.ValhallaRoutingEngine
import com.resqnet.app.navigation.pack.OfflinePackManager
import com.resqnet.app.navigation.pack.OfflinePackState
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Polygon
import org.maplibre.android.annotations.PolygonOptions

private const val PLACEHOLDER_BASEMAP_MAX_BYTES = 64 * 1024L
private const val BUNDLED_MUMBAI_PACK_ASSET = "mumbai-demo-pack.zip"
private const val ONLINE_OSM_STYLE = """{
  "version":8,
  "name":"ResQNet online Mumbai demo",
  "sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"tileSize":256,"attribution":"© OpenStreetMap contributors"}},
  "layers":[{"id":"osm","type":"raster","source":"osm"}]
}"""

/** Pack and destination selection. Live location guidance starts in M4. */
class NavigateActivity : AppCompatActivity() {
    private lateinit var packManager: OfflinePackManager
    private lateinit var mapView: MapView
    private lateinit var status: TextView
    private lateinit var sheltersView: TextView
    private lateinit var routeSummary: TextView
    private lateinit var zoomFitButton: com.google.android.material.button.MaterialButton
    private lateinit var clearPinsButton: com.google.android.material.button.MaterialButton
    private lateinit var sheltersButton: com.google.android.material.button.MaterialButton
    private lateinit var pipNavigationBanner: View
    private lateinit var pipManeuverIcon: ImageView
    private lateinit var pipDistanceText: TextView
    private lateinit var pipInstructionText: TextView
    private lateinit var pipEtaText: TextView
    private var readyPack: InstalledRegionPack? = null
    private var pinnedStart: GeoPoint? = null
    private var shelters: List<Shelter> = emptyList()
    private val routingEngine by lazy { ValhallaRoutingEngine(applicationContext) }
    private var map: MapLibreMap? = null
    private var navigationDestination: GeoPoint? = null
    private var currentLocationMarker: Marker? = null
    private var activeRoute: Polyline? = null
    private val hazardPolygons = mutableListOf<Polygon>()
    private var hazardReports: List<HazardReport> = emptyList()
    private var placingHazard = false
    private var previewIgnoringHazards = false
    private var currentLocation: GeoPoint? = null
    private var bundledPackInstallRequested = false
    private var nearestRouteInProgress = false
    private var findNearestAfterLocation = false
    private var headingDegrees = 0f
    private var sharedLocationMarker: Marker? = null
    private var pendingTargetCoordinate: GeoPoint? = null
    private var pendingTargetLabel: String? = null
    /** Markers added by long-press so they can be cleared as a group. */
    private val pinnedMarkers: MutableList<Marker> = mutableListOf()
    /** Geometry of the last computed route for zoom-fit calculation. */
    private var currentRouteGeometry: List<GeoPoint>? = null
    /** Text-to-speech engine for spoken navigation instructions. */
    private var tts: TextToSpeech? = null
    /** Prevents re-announcing the same instruction on every location tick. */
    private var lastSpokenInstruction: String = ""

    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }
    private val headingListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val matrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(matrix, event.values)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(matrix, orientation)
            val newHeading = ((Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f)
            if (kotlin.math.abs(newHeading - headingDegrees) < 5f) return
            headingDegrees = newHeading
            updateCurrentLocationHeading()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private var centerOnNextFix = false

    private val mapLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val shouldCenter = centerOnNextFix || (currentLocation == null)
            if (shouldCenter) centerOnNextFix = false
            setCurrentLocation(location, centerMap = shouldCenter)
        }
    }

    private val importPack = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            runCatching { packManager.importFrom(uri) }
                .onFailure { showError(it.message ?: "Could not import pack") }
        }
    }
    private val requestLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true || granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true) startNavigationIfReady()
        else showError("Location permission is required for live navigation")
    }
    private val requestMapLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true || granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            refreshCurrentLocation(forceCenter = true)
        } else {
            showError("Location permission is needed to place your position on the map")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_navigate)
        setSupportActionBar(findViewById(R.id.toolbar))
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).navigationIcon = null // top-level tab
        setupBottomNav(this, R.id.nav_navigate)
        packManager = OfflinePackManager(this, routingEngine = routingEngine)
        mapView = findViewById(R.id.mapView)
        status = findViewById(R.id.packStatus)
        sheltersView = findViewById(R.id.shelterList)
        routeSummary = findViewById(R.id.routeSummary)
        zoomFitButton = findViewById(R.id.zoomFitButton)
        clearPinsButton = findViewById(R.id.clearPinsButton)
        sheltersButton = findViewById(R.id.sheltersButton)
        pipNavigationBanner = findViewById(R.id.pipNavigationBanner)
        pipManeuverIcon = findViewById(R.id.pipManeuverIcon)
        pipDistanceText = findViewById(R.id.pipDistanceText)
        pipInstructionText = findViewById(R.id.pipInstructionText)
        pipEtaText = findViewById(R.id.pipEtaText)

        initTts()

        findViewById<MaterialButton>(R.id.importPackButton).setOnClickListener {
            importPack.launch(arrayOf("application/zip", "application/x-zip-compressed"))
        }
        findViewById<MaterialButton>(R.id.downloadPackButton).setOnClickListener {
            lifecycleScope.launch {
                runCatching { packManager.fetchCatalog().firstOrNull() }
                    .onSuccess { entry ->
                        if (entry == null) showError("No region packs are available")
                        else packManager.enqueueDownload(entry)
                    }
                    .onFailure { showError(it.message ?: "Could not retrieve pack catalog") }
            }
        }
        findViewById<MaterialButton>(R.id.nearestShelterButton).setOnClickListener { selectNearestShelter() }
        findViewById<MaterialButton>(R.id.reportHazardButton).setOnClickListener { beginHazardPlacement() }
        findViewById<MaterialButton>(R.id.startNavigationButton).setOnClickListener {
            val currentState = NavigationForegroundService.state.value
            if (currentState is NavigationState.Active || currentState is NavigationState.WaitingForFix) {
                NavigationForegroundService.stop(this)
                routeSummary.text = "Navigation stopped."
                return@setOnClickListener
            }
            if (navigationDestination == null) {
                showError("Select a destination on the map or from shelters first")
                return@setOnClickListener
            }
            requestNavigationPermission()
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.myLocationButton).setOnClickListener { onMyLocationClicked() }
        clearPinsButton.setOnClickListener { clearPins() }
        zoomFitButton.setOnClickListener { zoomToFitRoute() }
        sheltersButton.setOnClickListener { showShelterBottomSheet() }

        setStartButtonEnabled(false)
        requestCurrentLocation()

        // Render synchronously as well as observing later state changes. This
        // avoids leaving the XML placeholder visible before the lifecycle
        // collector is started on slower devices.
        renderPackState(packManager.state.value)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                packManager.state.collect(::renderPackState)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NavigationForegroundService.state.collect { navigation ->
                    val isNavigating = navigation is NavigationState.Active || navigation is NavigationState.WaitingForFix
                    updatePipParams(isNavigating)
                    val startButton = findViewById<MaterialButton>(R.id.startNavigationButton)
                    when (navigation) {
                        is NavigationState.Active -> {
                            val arrow = getManeuverArrow(navigation.nextInstruction)
                            startButton.isEnabled = true
                            startButton.text = "⏹ Stop Navigation"
                            startButton.alpha = 1f
                            startButton.backgroundTintList = ContextCompat.getColorStateList(this@NavigateActivity, R.color.alert)
                            routeSummary.text = "$arrow  ${navigation.nextInstruction}"
                            val minutes = (navigation.durationSeconds / 60.0).toInt().coerceAtLeast(1)
                            val distStr = if (navigation.distanceMeters >= 1000) {
                                "%.1f km".format(navigation.distanceMeters / 1000.0)
                            } else {
                                "${navigation.distanceMeters.toInt()} m"
                            }
                            sheltersView.text = "$distStr remaining · about $minutes min"

                            // Update Google Maps-style PiP Navigation Banner
                            pipDistanceText.text = "$distStr · about $minutes min"
                            pipInstructionText.text = navigation.nextInstruction
                            pipManeuverIcon.setImageResource(getManeuverArrowDrawable(navigation.nextInstruction))
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
                                pipNavigationBanner.visibility = View.VISIBLE
                            }

                            navigation.currentPoint?.let { point ->
                                currentLocation = point
                                showCurrentLocationMarker(point, centerMap = false)
                                val targetPos = LatLng(point.latitude, point.longitude)
                                val camTarget = map?.cameraPosition?.target
                                val distMeters = if (camTarget != null) camTarget.distanceTo(targetPos) else 999.0
                                val curZoom = map?.cameraPosition?.zoom ?: 0.0
                                if (distMeters > 3.0 || curZoom < 14.0) {
                                    val isPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode
                                    map?.easeCamera(
                                        CameraUpdateFactory.newCameraPosition(
                                            CameraPosition.Builder()
                                                .target(targetPos)
                                                .zoom(if (isPip) 16.8 else 16.2)
                                                .bearing(if (isPip) headingDegrees.toDouble() else (map?.cameraPosition?.bearing ?: 0.0))
                                                .tilt(if (isPip) 35.0 else 0.0)
                                                .build()
                                        ),
                                        600
                                    )
                                }
                            }

                            if (navigation.geometry.isNotEmpty() && (activeRoute == null || currentRouteGeometry != navigation.geometry)) {
                                currentRouteGeometry = navigation.geometry
                                zoomFitButton.visibility = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) View.GONE else View.VISIBLE
                                map?.let { activeMap ->
                                    activeRoute?.let(activeMap::removePolyline)
                                    activeRoute = activeMap.addPolyline(
                                        PolylineOptions()
                                            .addAll(navigation.geometry.map { LatLng(it.latitude, it.longitude) })
                                            .color(Color.parseColor("#64B5F6"))
                                            .width(10f)
                                    )
                                }
                            }
                            if (navigation.nextInstruction != lastSpokenInstruction) {
                                lastSpokenInstruction = navigation.nextInstruction
                                speak(navigation.nextInstruction)
                            }
                        }
                        is NavigationState.WaitingForFix -> {
                            startButton.isEnabled = true
                            startButton.text = "⏹ Stop Navigation"
                            startButton.alpha = 1f
                            startButton.backgroundTintList = ContextCompat.getColorStateList(this@NavigateActivity, R.color.alert)
                            routeSummary.text = "🧭  Starting navigation…"
                            sheltersView.text = "Acquiring GPS fix and route…"
                            pipDistanceText.text = "Acquiring GPS"
                            pipInstructionText.text = "Calculating route…"
                            pipManeuverIcon.setImageResource(R.drawable.ic_notif_arrow_straight)
                            pipEtaText.text = "Starting navigation"
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
                                pipNavigationBanner.visibility = View.VISIBLE
                            }
                            if (lastSpokenInstruction != "Waiting for GPS signal") {
                                lastSpokenInstruction = "Waiting for GPS signal"
                                speak("Starting navigation. Waiting for GPS signal")
                            }
                        }
                        is NavigationState.Arrived -> {
                            startButton.isEnabled = true
                            startButton.text = "Start Navigation"
                            startButton.alpha = 1f
                            startButton.backgroundTintList = ContextCompat.getColorStateList(this@NavigateActivity, R.color.resq_primary)
                            routeSummary.text = "🏁  Arrived at destination"
                            sheltersView.text = "You have reached your safe destination"
                            pipDistanceText.text = "Arrived"
                            pipInstructionText.text = "You have reached safety"
                            pipManeuverIcon.setImageResource(R.drawable.ic_notif_pin)
                            pipEtaText.text = "Destination reached"
                            speak("You have arrived at your destination")
                            lastSpokenInstruction = ""
                        }
                        is NavigationState.Failed -> {
                            startButton.text = "Start Navigation"
                            startButton.backgroundTintList = ContextCompat.getColorStateList(this@NavigateActivity, R.color.resq_primary)
                            setStartButtonEnabled(navigationDestination != null)
                            pipDistanceText.text = "Navigation failed"
                            pipInstructionText.text = navigation.message
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
                                pipNavigationBanner.visibility = View.GONE
                            }
                            showError(navigation.message)
                            lastSpokenInstruction = ""
                        }
                        is NavigationState.Idle -> {
                            startButton.text = "Start Navigation"
                            startButton.backgroundTintList = ContextCompat.getColorStateList(this@NavigateActivity, R.color.resq_primary)
                            setStartButtonEnabled(navigationDestination != null)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
                                pipNavigationBanner.visibility = View.GONE
                            }
                            lastSpokenInstruction = ""
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                (application as ResQNetApplication).hazards.observeActive().collect { reports ->
                    hazardReports = reports
                    renderHazards()
                }
            }
        }
        installBundledMumbaiPackIfNeeded()
        handleTargetIntent(intent)
    }

    /** Makes the test build usable without a file picker or map catalog. */
    private fun installBundledMumbaiPackIfNeeded() {
        if (bundledPackInstallRequested || packManager.state.value !is OfflinePackState.Absent) return
        if (!packManager.hasBundledAsset(BUNDLED_MUMBAI_PACK_ASSET)) return
        bundledPackInstallRequested = true
        lifecycleScope.launch {
            runCatching { packManager.installBundledAsset(BUNDLED_MUMBAI_PACK_ASSET) }
                .onFailure { showError(it.message ?: "Could not install the bundled Mumbai map") }
        }
    }

    private fun renderPackState(state: OfflinePackState) {
        findViewById<View>(R.id.mapManagementActions).visibility =
            if (state is OfflinePackState.Ready) View.GONE else View.VISIBLE
        status.text = when (state) {
            OfflinePackState.Absent -> {
                showOnlineMap()
                "Online map ready. Preparing the Mumbai map for offline use."
            }
            is OfflinePackState.Downloading -> "Downloading offline pack: ${state.bytesDownloaded} bytes"
            is OfflinePackState.Importing -> "Importing offline pack: ${state.bytesCopied} bytes"
            OfflinePackState.Verifying -> "Verifying offline pack…"
            OfflinePackState.Installing -> "Installing offline pack…"
            is OfflinePackState.UpdateAvailable -> "Update available: ${state.available.version}"
            is OfflinePackState.Failed -> "Offline pack error: ${state.reason.message}"
            is OfflinePackState.Ready -> {
                readyPack = state.pack
                showShelters(state.pack)
                if (!routingEngine.isInitialized()) lifecycleScope.launch {
                    runCatching { routingEngine.initialize(state.pack) }
                        .onFailure { routeSummary.text = "Routing data could not initialize: ${it.message}" }
                }
                "Offline pack ready: ${state.pack.regionId} ${state.pack.version}"
            }
        }
    }

    private var lastLoadedPackRegionId: String? = null

    private fun showShelters(pack: InstalledRegionPack) {
        shelters = ShelterRepository().load(pack)
        sheltersView.text = if (shelters.isEmpty()) "No shelter candidates in this map pack."
        else "${shelters.size} shelter candidates loaded"
        // Show the shelters FAB when shelters are available so users can browse the list
        if (shelters.isNotEmpty()) sheltersButton.visibility = View.VISIBLE

        // If the map is already loaded and initialized with this pack, do not re-apply style and reset camera!
        if (map?.style != null && lastLoadedPackRegionId == pack.regionId) {
            return
        }
        lastLoadedPackRegionId = pack.regionId

        val style = java.io.File(pack.directory, "style.json")
        val basemap = java.io.File(pack.directory, "basemap.pmtiles")
        val useOnlineDemoMap = !basemap.isFile || basemap.length() <= PLACEHOLDER_BASEMAP_MAX_BYTES
        if (!useOnlineDemoMap && !style.isFile) {
            routeSummary.text = "Pack is missing its offline style. Map rendering is unavailable."
            return
        }
        val styleText = if (useOnlineDemoMap) ONLINE_OSM_STYLE else style.readText()
        if (!useOnlineDemoMap && (styleText.contains("http://") || styleText.contains("https://"))) {
            routeSummary.text = "Pack style references the network and was rejected."
            return
        }
        findViewById<TextView>(R.id.mapAttribution).text = if (useOnlineDemoMap) {
            "Online demonstration map · © OpenStreetMap contributors"
        } else "Offline Mumbai map · © OpenStreetMap contributors"
        mapView.getMapAsync { map ->
            this.map = map
            if (useOnlineDemoMap) {
                map.setStyle(Style.Builder().fromJson(styleText)) { configureMap(map) }
            } else {
                map.setStyle("file://${style.absolutePath}") { configureMap(map) }
            }
        }
        mapView.visibility = View.VISIBLE
        if (useOnlineDemoMap) {
            routeSummary.text = "This test pack has no offline street map. Showing Mumbai online; the completed PMTiles pack will work offline."
        }
    }

    /** Keeps the map useful on first launch while the offline pack is downloading. */
    private fun showOnlineMap() {
        if (map?.style != null) return
        findViewById<TextView>(R.id.mapAttribution).text = "Online map · © OpenStreetMap contributors"
        mapView.getMapAsync { map ->
            this.map = map
            map.setStyle(Style.Builder().fromJson(ONLINE_OSM_STYLE)) {
                configureMap(map)
                val current = currentLocation
                if (current != null) {
                    map.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(current.latitude, current.longitude))
                        .zoom(15.5)
                        .build()
                } else {
                    map.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(19.0760, 72.8777))
                        .zoom(11.5)
                        .build()
                }
            }
        }
        mapView.visibility = View.VISIBLE
    }

    private fun configureMap(map: MapLibreMap) {
        val current = currentLocation
        if (isActivelyNavigating() && current != null) {
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(current.latitude, current.longitude))
                .zoom(16.5)
                .bearing(headingDegrees.toDouble())
                .build()
        } else if (current != null) {
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(current.latitude, current.longitude))
                .zoom(15.5)
                .build()
        } else {
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(19.2, 72.9))
                .zoom(10.0)
                .build()
        }
        shelters.take(MAX_VISIBLE_SHELTER_MARKERS).forEach { shelter ->
            map.addMarker(MarkerOptions().position(LatLng(shelter.latitude, shelter.longitude)).title(shelter.name))
        }
        map.setOnMarkerClickListener { marker ->
            val pos = marker.position
            val shelter = shelters.find {
                kotlin.math.abs(it.latitude - pos.latitude) < 0.0001 &&
                kotlin.math.abs(it.longitude - pos.longitude) < 0.0001
            }
            if (shelter != null) {
                confirmShelter(shelter)
                true
            } else {
                false
            }
        }
        map.addOnMapLongClickListener { point ->
            val pin = GeoPoint(point.latitude, point.longitude)
            val amberIcon = IconFactory.getInstance(this).fromBitmap(pinnedMarkerBitmap())
            if (pinnedStart == null) {
                pinnedStart = pin
                routeSummary.text = "Start pin set. Choose a shelter or long-press again to set a destination."
                val marker = map.addMarker(
                    MarkerOptions().position(point).title("Start").icon(amberIcon)
                )
                pinnedMarkers.add(marker)
            } else {
                navigationDestination = pin
                val marker = map.addMarker(
                    MarkerOptions().position(point).title("Destination").icon(amberIcon)
                )
                pinnedMarkers.add(marker)
                val start = pinnedStart ?: currentLocation
                if (start != null) {
                    routeSummary.text = "Calculating route to destination…"
                    lifecycleScope.launch {
                        if (!routingEngine.isInitialized()) {
                            readyPack?.let { runCatching { routingEngine.initialize(it) } }
                        }
                        val plan = runCatching { routingEngine.calculateRoute(NavigationRouteRequest(start, pin, currentAvoidanceAreas())) }.getOrNull()
                        if (plan != null) {
                            showRoute("Custom destination", pin, plan.distanceMeters, plan.durationSeconds, plan.geometry)
                        } else {
                            routeSummary.text = "Destination set at ${"%.5f".format(point.latitude)}, ${"%.5f".format(point.longitude)}. No walking route found."
                            setStartButtonEnabled(true)
                        }
                    }
                } else {
                    routeSummary.text = "Destination pin set at ${"%.5f".format(point.latitude)}, ${"%.5f".format(point.longitude)}. Tap My Location or long-press start pin."
                    setStartButtonEnabled(true)
                }
            }
            true
        }
        map.addOnMapClickListener { point ->
            val selected = GeoPoint(point.latitude, point.longitude)
            if (placingHazard) {
                placingHazard = false
                findViewById<MaterialButton>(R.id.reportHazardButton).text = "Report"
                showHazardEditor(selected)
                true
            } else {
                hazardReports.firstOrNull { distanceMeters(selected, it.center) <= it.radiusMeters }
                    ?.let(::showHazardDetails)
                false
            }
        }

        currentLocation?.let { point -> showCurrentLocationMarker(point, centerMap = false) }
        renderHazards()
        requestCurrentLocation()
        pendingTargetCoordinate?.let { point ->
            val label = pendingTargetLabel ?: "Shared Location"
            pendingTargetCoordinate = null
            pendingTargetLabel = null
            val nearby = getNearbyAreaDescription(point)
            focusOnTargetCoordinate(point, label, nearby, autoStart = false)
        }
    }

    private fun beginHazardPlacement() {
        if (map == null) { showError("Wait for the map to load before reporting an area"); return }
        placingHazard = !placingHazard
        findViewById<MaterialButton>(R.id.reportHazardButton).text = if (placingHazard) "Cancel" else "Report"
        routeSummary.text = if (placingHazard) "Tap the affected flood or unsafe area on the map." else "Hazard reporting cancelled."
    }

    private fun showHazardEditor(center: GeoPoint, existing: HazardReport? = null) {
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_report_hazard, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheetView)
        dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundResource(android.R.color.transparent)

        val tvTitle = sheetView.findViewById<TextView>(R.id.tvSheetTitle)
        val btnClose = sheetView.findViewById<ImageButton>(R.id.btnCloseSheet)
        val rgType = sheetView.findViewById<RadioGroup>(R.id.rgHazardType)
        val rbFlood = sheetView.findViewById<RadioButton>(R.id.rbFlood)
        val rbUnsafe = sheetView.findViewById<RadioButton>(R.id.rbUnsafe)
        val tvRadiusLabel = sheetView.findViewById<TextView>(R.id.tvRadiusLabel)
        val sbRadius = sheetView.findViewById<SeekBar>(R.id.sbRadius)
        val spExpiry = sheetView.findViewById<Spinner>(R.id.spExpiry)
        val etNote = sheetView.findViewById<EditText>(R.id.etNote)
        val btnSave = sheetView.findViewById<MaterialButton>(R.id.btnSaveReport)
        val btnCancel = sheetView.findViewById<MaterialButton>(R.id.btnCancelReport)

        tvTitle.text = if (existing == null) "Report area" else "Edit report"
        btnSave.text = if (existing == null) "SAVE REPORT" else "SAVE CHANGES"

        val floodId = View.generateViewId()
        val unsafeId = View.generateViewId()
        rbFlood.id = floodId
        rbUnsafe.id = unsafeId
        rgType.check(if (existing?.type == HazardType.UNSAFE_AREA) unsafeId else floodId)

        sbRadius.max = (AvoidanceArea.MAX_RADIUS_METERS - AvoidanceArea.MIN_RADIUS_METERS) / 25
        sbRadius.progress = ((existing?.radiusMeters ?: HazardType.FLOOD.defaultRadiusMeters) - AvoidanceArea.MIN_RADIUS_METERS) / 25

        fun selectedType() = if (rgType.checkedRadioButtonId == unsafeId) HazardType.UNSAFE_AREA else HazardType.FLOOD
        fun refreshRadius() {
            tvRadiusLabel.text = "Avoidance radius: ${AvoidanceArea.MIN_RADIUS_METERS + sbRadius.progress * 25} m"
        }
        refreshRadius()

        sbRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = refreshRadius()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        rgType.setOnCheckedChangeListener { _, _ ->
            if (existing == null) {
                sbRadius.progress = (selectedType().defaultRadiusMeters - AvoidanceArea.MIN_RADIUS_METERS) / 25
            }
        }

        val hours = listOf(1, 6, 12, 24)
        spExpiry.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, hours.map { "$it hour${if (it == 1) "" else "s"}" })
        spExpiry.setSelection(if (existing?.type == HazardType.UNSAFE_AREA) 3 else 1)

        etNote.setText(existing?.note.orEmpty())

        btnClose.setOnClickListener { dialog.dismiss() }
        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val type = selectedType()
            val radiusMeters = AvoidanceArea.MIN_RADIUS_METERS + sbRadius.progress * 25
            val expiresAt = System.currentTimeMillis() + hours[spExpiry.selectedItemPosition] * 60 * 60 * 1000L
            lifecycleScope.launch {
                runCatching {
                    if (existing == null) {
                        (application as ResQNetApplication).hazards.create(type, center, radiusMeters, expiresAt, etNote.text?.toString())
                    } else {
                        (application as ResQNetApplication).hazards.update(existing, type, radiusMeters, expiresAt, etNote.text?.toString())
                    }
                }.onSuccess {
                    dialog.dismiss()
                    routeSummary.text = "${type.label} report saved. New routes will avoid this area."
                }.onFailure {
                    showError(it.message ?: "Could not save report")
                }
            }
        }

        dialog.show()
    }

    private fun showHazardDetails(report: HazardReport) {
        MaterialAlertDialogBuilder(this).setTitle(report.type.label)
            .setMessage("Avoidance radius: ${report.radiusMeters} m" + report.note?.let { "\n\n$it" }.orEmpty())
            .setNegativeButton("Delete") { _, _ -> lifecycleScope.launch { (application as ResQNetApplication).hazards.delete(report.id) } }
            .setNeutralButton("Edit") { _, _ -> showHazardEditor(report.center, report) }
            .setPositiveButton("Mark resolved") { _, _ -> lifecycleScope.launch { (application as ResQNetApplication).hazards.resolve(report.id) } }.show()
    }

    private fun renderHazards() {
        val activeMap = map ?: return
        hazardPolygons.forEach(activeMap::removePolygon); hazardPolygons.clear()
        hazardReports.forEach { report ->
            val color = if (report.type == HazardType.FLOOD) Color.rgb(33, 150, 243) else Color.rgb(239, 108, 0)
            val ring = report.toAvoidanceArea().toValhallaPolygon().map { LatLng(it[1], it[0]) }
            hazardPolygons += activeMap.addPolygon(PolygonOptions().addAll(ring).fillColor(Color.argb(72, Color.red(color), Color.green(color), Color.blue(color))).strokeColor(color))
        }
    }

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat = Math.toRadians(b.latitude - a.latitude); val lon = Math.toRadians(b.longitude - a.longitude)
        val value = kotlin.math.sin(lat / 2) * kotlin.math.sin(lat / 2) + kotlin.math.cos(Math.toRadians(a.latitude)) * kotlin.math.cos(Math.toRadians(b.latitude)) * kotlin.math.sin(lon / 2) * kotlin.math.sin(lon / 2)
        return 6_371_000.0 * 2 * kotlin.math.atan2(kotlin.math.sqrt(value), kotlin.math.sqrt(1 - value))
    }

    private fun onMyLocationClicked() {
        if (!hasLocationPermission()) {
            centerOnNextFix = true
            requestMapLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }

        val manager = getSystemService(LocationManager::class.java)
        if (manager != null && !manager.isLocationEnabled) {
            showError("Turn on system location to use your current position")
            return
        }

        centerOnNextFix = true

        val loc = currentLocation
        if (loc != null) {
            pinnedStart = loc
            map?.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(loc.latitude, loc.longitude))
                        .zoom(16.5)
                        .build()
                ),
                500
            )
            Toast.makeText(this, "Centered on your location", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Acquiring GPS location…", Toast.LENGTH_SHORT).show()
        }

        refreshCurrentLocation(forceCenter = true)
    }

    private fun requestCurrentLocation() {
        if (hasLocationPermission()) refreshCurrentLocation()
        else requestMapLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    @Suppress("MissingPermission")
    private fun refreshCurrentLocation(forceCenter: Boolean = false) {
        val manager = getSystemService(LocationManager::class.java) ?: return
        if (!manager.isLocationEnabled) {
            showError("Turn on system location to use your current position")
            return
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        val lastFix = providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.minByOrNull { it.accuracy }
        if (lastFix != null) {
            val shouldCenter = forceCenter || centerOnNextFix || (currentLocation == null)
            if (shouldCenter) centerOnNextFix = false
            setCurrentLocation(lastFix, centerMap = shouldCenter)
        }
        providers.filter { manager.isProviderEnabled(it) }.forEach { provider ->
            runCatching { manager.requestLocationUpdates(provider, 1_000L, 0f, mapLocationListener) }
        }
    }

    private fun setCurrentLocation(location: Location, centerMap: Boolean = false) {
        val point = runCatching { GeoPoint(location.latitude, location.longitude).requireValid() }.getOrNull() ?: return
        val isFirst = currentLocation == null
        currentLocation = point
        if (pinnedStart == null) {
            pinnedStart = point
        }
        showCurrentLocationMarker(point, centerMap = centerMap)
        if (isFirst && navigationDestination == null) {
            routeSummary.text = "Current location found. Choose a shelter or long-press to set a destination."
        }
        if (findNearestAfterLocation) {
            findNearestAfterLocation = false
            selectNearestShelter()
        }
    }

    private fun showCurrentLocationMarker(point: GeoPoint, centerMap: Boolean) {
        val activeMap = map ?: return
        val pos = LatLng(point.latitude, point.longitude)
        val marker = currentLocationMarker
        if (marker != null) {
            marker.position = pos
            activeMap.updateMarker(marker)
        } else {
            currentLocationMarker = activeMap.addMarker(
                MarkerOptions()
                    .position(pos)
                    .title("Your current location")
                    .icon(IconFactory.getInstance(this).fromBitmap(currentLocationMarkerBitmap()))
            )
        }
        if (centerMap) {
            activeMap.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(pos)
                        .zoom(16.5)
                        .build()
                ),
                500
            )
        }
    }

    /**
     * High-visibility Google Maps style location puck:
     * - Scaled by device screen density (56 dp wide, highly visible)
     * - Soft pulsing outer blue halo
     * - Directional heading beam cone tracking user's compass rotation
     * - Crisp white outer elevation ring
     * - Vibrant Google Blue center puck with white center pinpoint
     */
    private fun currentLocationMarkerBitmap(): Bitmap {
        val sizeDp = 56
        val density = resources.displayMetrics.density
        val size = (sizeDp * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = size / 2f

        // 1. Soft pulsing outer blue halo
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#331A73E8")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, center - 2 * density, haloPaint)

        // 2. Directional heading cone beam (Google Maps flashlight cone)
        val conePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4D1A73E8")
            style = Paint.Style.FILL
        }
        val coneRect = android.graphics.RectF(
            center - center * 0.95f,
            center - center * 0.95f,
            center + center * 0.95f,
            center + center * 0.95f
        )
        val conePath = android.graphics.Path().apply {
            moveTo(center, center)
            arcTo(coneRect, headingDegrees - 35f, 70f, false)
            close()
        }
        canvas.drawPath(conePath, conePaint)

        // 3. Crisp white outer ring with drop shadow
        val whiteRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            setShadowLayer(4 * density, 0f, 2 * density, Color.parseColor("#40000000"))
        }
        canvas.drawCircle(center, center, 14 * density, whiteRingPaint)

        // 4. Vibrant Google Blue solid center puck
        val puckPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A73E8")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, 11 * density, puckPaint)

        // 5. Crisp white center dot
        val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, 3.5f * density, centerDotPaint)

        return bitmap
    }

    private var lastHeadingBitmapUpdate = 0L
    private fun updateCurrentLocationHeading() {
        val now = System.currentTimeMillis()
        if (now - lastHeadingBitmapUpdate < 250L) return
        lastHeadingBitmapUpdate = now
        val marker = currentLocationMarker ?: return
        marker.icon = IconFactory.getInstance(this).fromBitmap(currentLocationMarkerBitmap())
        map?.updateMarker(marker)
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun confirmShelter(shelter: Shelter) {
        if (shelter.verified) {
            routeToShelter(shelter)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Unverified shelter")
            .setMessage("${shelter.name} has not been verified recently. Confirm before using it as a destination.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use shelter") { _, _ ->
                routeToShelter(shelter)
            }
            .show()
    }

    private fun chooseShelter() {
        if (shelters.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Choose a shelter")
            .setItems(shelters.map { if (it.verified) it.name else "${it.name} — Unverified" }.toTypedArray()) { _, index ->
                confirmShelter(shelters[index])
            }
            .show()
    }

    private fun selectNearestShelter(ignoreHazards: Boolean = false) {
        if (nearestRouteInProgress) return
        val start = pinnedStart
        if (start == null) {
            findNearestAfterLocation = true
            requestCurrentLocation()
            routeSummary.text = "Getting your location before finding the nearest shelter…"
            return
        }
        nearestRouteInProgress = true
        findViewById<MaterialButton>(R.id.nearestShelterButton).apply {
            isEnabled = false
            text = "Finding…"
        }
        setStartButtonEnabled(false)
        routeSummary.text = "Finding the nearest accessible shelter…"
        lifecycleScope.launch {
            val result = runCatching { ShelterRouteSelector(routingEngine).chooseBest(start, shelters, if (ignoreHazards) emptyList() else currentAvoidanceAreas()) }.getOrNull()
            nearestRouteInProgress = false
            findViewById<MaterialButton>(R.id.nearestShelterButton).apply {
                isEnabled = true
                text = "Nearest"
            }
            if (result == null && !ignoreHazards) noSafeRoute { selectNearestShelter(ignoreHazards = true) }
            else if (result == null) routeSummary.text = "No walking route to the nearest shelter candidates was found."
            else showRoute(result.shelter, result.route.distanceMeters, result.route.durationSeconds, result.route.geometry, ignoreHazards)
        }
    }

    private fun routeToShelter(shelter: Shelter, ignoreHazards: Boolean = false, onRouteReady: (() -> Unit)? = null) {
        val start = pinnedStart ?: currentLocation
        if (start == null) {
            navigationDestination = GeoPoint(shelter.latitude, shelter.longitude)
            setStartButtonEnabled(true)
            routeSummary.text = "Selected destination: ${shelter.name}. Long-press the map to set a start pin or enable GPS."
            return
        }
        routeSummary.text = "Calculating route to ${shelter.name}…"
        lifecycleScope.launch {
            if (!routingEngine.isInitialized()) {
                readyPack?.let { runCatching { routingEngine.initialize(it) } }
            }
            val result = runCatching { ShelterRouteSelector(routingEngine).chooseBest(start, listOf(shelter), if (ignoreHazards) emptyList() else currentAvoidanceAreas()) }.getOrNull()
            if (result == null) {
                if (!ignoreHazards) noSafeRoute { routeToShelter(shelter, ignoreHazards = true, onRouteReady = onRouteReady) }
                else {
                    navigationDestination = GeoPoint(shelter.latitude, shelter.longitude)
                    setStartButtonEnabled(true)
                    routeSummary.text = "No walking route to ${shelter.name} was found."
                }
            } else {
                showRoute(result.shelter, result.route.distanceMeters, result.route.durationSeconds, result.route.geometry, ignoreHazards)
                onRouteReady?.invoke()
            }
        }
    }

    private suspend fun currentAvoidanceAreas(): List<AvoidanceArea> =
        (application as ResQNetApplication).hazards.activeNow().map(HazardReport::toAvoidanceArea)

    private fun noSafeRoute(override: () -> Unit) {
        MaterialAlertDialogBuilder(this).setTitle("No safe route found")
            .setMessage("Reported flood or unsafe areas block all available routes. Retry without avoiding reports for this navigation session?")
            .setNegativeButton("Keep avoiding", null).setPositiveButton("Retry ignoring reports") { _, _ -> override() }.show()
    }

    private fun showRoute(shelter: Shelter, distanceMeters: Double, durationSeconds: Double, geometry: List<GeoPoint>, ignoringHazards: Boolean = false) {
        showRoute(shelter.name, GeoPoint(shelter.latitude, shelter.longitude), distanceMeters, durationSeconds, geometry, ignoringHazards)
    }

    private fun showRoute(destinationTitle: String, destination: GeoPoint, distanceMeters: Double, durationSeconds: Double, geometry: List<GeoPoint>, ignoringHazards: Boolean = false) {
        navigationDestination = destination
        previewIgnoringHazards = ignoringHazards
        val minutes = (durationSeconds / 60.0).toInt()
        routeSummary.text = if (ignoringHazards) "$destinationTitle: ${distanceMeters.toInt()} m, about $minutes min. Reported hazards ignored for this session."
        else "$destinationTitle: ${distanceMeters.toInt()} m, about $minutes min"
        setStartButtonEnabled(true)
        currentRouteGeometry = geometry
        zoomFitButton.visibility = View.VISIBLE
        map?.let { activeMap ->
            // There is one active destination at a time, so retain only its route.
            activeRoute?.let(activeMap::removePolyline)
            activeRoute = activeMap.addPolyline(
                PolylineOptions()
                    .addAll(geometry.map { LatLng(it.latitude, it.longitude) })
                    .color(if (ignoringHazards) Color.parseColor("#FFB74D") else Color.parseColor("#64B5F6"))
                    .width(10f)
            )
        }
    }

    private fun setStartButtonEnabled(enabled: Boolean) {
        findViewById<MaterialButton>(R.id.startNavigationButton).apply {
            isEnabled = enabled
            if (enabled) {
                alpha = 1f
                backgroundTintList = ContextCompat.getColorStateList(this@NavigateActivity, R.color.resq_primary)
            } else {
                alpha = 0.4f
                backgroundTintList = ContextCompat.getColorStateList(this@NavigateActivity, R.color.text_muted)
            }
        }
    }

    private fun showError(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun requestNavigationPermission() {
        if (navigationDestination == null) {
            showError("Select a routed shelter before starting navigation")
            return
        }
        // Guard: if a session is already active prompt the user to stop it first
        val currentState = NavigationForegroundService.state.value
        if (currentState is NavigationState.Active || currentState is NavigationState.WaitingForFix) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Navigation in progress")
                .setMessage("You already have an active navigation session. Stop it and start a new one?")
                .setNegativeButton("Keep current", null)
                .setPositiveButton("Stop & restart") { _, _ ->
                    NavigationForegroundService.stop(this)
                    startNavigationIfReady()
                }
                .show()
            return
        }
        if (hasLocationPermission()) {
            startNavigationIfReady()
        } else requestLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS))
    }

    private fun startNavigationIfReady() {
        val target = navigationDestination ?: return
        val location = getSystemService(LocationManager::class.java)
        if (!location.isLocationEnabled) { showError("Turn on system location before starting navigation"); return }
        val origin = pinnedStart ?: currentLocation
        routeSummary.text = "🧭  Starting navigation…"
        sheltersView.text = "Acquiring route and GPS fix…"
        NavigationForegroundService.start(this, target, origin, ignoreHazards = previewIgnoringHazards)
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() {
        super.onResume()
        mapView.onResume()
        findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)?.let { nav ->
            if (nav.selectedItemId != R.id.nav_navigate) {
                nav.selectedItemId = R.id.nav_navigate
            }
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensor ->
            sensorManager.registerListener(headingListener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        requestCurrentLocation()
        TutorialManager.checkAndResumeTour(this)
        if (isActivelyNavigating()) {
            currentLocation?.let { loc ->
                map?.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(loc.latitude, loc.longitude))
                            .zoom(16.5)
                            .bearing(headingDegrees.toDouble())
                            .build()
                    ),
                    400
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)?.let { nav ->
            if (nav.selectedItemId != R.id.nav_navigate) {
                nav.selectedItemId = R.id.nav_navigate
            }
        }
        TutorialManager.checkAndResumeTour(this)
        handleTargetIntent(intent)
    }

    private fun handleTargetIntent(intent: android.content.Intent?) {
        if (intent == null) return
        if (intent.hasExtra(EXTRA_TARGET_LAT) && intent.hasExtra(EXTRA_TARGET_LON)) {
            val lat = intent.getDoubleExtra(EXTRA_TARGET_LAT, 0.0)
            val lon = intent.getDoubleExtra(EXTRA_TARGET_LON, 0.0)
            val label = intent.getStringExtra(EXTRA_TARGET_LABEL) ?: "Shared Location"
            intent.removeExtra(EXTRA_TARGET_LAT)
            intent.removeExtra(EXTRA_TARGET_LON)
            intent.removeExtra(EXTRA_TARGET_LABEL)

            val point = runCatching { GeoPoint(lat, lon).requireValid() }.getOrNull() ?: return
            val nearbyArea = getNearbyAreaDescription(point)

            val currentState = NavigationForegroundService.state.value
            val isNavigating = currentState is NavigationState.Active || currentState is NavigationState.WaitingForFix

            if (isNavigating) {
                promptForSharedCoordinateWhileNavigating(point, label, nearbyArea)
            } else {
                focusOnTargetCoordinate(point, label, nearbyArea, autoStart = false)
            }
        }
    }

    private fun promptForSharedCoordinateWhileNavigating(point: GeoPoint, label: String, nearbyArea: String) {
        val latStr = "%.5f".format(point.latitude)
        val lonStr = "%.5f".format(point.longitude)
        MaterialAlertDialogBuilder(this)
            .setTitle("New Location Received")
            .setMessage(
                "Shared Location: $label\n" +
                "Area: $nearbyArea\n" +
                "Coordinates: $latStr, $lonStr\n\n" +
                "You currently have active navigation in progress. Would you like to stop it and navigate to this new location?"
            )
            .setPositiveButton("Stop & Navigate Here") { _, _ ->
                NavigationForegroundService.stop(this)
                focusOnTargetCoordinate(point, label, nearbyArea, autoStart = true)
            }
            .setNeutralButton("View on Map") { _, _ ->
                showTargetCoordinateMarker(point, "$label ($nearbyArea)")
                map?.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(point.latitude, point.longitude))
                    .zoom(15.0)
                    .build()
            }
            .setNegativeButton("Keep Current", null)
            .show()
    }

    private fun showTargetCoordinateMarker(point: GeoPoint, title: String) {
        val activeMap = map ?: return
        sharedLocationMarker?.let(activeMap::removeMarker)
        val amberIcon = IconFactory.getInstance(this).fromBitmap(pinnedMarkerBitmap())
        sharedLocationMarker = activeMap.addMarker(
            MarkerOptions()
                .position(LatLng(point.latitude, point.longitude))
                .title(title)
                .icon(amberIcon)
        )
    }

    private fun focusOnTargetCoordinate(point: GeoPoint, label: String, nearbyArea: String, autoStart: Boolean) {
        val activeMap = map
        if (activeMap == null) {
            pendingTargetCoordinate = point
            pendingTargetLabel = "$label - $nearbyArea"
            return
        }

        showTargetCoordinateMarker(point, "$label ($nearbyArea)")

        activeMap.cameraPosition = CameraPosition.Builder()
            .target(LatLng(point.latitude, point.longitude))
            .zoom(15.0)
            .build()

        navigationDestination = point
        routeSummary.text = "📍 $label · $nearbyArea"
        setStartButtonEnabled(true)

        val start = pinnedStart ?: currentLocation
        if (start != null) {
            val destinationShelter = Shelter(
                id = "shared_${point.latitude}_${point.longitude}",
                name = label,
                latitude = point.latitude,
                longitude = point.longitude,
                address = nearbyArea,
                notes = "Coordinates shared in chat",
                source = "chat",
                verified = true,
                lastVerified = null
            )
            routeToShelter(destinationShelter) {
                if (autoStart) {
                    startNavigationIfReady()
                }
            }
        } else {
            requestCurrentLocation()
        }
    }

    private fun getNearbyAreaDescription(point: GeoPoint): String {
        var areaName: String? = null
        if (Geocoder.isPresent()) {
            try {
                val geocoder = Geocoder(this, Locale.getDefault())
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(point.latitude, point.longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    val parts = listOfNotNull(
                        addr.subLocality ?: addr.featureName,
                        addr.locality ?: addr.subAdminArea
                    ).filter { it.isNotBlank() }.distinct()
                    if (parts.isNotEmpty()) {
                        areaName = parts.joinToString(", ")
                    }
                }
            } catch (_: Exception) {}
        }

        val closest = shelters.minByOrNull { shelter: Shelter ->
            com.resqnet.app.navigation.NavigationProgressEvaluator.distanceMeters(
                point,
                GeoPoint(shelter.latitude, shelter.longitude)
            )
        }
        val closestDist = if (closest != null) {
            com.resqnet.app.navigation.NavigationProgressEvaluator.distanceMeters(
                point,
                GeoPoint(closest.latitude, closest.longitude)
            ).toInt()
        } else Int.MAX_VALUE

        return when {
            areaName != null && closest != null && closestDist < 800 ->
                "$areaName (Near ${closest.name})"
            areaName != null ->
                areaName
            closest != null && closestDist < 3000 ->
                "Near ${closest.name} (~${closestDist}m)"
            closest != null ->
                "Near ${closest.name}"
            else ->
                "${"%.4f".format(point.latitude)}, ${"%.4f".format(point.longitude)}"
        }
    }

    override fun onPause() {
        sensorManager.unregisterListener(headingListener)
        mapView.onPause()
        // Enter PiP on Android 8-11 when navigating and leaving the app via gesture/home
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S
            && isActivelyNavigating()
            && !isChangingConfigurations
            && !isFinishing
        ) {
            runCatching {
                val rect = android.graphics.Rect()
                mapView.getGlobalVisibleRect(rect)
                val builder = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(3, 4))
                if (!rect.isEmpty) builder.setSourceRectHint(rect)
                enterPictureInPictureMode(builder.build())
            }
        }
        super.onPause()
    }
    override fun onStop() {
        mapView.onStop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isInPictureInPictureMode) {
            getSystemService(LocationManager::class.java)?.removeUpdates(mapLocationListener)
        }
        super.onStop()
    }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() {
        getSystemService(LocationManager::class.java).removeUpdates(mapLocationListener)
        routingEngine.close()
        mapView.onDestroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }

    // ── Picture-in-Picture (PiP) ────────────────────────────────────────────

    private fun isActivelyNavigating(): Boolean {
        val s = NavigationForegroundService.state.value
        return s is NavigationState.Active || s is NavigationState.WaitingForFix
    }

    /**
     * Call whenever navigation state changes to arm/disarm auto-enter PiP.
     * On Android 12+ this lets the system automatically enter PiP on swipe-up/home.
     * 3:4 = Google Maps standard portrait navigation aspect ratio.
     */
    private fun updatePipParams(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val builder = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(3, 4))

                val rect = android.graphics.Rect()
                mapView.getGlobalVisibleRect(rect)
                if (!rect.isEmpty) {
                    builder.setSourceRectHint(rect)
                }

                if (enabled) {
                    val stopPendingIntent = PendingIntent.getService(
                        this,
                        2,
                        Intent(this, NavigationForegroundService::class.java).setAction(NavigationForegroundService.ACTION_STOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    val stopIcon = Icon.createWithResource(this, R.drawable.ic_clear_pins)
                    val stopAction = RemoteAction(stopIcon, "Stop", "Stop navigation", stopPendingIntent)
                    builder.setActions(listOf(stopAction))
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    builder.setAutoEnterEnabled(enabled)
                }
                setPictureInPictureParams(builder.build())
            }
        }
    }

    /**
     * Fallback for Android 8-11: onUserLeaveHint fires on Home button press
     * but NOT reliably on gesture-swipe-up. We also handle onPause below.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isActivelyNavigating() && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            runCatching {
                val rect = android.graphics.Rect()
                mapView.getGlobalVisibleRect(rect)
                val builder = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(3, 4))
                if (!rect.isEmpty) builder.setSourceRectHint(rect)
                enterPictureInPictureMode(builder.build())
            }
        }
    }


    @Suppress("DEPRECATION")
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            // Hide all full-screen chrome and non-navigation buttons
            findViewById<View>(R.id.toolbar)?.visibility = View.GONE
            findViewById<View>(R.id.appBarLayout)?.visibility = View.GONE
            findViewById<View>(R.id.bottomNav)?.visibility = View.GONE
            findViewById<View>(R.id.bottomNavDivider)?.visibility = View.GONE
            findViewById<View>(R.id.topPackCardContainer)?.visibility = View.GONE
            findViewById<View>(R.id.nearestShelterButton)?.visibility = View.GONE
            findViewById<View>(R.id.sheltersButton)?.visibility = View.GONE
            findViewById<View>(R.id.clearPinsButton)?.visibility = View.GONE
            findViewById<View>(R.id.myLocationButton)?.visibility = View.GONE
            findViewById<View>(R.id.zoomFitButton)?.visibility = View.GONE
            findViewById<View>(R.id.mapAttribution)?.visibility = View.GONE
            findViewById<View>(R.id.bottomRouteCard)?.visibility = View.GONE

            // Show Google Maps-style navigation banner at top of PiP window
            pipNavigationBanner.visibility = if (isActivelyNavigating()) View.VISIBLE else View.GONE

            // Hide MapLibre logo & attribution info in PiP
            map?.uiSettings?.isAttributionEnabled = false
            map?.uiSettings?.isLogoEnabled = false

            // Center camera on current location with 3D tilt and navigation zoom
            currentLocation?.let { loc ->
                map?.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(loc.latitude, loc.longitude))
                            .zoom(16.8)
                            .bearing(headingDegrees.toDouble())
                            .tilt(35.0)
                            .build()
                    ),
                    400
                )
            }
        } else {
            // Restore full-screen UI
            pipNavigationBanner.visibility = View.GONE

            findViewById<View>(R.id.toolbar)?.visibility = View.VISIBLE
            findViewById<View>(R.id.appBarLayout)?.visibility = View.VISIBLE
            findViewById<View>(R.id.bottomNav)?.visibility = View.VISIBLE
            findViewById<View>(R.id.bottomNavDivider)?.visibility = View.VISIBLE
            findViewById<View>(R.id.topPackCardContainer)?.visibility = View.VISIBLE
            findViewById<View>(R.id.nearestShelterButton)?.visibility = View.VISIBLE
            findViewById<View>(R.id.clearPinsButton)?.visibility = View.VISIBLE
            findViewById<View>(R.id.myLocationButton)?.visibility = View.VISIBLE
            findViewById<View>(R.id.mapAttribution)?.visibility = View.VISIBLE
            findViewById<View>(R.id.bottomRouteCard)?.visibility = View.VISIBLE

            findViewById<View>(R.id.sheltersButton)?.visibility = if (shelters.isEmpty()) View.GONE else View.VISIBLE
            findViewById<View>(R.id.zoomFitButton)?.visibility = if (currentRouteGeometry == null) View.GONE else View.VISIBLE
            findViewById<View>(R.id.mapManagementActions)?.visibility = if (readyPack != null) View.GONE else View.VISIBLE

            map?.uiSettings?.isAttributionEnabled = true
            map?.uiSettings?.isLogoEnabled = true
        }
    }

    // ── TTS ─────────────────────────────────────────────────────────────────

    private fun initTts() {
        val hasGoogleTts = runCatching {
            packageManager.getPackageInfo("com.google.android.tts", 0) != null
        }.getOrDefault(false)
        val preferredEngine = if (hasGoogleTts) "com.google.android.tts" else null

        tts = TextToSpeech(applicationContext, { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { engine ->
                    val locale = Locale.getDefault()
                    val result = engine.setLanguage(locale)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        engine.setLanguage(Locale.US)
                    }
                    runCatching {
                        val offlineVoice = engine.voices?.firstOrNull { voice ->
                            voice.locale.language == Locale.getDefault().language &&
                                !voice.isNetworkConnectionRequired &&
                                !voice.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                        }
                        if (offlineVoice != null) {
                            engine.voice = offlineVoice
                        }
                    }
                    engine.setSpeechRate(0.95f)
                    engine.setPitch(1.0f)
                }
            } else {
                tts = null
            }
        }, preferredEngine)
    }

    private fun cleanForSpeech(raw: String): String {
        return raw
            .replace(Regex("[\\p{So}\\p{Cn}\\p{Cs}\\p{Sc}\\p{Sk}⬅️➡️⬆️⬇️↗️↘️↙️↖️🔄🏁🧭⏹]"), "")
            .replace(Regex("(?i)\\bapprox\\.?\\b"), "approximately")
            .replace(Regex("(?i)\\b(\\d+)\\s*m\\b"), "$1 meters")
            .replace(Regex("(?i)\\b(\\d+)\\s*km\\b"), "$1 kilometers")
            .replace(Regex("(?i)\\b(\\d+)\\s*min\\b"), "$1 minutes")
            .replace(Regex("(?i)\\brd\\.?\\b"), "Road")
            .replace(Regex("(?i)\\bst\\.?\\b"), "Street")
            .replace(Regex("(?i)\\bave\\.?\\b"), "Avenue")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** Speak a navigation instruction; no-op when TTS is unavailable. */
    private fun speak(text: String) {
        val clean = cleanForSpeech(text)
        if (clean.isBlank()) return
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "nav_tts")
    }

    private fun getManeuverArrow(instruction: String): String {
        val lower = instruction.lowercase()
        return when {
            lower.contains("arrived") || lower.contains("destination") || lower.contains("reached") -> "🏁"
            lower.contains("u-turn") || lower.contains("uturn") || lower.contains("roundabout") || lower.contains("rotary") -> "🔄"
            lower.contains("sharp left") -> "⮡"
            lower.contains("slight left") || lower.contains("bear left") || lower.contains("keep left") -> "↖️"
            lower.contains("turn left") || lower.contains("left") -> "⬅️"
            lower.contains("sharp right") -> "⮠"
            lower.contains("slight right") || lower.contains("bear right") || lower.contains("keep right") -> "↗️"
            lower.contains("turn right") || lower.contains("right") -> "➡️"
            lower.contains("ramp") || lower.contains("merge") || lower.contains("fork") -> "🔀"
            lower.contains("straight") || lower.contains("continue") || lower.contains("walk") || lower.contains("head") -> "⬆️"
            else -> "⬆️"
        }
    }

    private fun getManeuverArrowDrawable(instruction: String): Int {
        val lower = instruction.lowercase()
        return when {
            lower.contains("u-turn") || lower.contains("uturn") || lower.contains("roundabout") || lower.contains("rotary") -> R.drawable.ic_notif_arrow_uturn
            lower.contains("slight right") || lower.contains("bear right") || lower.contains("keep right") -> R.drawable.ic_notif_arrow_slight_right
            lower.contains("sharp right") -> R.drawable.ic_notif_arrow_right
            lower.contains("turn right") || lower.contains("right") -> R.drawable.ic_notif_arrow_right
            lower.contains("slight left") || lower.contains("bear left") || lower.contains("keep left") -> R.drawable.ic_notif_arrow_slight_left
            lower.contains("sharp left") -> R.drawable.ic_notif_arrow_left
            lower.contains("turn left") || lower.contains("left") -> R.drawable.ic_notif_arrow_left
            lower.contains("arrived") || lower.contains("destination") || lower.contains("reached") -> R.drawable.ic_notif_pin
            else -> R.drawable.ic_notif_arrow_straight
        }
    }

    // ── Pinned marker helpers ────────────────────────────────────────────────

    /**
     * Amber filled circle used for long-press start / destination pins so they are
     * visually distinct from the default grey shelter markers.
     */
    private fun pinnedMarkerBitmap(): Bitmap {
        val size = (24 * resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.parseColor("#F2B544")
        paint.style = Paint.Style.FILL
        val radius = size / 2f - 3
        canvas.drawCircle(size / 2f, size / 2f, radius, paint)
        paint.color = Color.parseColor("#172B3A")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        canvas.drawCircle(size / 2f, size / 2f, radius, paint)
        return bitmap
    }

    /** Remove user-placed custom markers without stopping active navigation. */
    private fun clearPins() {
        val activeMap = map
        if (activeMap != null) {
            pinnedMarkers.forEach { activeMap.removeMarker(it) }
            sharedLocationMarker?.let { activeMap.removeMarker(it) }
        }
        pinnedMarkers.clear()
        sharedLocationMarker = null

        // If not actively navigating, also reset pre-navigation route planning
        if (!isActivelyNavigating()) {
            pinnedStart = currentLocation
            navigationDestination = null
            if (activeMap != null) {
                activeRoute?.let(activeMap::removePolyline)
                activeRoute = null
            }
            currentRouteGeometry = null
            zoomFitButton.visibility = View.GONE
            setStartButtonEnabled(false)
            routeSummary.text = "Pins cleared. Long-press to set a new start or destination."
        }
    }

    // ── Zoom to fit route ────────────────────────────────────────────────────

    /** Animate the camera to show the entire current route in the viewport. */
    private fun zoomToFitRoute() {
        val activeMap = map ?: return
        val geometry = currentRouteGeometry ?: return
        val points = mutableListOf<LatLng>()
        pinnedStart?.let { points.add(LatLng(it.latitude, it.longitude)) }
        points.addAll(geometry.map { LatLng(it.latitude, it.longitude) })
        navigationDestination?.let { points.add(LatLng(it.latitude, it.longitude)) }
        if (points.isEmpty()) return
        val boundsBuilder = LatLngBounds.Builder()
        points.forEach { boundsBuilder.include(it) }
        val bounds = boundsBuilder.build()
        val padding = (80 * resources.displayMetrics.density).toInt()
        activeMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
    }

    // ── Shelter Bottom Sheet ─────────────────────────────────────────────────

    /** Show the shelter list in a Material bottom sheet backed by a RecyclerView. */
    private fun showShelterBottomSheet() {
        if (shelters.isEmpty()) return
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_shelters, null)
        dialog.setContentView(view)
        view.findViewById<TextView>(R.id.tvShelterCount).text = "${shelters.size} Nearby Shelters"
        view.findViewById<ImageButton>(R.id.btnCloseShelterSheet).setOnClickListener { dialog.dismiss() }
        val rv = view.findViewById<RecyclerView>(R.id.shelterRecyclerView)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = ShelterAdapter(shelters) { shelter ->
            dialog.dismiss()
            confirmShelter(shelter)
        }
        dialog.show()
    }

    /** RecyclerView adapter for the shelter list bottom sheet. */
    private inner class ShelterAdapter(
        private val items: List<Shelter>,
        private val onClick: (Shelter) -> Unit
    ) : RecyclerView.Adapter<ShelterAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.tvShelterName)
            val address: TextView = view.findViewById(R.id.tvShelterAddress)
            val badge: ImageView = view.findViewById(R.id.ivVerifiedBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = layoutInflater.inflate(R.layout.item_shelter, parent, false)
            return ViewHolder(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val s = items[position]
            holder.name.text = s.name
            holder.address.text = s.address ?: s.notes ?: "No address info"
            holder.badge.setImageResource(R.drawable.ic_shield_status)
            ImageViewCompat.setImageTintList(
                holder.badge,
                ColorStateList.valueOf(
                    if (s.verified) getColor(R.color.color_safe) else getColor(R.color.color_pending)
                )
            )
            holder.itemView.setOnClickListener { onClick(s) }
        }
    }

    companion object {
        const val EXTRA_TARGET_LAT = "extra_target_lat"
        const val EXTRA_TARGET_LON = "extra_target_lon"
        const val EXTRA_TARGET_LABEL = "extra_target_label"
        // The complete list remains available from the destination picker. Keeping
        // the initial map sparse lets users see roads and landmarks at city zoom.
        const val MAX_VISIBLE_SHELTER_MARKERS = 60
    }
}
