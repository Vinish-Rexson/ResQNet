package com.resqnet.app.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import com.resqnet.app.navigation.spike.LatLon
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.valhalla.api.models.CostingModel
import com.valhalla.api.models.RouteRequest
import com.valhalla.api.models.RoutingWaypoint
import com.valhalla.config.models.ValhallaConfig
import com.valhalla.valhalla.Valhalla
import com.valhalla.valhalla.ValhallaResponse
import com.valhalla.valhalla.config.ValhallaConfigManager
import com.valhalla.valhalla.files.ValhallaFile
import java.io.File
import java.util.Locale

/**
 * Physical-device-only probe for the public valhalla-mobile API. It deliberately
 * uses the already installed tiles.tar and does not exercise ValhallaBridge.
 */
class ValhallaPublicApiSmokeActivity : Activity() {
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply {
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.rgb(18, 32, 42))
            setTextColor(Color.WHITE)
            textSize = 18f
            text = "Running public Valhalla API smoke test…"
        }
        setContentView(output)
        Thread(::runSmoke, "valhalla-public-api-smoke").start()
    }

    private fun runSmoke() {
        val result = runCatching {
            val dataDir = File(filesDir, "valhalla_tiles")
            val configFile = File(dataDir, "valhalla.json")
            val tilesTar = File(dataDir, "tiles.tar")
            check(configFile.isFile) { "Missing config: ${configFile.absolutePath}" }
            check(tilesTar.isFile) { "Missing tile archive: ${tilesTar.absolutePath}" }

            val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            val config = moshi.adapter(ValhallaConfig::class.java)
                .fromJson(configFile.readText())
                ?: error("Could not parse ${configFile.name}")
            val manager = ValhallaConfigManager(
                this,
                ValhallaFile(this, "valhalla-public-api-smoke.json", filesDir),
                moshi
            )
            val request = RouteRequest(
                locations = listOf(
                    RoutingWaypoint(19.3828, 72.8319),
                    RoutingWaypoint(19.3750, 72.8240)
                ),
                costing = CostingModel.pedestrian,
                id = "public-api-tar-smoke"
            )

            val started = System.nanoTime()
            val response = Valhalla(this, config, manager, moshi).use { it.route(request) }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
            val route = (response as? ValhallaResponse.Json)?.jsonResponse
                ?: error("Expected Valhalla JSON response, got ${response::class.java.name}")
            val summary = route.trip.summary
            "PASS\nPublic Valhalla API routed via tiles.tar\n" +
                "distance=${String.format(Locale.US, "%.3f", summary.length)} km\n" +
                "duration=${String.format(Locale.US, "%.1f", summary.time)} s\n" +
                "elapsed=${String.format(Locale.US, "%.1f", elapsedMs)} ms"
        }.getOrElse { throwable ->
            "FAIL\n${throwable.javaClass.simpleName}: ${throwable.message}\n" +
                Log.getStackTraceString(throwable)
        }
        Log.i(TAG, result)
        runOnUiThread { output.text = result }
    }

    private companion object {
        const val TAG = "ValhallaPublicSmoke"
    }
}
