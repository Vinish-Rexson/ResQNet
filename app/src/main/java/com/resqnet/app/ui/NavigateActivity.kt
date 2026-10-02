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
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.EditText
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.resqnet.app.ResQNetApplication
import com.resqnet.app.R
import com.resqnet.app.navigation.GeoPoint
import com.resqnet.app.navigation.AvoidanceArea
import com.resqnet.app.navigation.HazardReport
import com.resqnet.app.navigation.HazardType
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
        findViewById<MaterialButton>(R.id.reportHazardButton).setOnClickListener { beginHazardPlacement() }
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
        pendingTargetCoordinate?.let { point ->
            val label = pendingTargetLabel ?: "Shared Location"
            pendingTargetCoordinate = null
            pendingTargetLabel = null
            focusOnTargetCoordinate(point, label)
        }
    }

    private fun beginHazardPlacement() {
        if (map == null) {
            showError("Wait for the map to load before reporting an area")
            return
        }
        placingHazard = !placingHazard
        findViewById<MaterialButton>(R.id.reportHazardButton).text = if (placingHazard) "Cancel" else "Report"
        routeSummary.text = if (placingHazard) "Tap the affected flood or unsafe area on the map." else "Hazard reporting cancelled."
    }

    private fun showHazardEditor(center: GeoPoint, existing: HazardReport? = null) {
        val dialog = BottomSheetDialog(this)
        val padding = (20 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply {
            text = if (existing == null) "Report area" else "Edit report"
            textSize = 20f
            setTextColor(Color.BLACK)
        })
        content.addView(TextView(this).apply {
            text = "This report stays on this device and is used to avoid the area while routing."
            setPadding(0, padding / 3, 0, padding / 2)
        })
        val typeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val flood = RadioButton(this).apply { id = View.generateViewId(); text = "Flood" }
        val unsafe = RadioButton(this).apply { id = View.generateViewId(); text = "Unsafe area" }
        typeGroup.addView(flood); typeGroup.addView(unsafe)
        typeGroup.check(if (existing?.type == HazardType.UNSAFE_AREA) unsafe.id else flood.id)
        content.addView(typeGroup)
        val radiusLabel = TextView(this).apply { setPadding(0, padding / 2, 0, 0) }
        val radius = SeekBar(this).apply {
            max = (AvoidanceArea.MAX_RADIUS_METERS - AvoidanceArea.MIN_RADIUS_METERS) / 25
            progress = ((existing?.radiusMeters ?: HazardType.FLOOD.defaultRadiusMeters) - AvoidanceArea.MIN_RADIUS_METERS) / 25
        }
        fun selectedType() = if (typeGroup.checkedRadioButtonId == unsafe.id) HazardType.UNSAFE_AREA else HazardType.FLOOD
        fun updateRadiusLabel() { radiusLabel.text = "Avoidance radius: ${AvoidanceArea.MIN_RADIUS_METERS + radius.progress * 25} m" }
        updateRadiusLabel()
        radius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updateRadiusLabel()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        typeGroup.setOnCheckedChangeListener { _, _ ->
            if (existing == null) {
                radius.progress = (selectedType().defaultRadiusMeters - AvoidanceArea.MIN_RADIUS_METERS) / 25
            }
        }
        content.addView(radiusLabel); content.addView(radius)
        content.addView(TextView(this).apply { text = "Expires after"; setPadding(0, padding / 2, 0, 0) })
        val expiry = Spinner(this)
        val expiryHours = listOf(1, 6, 12, 24)
        expiry.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, expiryHours.map { "$it hour${if (it == 1) "" else "s"}" })
        val defaultHours = ((existing?.expiresAt?.minus(System.currentTimeMillis()) ?: selectedType().defaultDurationMillis) / (60 * 60 * 1000L)).toInt()
        expiry.setSelection(expiryHours.indexOf(defaultHours).takeIf { it >= 0 } ?: if (existing?.type == HazardType.UNSAFE_AREA) 3 else 1)
        content.addView(expiry)
        val note = EditText(this).apply {
            hint = "Optional note"
            setText(existing?.note.orEmpty())
            setSingleLine(false)
            maxLines = 3
            setPadding(0, padding / 2, 0, padding / 2)
        }
        content.addView(note)
        val actions = LinearLayout(this).apply { gravity = Gravity.END; orientation = LinearLayout.HORIZONTAL }
        val cancel = MaterialButton(this).apply { text = "Cancel" }
        val save = MaterialButton(this).apply { text = if (existing == null) "Save report" else "Save changes" }
        actions.addView(cancel); actions.addView(save); content.addView(actions)
        cancel.setOnClickListener { dialog.dismiss() }
        save.setOnClickListener {
            val type = selectedType()
            val radiusMeters = AvoidanceArea.MIN_RADIUS_METERS + radius.progress * 25
            val expiresAt = System.currentTimeMillis() + expiryHours[expiry.selectedItemPosition] * 60 * 60 * 1000L
            lifecycleScope.launch {
                runCatching {
                    if (existing == null) (application as ResQNetApplication).hazards.create(type, center, radiusMeters, expiresAt, note.text?.toString())
                    else (application as ResQNetApplication).hazards.update(existing, type, radiusMeters, expiresAt, note.text?.toString())
                }.onSuccess {
                    dialog.dismiss()
                    routeSummary.text = "${type.label} report saved. New routes will avoid this area."
                }.onFailure { showError(it.message ?: "Could not save report") }
            }
        }
        dialog.setContentView(content)
        dialog.show()
    }

    private fun showHazardDetails(report: HazardReport) {
        val remainingHours = ((report.expiresAt - System.currentTimeMillis()).coerceAtLeast(0) / (60 * 60 * 1000L)).coerceAtLeast(1)
        MaterialAlertDialogBuilder(this)
            .setTitle(report.type.label)
            .setMessage("Avoidance radius: ${report.radiusMeters} m\nExpires in about $remainingHours hour(s)" + report.note?.let { "\n\n$it" }.orEmpty())
            .setNegativeButton("Delete") { _, _ -> lifecycleScope.launch { (application as ResQNetApplication).hazards.delete(report.id) } }
            .setNeutralButton("Edit") { _, _ -> showHazardEditor(report.center, report) }
            .setPositiveButton("Mark resolved") { _, _ -> lifecycleScope.launch { (application as ResQNetApplication).hazards.resolve(report.id) } }
            .show()
    }

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val radius = 6_371_000.0
        val latDelta = Math.toRadians(b.latitude - a.latitude)
        val lonDelta = Math.toRadians(b.longitude - a.longitude)
        val h = kotlin.math.sin(latDelta / 2) * kotlin.math.sin(latDelta / 2) +
            kotlin.math.cos(Math.toRadians(a.latitude)) * kotlin.math.cos(Math.toRadians(b.latitude)) *
            kotlin.math.sin(lonDelta / 2) * kotlin.math.sin(lonDelta / 2)
        return radius * 2 * kotlin.math.atan2(kotlin.math.sqrt(h), kotlin.math.sqrt(1 - h))
    }

    private fun renderHazards() {
        val activeMap = map ?: return
        hazardPolygons.forEach(activeMap::removePolygon)
        hazardPolygons.clear()
        hazardReports.forEach { report ->
            val color = if (report.type == HazardType.FLOOD) Color.rgb(33, 150, 243) else Color.rgb(239, 108, 0)
            val ring = report.toAvoidanceArea().toValhallaPolygon().map { coordinates -> LatLng(coordinates[1], coordinates[0]) }
            hazardPolygons += activeMap.addPolygon(
                PolygonOptions().addAll(ring).fillColor(Color.argb(72, Color.red(color), Color.green(color), Color.blue(color))).strokeColor(color)
            )
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
            val result = runCatching {
                ShelterRouteSelector(routingEngine).chooseBest(start, shelters, if (ignoreHazards) emptyList() else currentAvoidanceAreas())
            }
            nearestRouteInProgress = false
            findViewById<MaterialButton>(R.id.nearestShelterButton).apply {
                isEnabled = true
                text = "Nearest"
            }
            result.onSuccess { route ->
                if (route == null) noSafeRoute { selectNearestShelter(ignoreHazards = true) }
                else showRoute(route.shelter, route.route.distanceMeters, route.route.durationSeconds, route.route.geometry, ignoreHazards)
            }.onFailure { error ->
                if (!ignoreHazards && error is com.resqnet.app.navigation.RoutingException.NoRoute) noSafeRoute { selectNearestShelter(ignoreHazards = true) }
                else showError(error.message ?: "Could not find a route to a shelter")
            }
        }
    }

    private fun routeToShelter(shelter: Shelter, ignoreHazards: Boolean = false) {
        val start = pinnedStart
        if (start == null) {
            routeSummary.text = "Selected destination: ${shelter.name}. Long-press the map to set a start pin for a preview."
            return
        }
        lifecycleScope.launch {
            val result = runCatching {
                ShelterRouteSelector(routingEngine).chooseBest(start, listOf(shelter), if (ignoreHazards) emptyList() else currentAvoidanceAreas())
            }
            result.onSuccess { route ->
                if (route == null) noSafeRoute { routeToShelter(shelter, ignoreHazards = true) }
                else showRoute(route.shelter, route.route.distanceMeters, route.route.durationSeconds, route.route.geometry, ignoreHazards)
            }.onFailure { error ->
                if (!ignoreHazards && error is com.resqnet.app.navigation.RoutingException.NoRoute) noSafeRoute { routeToShelter(shelter, ignoreHazards = true) }
                else showError(error.message ?: "Could not find a route to ${shelter.name}")
            }
        }
    }

    private suspend fun currentAvoidanceAreas(): List<AvoidanceArea> =
        (application as ResQNetApplication).hazards.activeNow().map(HazardReport::toAvoidanceArea)

    private fun noSafeRoute(override: () -> Unit) {
        routeSummary.text = "No route avoids the reported areas."
        MaterialAlertDialogBuilder(this)
            .setTitle("No safe route found")
            .setMessage("Reported flood or unsafe areas block all available routes. You can deliberately retry without avoiding reports for this navigation session.")
            .setNegativeButton("Keep avoiding", null)
            .setPositiveButton("Retry ignoring reports") { _, _ -> override() }
            .show()
    }

    private fun showRoute(shelter: Shelter, distanceMeters: Double, durationSeconds: Double, geometry: List<GeoPoint>, ignoringHazards: Boolean = false) {
        navigationDestination = GeoPoint(shelter.latitude, shelter.longitude)
        previewIgnoringHazards = ignoringHazards
        val minutes = (durationSeconds / 60.0).toInt()
        routeSummary.text = if (ignoringHazards) "${shelter.name}: ${distanceMeters.toInt()} m, about $minutes min. Reported hazards ignored for this session."
        else "${shelter.name}: ${distanceMeters.toInt()} m, about $minutes min"
        setStartButtonEnabled(true)
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
        NavigationForegroundService.start(this, target, ignoreHazards = previewIgnoringHazards)
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
