package com.resqnet.app.navigation.spike

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Valhalla on-device routing engine implementation via valhalla-mobile (v0.6.1).
 * Communicates directly with the native C++ library via ValhallaBridge.
 */
class ValhallaRoutingEngine(private val context: Context) : RoutingEngine {
    override val name: String = "Valhalla (v3.6.3 via valhalla-mobile)"

    private val bridge = ValhallaBridge()

    @Synchronized
    override fun isInitialized(): Boolean = bridge.isInitialized

    override suspend fun init(dataDir: File): Unit = withContext(Dispatchers.IO) {
        if (bridge.isInitialized) {
            close()
        }

        require(dataDir.exists()) {
            "Valhalla data directory does not exist: ${dataDir.absolutePath}"
        }

        val configFile = File(dataDir, "valhalla.json").takeIf { it.exists() }
            ?: createDefaultConfig(dataDir)

        bridge.init(configFile.absolutePath)
    }

    override suspend fun route(
        from: LatLon,
        to: LatLon,
        profile: RoutingProfile,
        avoidPoints: List<LatLon>
    ): RouteResult = withContext(Dispatchers.IO) {
        require(bridge.isInitialized) {
            "Valhalla engine is not initialized. Call init(dataDir) first."
        }

        val requestObj = JSONObject().apply {
            put("locations", JSONArray().apply {
                put(JSONObject().apply {
                    put("lat", from.lat)
                    put("lon", from.lon)
                    put("type", "break")
                })
                put(JSONObject().apply {
                    put("lat", to.lat)
                    put("lon", to.lon)
                    put("type", "break")
                })
            })

            put("costing", "pedestrian")
            put("costing_options", JSONObject().apply {
                put("pedestrian", JSONObject().apply {
                    put("walking_speed", 4.5)
                })
            })

            if (avoidPoints.isNotEmpty()) {
                val avoidArray = JSONArray()
                avoidPoints.forEach { pt ->
                    avoidArray.put(JSONObject().apply {
                        put("lat", pt.lat)
                        put("lon", pt.lon)
                    })
                }
                put("exclude_locations", avoidArray)
                put("avoid_locations", avoidArray)
            }

            put("directions_options", JSONObject().apply {
                put("units", "kilometers")
            })
            put("id", "resqnet_spike_route")
        }

        val responseJsonStr = bridge.route(requestObj.toString())
        val respObj = JSONObject(responseJsonStr)

        if (respObj.has("error_code") || respObj.has("error")) {
            val errorMsg = respObj.optString("error", respObj.optString("message", "Unknown Valhalla error"))
            error("Valhalla route error: $errorMsg")
        }

        val trip = respObj.getJSONObject("trip")
        val summary = trip.getJSONObject("summary")
        val lengthKm = summary.getDouble("length")
        val timeSec = summary.getDouble("time").toLong()

        val legs = trip.getJSONArray("legs")
        val polylinePoints = mutableListOf<LatLon>()
        val maneuvers = mutableListOf<String>()

        for (i in 0 until legs.length()) {
            val leg = legs.getJSONObject(i)
            val encodedShape = leg.optString("shape", "")
            if (encodedShape.isNotBlank()) {
                polylinePoints.addAll(PolylineDecoder.decode(encodedShape, 1e6))
            }

            val legManeuvers = leg.optJSONArray("maneuvers")
            if (legManeuvers != null) {
                for (j in 0 until legManeuvers.length()) {
                    val m = legManeuvers.getJSONObject(j)
                    val text = m.optString("instruction", "")
                    val lengthM = (m.optDouble("length", 0.0) * 1000).toInt()
                    if (text.isNotBlank()) {
                        maneuvers.add("$text ($lengthM m)")
                    }
                }
            }
        }

        RouteResult(
            polyline = polylinePoints,
            distanceM = lengthKm * 1000.0,
            durationS = timeSec,
            maneuvers = maneuvers
        )
    }

    private fun createDefaultConfig(dataDir: File): File {
        val targetConfigFile = File(dataDir, "valhalla.json")
        val tilesTar = File(dataDir, "tiles.tar").takeIf { it.exists() }
            ?: File(dataDir, "valhalla_tiles.tar").takeIf { it.exists() }

        val root = JSONObject().apply {
            put("mjolnir", JSONObject().apply {
                put("tile_dir", dataDir.absolutePath)
                if (tilesTar != null) {
                    put("tile_extract", tilesTar.absolutePath)
                }
                put("concurrency", 1)
                put("use_simple_mem_cache", false)
            })
            put("loki", JSONObject().apply {
                put("actions", JSONArray().apply {
                    put("route")
                    put("locate")
                })
            })
            put("service_limits", JSONObject().apply {
                put("pedestrian", JSONObject().apply {
                    put("max_distance", 100000.0)
                })
            })
        }

        targetConfigFile.writeText(root.toString(2))
        return targetConfigFile
    }

    @Synchronized
    override fun close() {
        bridge.close()
    }
}
