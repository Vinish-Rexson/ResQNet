package com.resqnet.app.ui

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
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
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_navigate)
        setSupportActionBar(findViewById(R.id.toolbar))
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
    }

    private fun renderPackState(state: OfflinePackState) {
        status.text = when (state) {
            OfflinePackState.Absent -> "No offline region installed. Import a verified pack or download one."
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
        sheltersView.text = if (shelters.isEmpty()) "No curated shelters in this pack."
        else shelters.joinToString("\n") { shelter ->
            val badge = if (shelter.verified) "" else " — Unverified"
            "${shelter.name}$badge"
        }
        sheltersView.setOnClickListener { chooseShelter() }
        val style = java.io.File(pack.directory, "style.json")
        if (!style.isFile) {
            routeSummary.text = "Pack is missing its offline style. Map rendering is unavailable."
            return
        }
        val styleText = style.readText()
        if (styleText.contains("http://") || styleText.contains("https://")) {
            routeSummary.text = "Pack style references the network and was rejected."
            return
        }
        mapView.getMapAsync { map ->
            this.map = map
            map.setStyle("file://${style.absolutePath}") {
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(19.2, 72.9))
                    .zoom(10.0)
                    .build()
                shelters.forEach { shelter ->
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
            }
        }
        mapView.visibility = View.VISIBLE
    }

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
        val start = pinnedStart
        if (start == null) {
            routeSummary.text = "Long-press the map to set a start pin before finding the nearest shelter."
            return
        }
        lifecycleScope.launch {
            val result = runCatching { ShelterRouteSelector(routingEngine).chooseBest(start, shelters) }.getOrNull()
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
        map?.addPolyline(PolylineOptions().addAll(geometry.map { LatLng(it.latitude, it.longitude) }))
    }

    private fun showError(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun requestNavigationPermission() {
        if (navigationDestination == null) {
            showError("Select a routed shelter before starting navigation")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
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
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() { routingEngine.close(); mapView.onDestroy(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }
}
