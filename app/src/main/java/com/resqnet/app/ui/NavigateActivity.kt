package com.resqnet.app.ui

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.resqnet.app.R
import com.resqnet.app.navigation.GeoPoint
import com.resqnet.app.navigation.InstalledRegionPack
import com.resqnet.app.navigation.NavigationForegroundService
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
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.IconFactory

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
    private var readyPack: InstalledRegionPack? = null
    private var pinnedStart: GeoPoint? = null
    private var shelters: List<Shelter> = emptyList()
    private val routingEngine by lazy { ValhallaRoutingEngine(applicationContext) }
    private var map: MapLibreMap? = null
    private var navigationDestination: GeoPoint? = null
    private var currentLocationMarker: Marker? = null
    private var activeRoute: Polyline? = null
    private var currentLocation: GeoPoint? = null
    private var bundledPackInstallRequested = false
    private var nearestRouteInProgress = false
    private var findNearestAfterLocation = false
    private var headingDegrees = 0f
    private var sharedLocationMarker: Marker? = null
    private var pendingTargetCoordinate: GeoPoint? = null
    private var pendingTargetLabel: String? = null
    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }
    private val headingListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val matrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(matrix, event.values)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(matrix, orientation)
            val newHeading = ((Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f)
            if (kotlin.math.abs(newHeading - headingDegrees) < 2f) return
            headingDegrees = newHeading
            updateCurrentLocationHeading()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val mapLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            setCurrentLocation(location)
            getSystemService(LocationManager::class.java).removeUpdates(this)
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
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true || granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true) refreshCurrentLocation()
        else showError("Location permission is needed to place your position on the map")
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
        findViewById<MaterialButton>(R.id.startNavigationButton).setOnClickListener { requestNavigationPermission() }
        findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.myLocationButton)
            .setOnClickListener { requestCurrentLocation() }

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
                    if (navigation is NavigationState.Active) routeSummary.text = "${navigation.nextInstruction} · ${navigation.distanceMeters.toInt()} m remaining"
                    if (navigation is NavigationState.Arrived) routeSummary.text = "Arrived at destination"
                    if (navigation is NavigationState.Failed) showError(navigation.message)
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

    private fun showShelters(pack: InstalledRegionPack) {
        shelters = ShelterRepository().load(pack)
        sheltersView.text = if (shelters.isEmpty()) "No shelter candidates in this map pack."
        else "${shelters.size} temporary shelter candidates · Tap to browse"
        sheltersView.setOnClickListener { chooseShelter() }
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
        findViewById<TextView>(R.id.mapAttribution).text = "Online map · © OpenStreetMap contributors"
        mapView.getMapAsync { map ->
            this.map = map
            map.setStyle(Style.Builder().fromJson(ONLINE_OSM_STYLE)) {
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(19.0760, 72.8777))
                    .zoom(11.5)
                    .build()
                currentLocation?.let { point -> showCurrentLocationMarker(point, centerMap = false) }
                pendingTargetCoordinate?.let { point ->
                    val label = pendingTargetLabel ?: "Shared Location"
                    pendingTargetCoordinate = null
                    pendingTargetLabel = null
                    focusOnTargetCoordinate(point, label)
                }
            }
        }
        mapView.visibility = View.VISIBLE
    }

    private fun configureMap(map: MapLibreMap) {
        map.cameraPosition = CameraPosition.Builder()
            .target(LatLng(19.2, 72.9))
            .zoom(10.0)
            .build()
        shelters.take(MAX_VISIBLE_SHELTER_MARKERS).forEach { shelter ->
            map.addMarker(MarkerOptions().position(LatLng(shelter.latitude, shelter.longitude)).title(shelter.name))
        }
        map.addOnMapLongClickListener { point ->
            val pin = GeoPoint(point.latitude, point.longitude)
            if (pinnedStart == null) {
                pinnedStart = pin
                routeSummary.text = "Start pin set. Choose a shelter or long-press again to set a destination."
            } else {
                routeSummary.text = "Destination pin set at ${"%.5f".format(point.latitude)}, ${"%.5f".format(point.longitude)}. Route preview will use this pinned start."
            }
            map.addMarker(MarkerOptions().position(point).title(if (pinnedStart == pin) "Start" else "Destination"))
            true
        }
        currentLocation?.let { point -> showCurrentLocationMarker(point, centerMap = false) }
        pendingTargetCoordinate?.let { point ->
            val label = pendingTargetLabel ?: "Shared Location"
            pendingTargetCoordinate = null
            pendingTargetLabel = null
            focusOnTargetCoordinate(point, label)
        }
    }

    private fun requestCurrentLocation() {
        if (hasLocationPermission()) refreshCurrentLocation()
        else requestMapLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    @Suppress("MissingPermission")
    private fun refreshCurrentLocation() {
        val manager = getSystemService(LocationManager::class.java)
        if (!manager.isLocationEnabled) {
            showError("Turn on system location to use your current position")
            return
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        val lastFix = providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.minByOrNull { it.accuracy }
        if (lastFix != null) setCurrentLocation(lastFix)
        val provider = providers.firstOrNull { manager.isProviderEnabled(it) }
        if (provider == null) {
            if (lastFix == null) showError("Waiting for a location provider")
            return
        }
        runCatching { manager.requestLocationUpdates(provider, 1_000L, 0f, mapLocationListener) }
            .onFailure { if (lastFix == null) showError("Could not get your current location") }
    }

    private fun setCurrentLocation(location: Location) {
        val point = runCatching { GeoPoint(location.latitude, location.longitude).requireValid() }.getOrNull() ?: return
        currentLocation = point
        pinnedStart = point
        showCurrentLocationMarker(point, centerMap = true)
        routeSummary.text = "Current location set as your route start. Choose a shelter or long-press to set a destination."
        if (findNearestAfterLocation) {
            findNearestAfterLocation = false
            selectNearestShelter()
        }
    }

    private fun showCurrentLocationMarker(point: GeoPoint, centerMap: Boolean) {
        val activeMap = map ?: return
        currentLocationMarker?.let(activeMap::removeMarker)
        currentLocationMarker = activeMap.addMarker(
            MarkerOptions()
                .position(LatLng(point.latitude, point.longitude))
                .title("Your current location")
                .icon(IconFactory.getInstance(this).fromBitmap(currentLocationMarkerBitmap()))
        )
        if (centerMap) {
            activeMap.cameraPosition = CameraPosition.Builder()
                .target(LatLng(point.latitude, point.longitude))
                .zoom(15.0)
                .build()
        }
    }

    /** MapLibre annotation icons require a bitmap; Android vector drawables are not accepted directly. */
    private fun currentLocationMarkerBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        val drawable = requireNotNull(ContextCompat.getDrawable(this, R.drawable.ic_current_location_marker))
        drawable.setBounds(0, 0, bitmap.width, bitmap.height)
        Canvas(bitmap).apply {
            rotate(headingDegrees, bitmap.width / 2f, bitmap.height / 2f)
            drawable.draw(this)
        }
        return bitmap
    }

    private fun updateCurrentLocationHeading() {
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

    private fun selectNearestShelter() {
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
            val result = runCatching { ShelterRouteSelector(routingEngine).chooseBest(start, shelters) }.getOrNull()
            nearestRouteInProgress = false
            findViewById<MaterialButton>(R.id.nearestShelterButton).apply {
                isEnabled = true
                text = "Nearest"
            }
            if (result == null) routeSummary.text = "No walking route to the nearest shelter candidates was found."
            else showRoute(result.shelter, result.route.distanceMeters, result.route.durationSeconds, result.route.geometry)
        }
    }

    private fun routeToShelter(shelter: Shelter) {
        val start = pinnedStart
        if (start == null) {
            routeSummary.text = "Selected destination: ${shelter.name}. Long-press the map to set a start pin for a preview."
            return
        }
        lifecycleScope.launch {
            val result = runCatching { ShelterRouteSelector(routingEngine).chooseBest(start, listOf(shelter)) }.getOrNull()
            if (result == null) routeSummary.text = "No walking route to ${shelter.name} was found."
            else showRoute(result.shelter, result.route.distanceMeters, result.route.durationSeconds, result.route.geometry)
        }
    }

    private fun showRoute(shelter: Shelter, distanceMeters: Double, durationSeconds: Double, geometry: List<GeoPoint>) {
        navigationDestination = GeoPoint(shelter.latitude, shelter.longitude)
        val minutes = (durationSeconds / 60.0).toInt()
        routeSummary.text = "${shelter.name}: ${distanceMeters.toInt()} m, about $minutes min"
        setStartButtonEnabled(true)
        map?.let { activeMap ->
            // There is one active destination at a time, so retain only its route.
            activeRoute?.let(activeMap::removePolyline)
            activeRoute = activeMap.addPolyline(
                PolylineOptions()
                    .addAll(geometry.map { LatLng(it.latitude, it.longitude) })
                    .color(Color.parseColor("#64B5F6"))
                    .width(10f)
            )
        }
    }

    private fun setStartButtonEnabled(enabled: Boolean) {
        findViewById<MaterialButton>(R.id.startNavigationButton).apply {
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.55f
        }
    }

    private fun showError(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun requestNavigationPermission() {
        if (navigationDestination == null) {
            showError("Select a routed shelter before starting navigation")
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
        NavigationForegroundService.start(this, target)
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() {
        super.onResume()
        mapView.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensor ->
            sensorManager.registerListener(headingListener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        TutorialManager.checkAndResumeTour(this)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
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
            focusOnTargetCoordinate(GeoPoint(lat, lon), label)
        }
    }

    private fun focusOnTargetCoordinate(point: GeoPoint, label: String) {
        val activeMap = map
        if (activeMap == null) {
            pendingTargetCoordinate = point
            pendingTargetLabel = label
            return
        }

        sharedLocationMarker?.let(activeMap::removeMarker)
        sharedLocationMarker = activeMap.addMarker(
            MarkerOptions()
                .position(LatLng(point.latitude, point.longitude))
                .title(label)
        )

        activeMap.cameraPosition = CameraPosition.Builder()
            .target(LatLng(point.latitude, point.longitude))
            .zoom(15.0)
            .build()

        navigationDestination = point
        routeSummary.text = "📍 $label\nLat: ${"%.5f".format(point.latitude)}, Lon: ${"%.5f".format(point.longitude)}"
        setStartButtonEnabled(true)

        val start = pinnedStart ?: currentLocation
        if (start != null) {
            val destinationShelter = Shelter(
                id = "shared_${point.latitude}_${point.longitude}",
                name = label,
                latitude = point.latitude,
                longitude = point.longitude,
                address = "${"%.5f".format(point.latitude)}, ${"%.5f".format(point.longitude)}",
                notes = "Coordinates shared in chat",
                source = "chat",
                verified = true,
                lastVerified = null
            )
            routeToShelter(destinationShelter)
        } else {
            requestCurrentLocation()
        }
    }

    override fun onPause() {
        sensorManager.unregisterListener(headingListener)
        mapView.onPause()
        super.onPause()
    }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() { getSystemService(LocationManager::class.java).removeUpdates(mapLocationListener); routingEngine.close(); mapView.onDestroy(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }

    companion object {
        const val EXTRA_TARGET_LAT = "extra_target_lat"
        const val EXTRA_TARGET_LON = "extra_target_lon"
        const val EXTRA_TARGET_LABEL = "extra_target_label"
        // The complete list remains available from the destination picker. Keeping
        // the initial map sparse lets users see roads and landmarks at city zoom.
        const val MAX_VISIBLE_SHELTER_MARKERS = 60
    }
}
