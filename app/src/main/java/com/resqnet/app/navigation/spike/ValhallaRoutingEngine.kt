package com.resqnet.app.navigation.spike

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Valhalla on-device routing engine implementation via valhalla-mobile (v0.6.1).
 * Dynamically adapts valhalla.json to point to on-device tile paths.
 */
class ValhallaRoutingEngine(private val context: Context) : RoutingEngine {
    override val name: String = "Valhalla (v3.6.3 via valhalla-mobile)"

    private val bridge = ValhallaBridge()
    private val requestSequence = AtomicLong(0)

    @Synchronized
    override fun isInitialized(): Boolean = bridge.isInitialized

    override suspend fun init(dataDir: File): Unit = withContext(Dispatchers.IO) {
        if (bridge.isInitialized) {
            close()
        }

        require(dataDir.exists()) {
            "Valhalla data directory does not exist: ${dataDir.absolutePath}"
        }

        val configFile = File(dataDir, "valhalla.json")
        val tilesTar = File(dataDir, "tiles.tar").takeIf { it.exists() }
            ?: File(dataDir, "valhalla_tiles.tar").takeIf { it.exists() }

        if (configFile.exists()) {
            try {
                // Dynamically sanitize config to match on-device tile location
                val json = JSONObject(configFile.readText())
                val mjolnir = json.optJSONObject("mjolnir") ?: JSONObject()
                mjolnir.put("tile_dir", dataDir.absolutePath)
                if (tilesTar != null) {
                    mjolnir.put("tile_extract", tilesTar.absolutePath)
                }
                // Strip non-existent server/desktop paths
                mjolnir.remove("admin")
                mjolnir.remove("timezone")
                mjolnir.remove("traffic_extract")
                mjolnir.remove("landmarks")
                mjolnir.remove("transit_dir")
                mjolnir.remove("transit_feeds_dir")
                json.put("mjolnir", mjolnir)
                json.remove("additional_data")

                val serviceLimits = json.optJSONObject("service_limits") ?: JSONObject()
                serviceLimits.put("allow_hard_exclusions", true)
                serviceLimits.put("max_exclude_polygons_length", 100000)
                serviceLimits.put("max_exclude_locations", 100)
                json.put("service_limits", serviceLimits)

                configFile.writeText(json.toString(2))
            } catch (_: Throwable) {
                createDefaultConfig(dataDir)
            }
        } else {
            createDefaultConfig(dataDir)
        }

        val configJson = JSONObject(configFile.readText())
        val limits = configJson.optJSONObject("service_limits") ?: JSONObject()
        android.util.Log.i(
            "ValhallaConfig",
            "path=${configFile.absolutePath} max_exclude_locations=${limits.optInt("max_exclude_locations", -1)} " +
                "max_exclude_polygons_length=${limits.optInt("max_exclude_polygons_length", -1)} " +
                "allow_hard_exclusions=${limits.optBoolean("allow_hard_exclusions", false)}"
        )
        bridge.init(configFile.absolutePath)
    }

    override suspend fun route(
        from: LatLon,
        to: LatLon,
        profile: RoutingProfile,
        avoidPoints: List<LatLon>
    ): RouteResult = routeInternal(from, to, profile, avoidPoints, null)

    /** Diagnostic-only polygon request; normal RoutingEngine behavior is unchanged. */
    suspend fun routeWithExcludePolygon(
        from: LatLon,
        to: LatLon,
        profile: RoutingProfile = RoutingProfile.PEDESTRIAN,
        center: LatLon,
        boxSizeMeters: Double
    ): RouteResult = routeInternal(from, to, profile, emptyList(), PolygonExclusion(center, boxSizeMeters))

    private suspend fun routeInternal(
        from: LatLon,
        to: LatLon,
        profile: RoutingProfile,
        avoidPoints: List<LatLon>,
        polygon: PolygonExclusion?
    ): RouteResult = withContext(Dispatchers.IO) {
        require(bridge.isInitialized) {
            "Valhalla engine is not initialized. Call init(dataDir) first."
        }

        val requestId = "resqnet_spike_route_${requestSequence.incrementAndGet()}"
        val requestMode = when {
            polygon != null -> "exclude_polygons"
            avoidPoints.isNotEmpty() -> "exclude_locations"
            else -> "baseline"
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

            if (polygon != null) {
                put("exclude_polygons", polygon.toJson())
            } else if (avoidPoints.isNotEmpty()) {
                val avoidArray = JSONArray()
                avoidPoints.forEach { pt ->
                    avoidArray.put(JSONObject().apply {
                        put("lat", pt.lat)
                        put("lon", pt.lon)
                        // Obstacles are already road-level reports. Avoid Valhalla's
                        // comparatively expensive default reachability/ranking work for
                        // every excluded location and keep correlation local to the report.
                        put("minimum_reachability", 0)
                        put("radius", AVOID_RADIUS_METERS)
                        put("search_cutoff", AVOID_SEARCH_CUTOFF_METERS)
                        put("rank_candidates", false)
                    })
                }
                put("exclude_locations", avoidArray)
            }

            put("directions_options", JSONObject().apply {
                put("units", "kilometers")
            })
            put("id", requestId)
        }

        val requestJson = requestObj.toString()
        logRawRequest(
            requestId = requestId,
            mode = requestMode,
            points = avoidPoints.size,
            polygonBoxMeters = polygon?.boxSizeMeters ?: 0.0,
            json = requestJson
        )
        val responseJsonStr = bridge.route(requestJson)
        val respObj = JSONObject(responseJsonStr)
        android.util.Log.i(
            "ValhallaRawResponse",
            "requestId=$requestId responseId=${respObj.optString("id", "<missing>")} " +
                "hasTrip=${respObj.has("trip")} warnings=${respObj.optJSONArray("warnings") ?: "[]"}"
        )

        if (!respObj.has("trip")) {
            val errorMsg = respObj.optString("error", respObj.optString("message", respObj.toString()))
            error("Valhalla route error: $errorMsg (Raw response: $responseJsonStr)")
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
                put("allow_hard_exclusions", true)
                put("max_exclude_polygons_length", 100000)
                put("max_exclude_locations", 100)
            })
        }

        targetConfigFile.writeText(root.toString(2))
        return targetConfigFile
    }

    private fun logRawRequest(
        requestId: String,
        mode: String,
        points: Int,
        polygonBoxMeters: Double,
        json: String
    ) {
        val prefix = "requestId=$requestId mode=$mode points=$points polygonBoxMeters=$polygonBoxMeters json="
        val chunkSize = 3000
        val chunks = json.chunked(chunkSize)
        chunks.forEachIndexed { index, chunk ->
            android.util.Log.i(
                "ValhallaRawRequest",
                "$prefix part=${index + 1}/${chunks.size} $chunk"
            )
        }
    }

    @Synchronized
    override fun close() {
        bridge.close()
    }

    private companion object {
        const val AVOID_RADIUS_METERS = 75
        const val AVOID_SEARCH_CUTOFF_METERS = 150
    }
}

private data class PolygonExclusion(
    val center: LatLon,
    val boxSizeMeters: Double
) {
    init {
        require(boxSizeMeters > 0.0) { "Polygon box size must be positive" }
    }

    fun toJson(): JSONArray {
        val halfLat = (boxSizeMeters / 2.0) / 111_000.0
        val halfLon = halfLat / kotlin.math.cos(Math.toRadians(center.lat))
        val minLat = center.lat - halfLat
        val maxLat = center.lat + halfLat
        val minLon = center.lon - halfLon
        val maxLon = center.lon + halfLon

        val ring = JSONArray()
            .put(JSONArray().put(minLon).put(minLat))
            .put(JSONArray().put(maxLon).put(minLat))
            .put(JSONArray().put(maxLon).put(maxLat))
            .put(JSONArray().put(minLon).put(maxLat))
            .put(JSONArray().put(minLon).put(minLat))
        return JSONArray().put(ring)
    }
}
