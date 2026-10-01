package com.resqnet.app.navigation.spike

import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.config.Profile
import com.graphhopper.json.Statement
import com.graphhopper.util.CustomModel
import com.graphhopper.util.JsonFeature
import com.graphhopper.util.JsonFeatureCollection
import com.graphhopper.util.Parameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import java.io.File

/**
 * GraphHopper on-device routing engine implementation (v9.1).
 * Supports pedestrian routing with Contraction Hierarchies (speed mode)
 * and CustomModel / CH-disabled mode for obstacle / flood avoidance.
 */
class GhRoutingEngine : RoutingEngine {
    override val name: String = "GraphHopper (v9.1)"

    private var hopper: GraphHopper? = null
    private val geomFactory = GeometryFactory()

    @Synchronized
    override fun isInitialized(): Boolean = hopper != null

    override suspend fun init(dataDir: File): Unit = withContext(Dispatchers.IO) {
        if (hopper != null) {
            close()
        }

        require(dataDir.exists()) {
            "GraphHopper data directory does not exist: ${dataDir.absolutePath}"
        }
        val contents = dataDir.listFiles()
        require(!contents.isNullOrEmpty()) {
            "GraphHopper data directory is empty: ${dataDir.absolutePath}"
        }

        val gh = GraphHopper().apply {
            // Profile configuration matches pre-built graph foot profile
            profiles = listOf(
                Profile("foot").setWeighting("shortest")
            )
            setGraphHopperLocation(dataDir.absolutePath)
            setAllowWrites(false) // Read-only memory-mapped access
        }

        val loaded = gh.load()
        if (!loaded) {
            gh.close()
            error("GraphHopper failed to load graph cache from ${dataDir.absolutePath}")
        }
        hopper = gh
    }

    override suspend fun route(
        from: LatLon,
        to: LatLon,
        profile: RoutingProfile,
        avoidPoints: List<LatLon>
    ): RouteResult = withContext(Dispatchers.IO) {
        val gh = hopper ?: error("GraphHopper is not initialized. Call init(dataDir) first.")

        val req = GHRequest(from.lat, from.lon, to.lat, to.lon)
            .setProfile("foot")
            .setLocale(java.util.Locale.ENGLISH)

        if (avoidPoints.isNotEmpty()) {
            // Build custom model to avoid areas around the blocked points
            val customModel = CustomModel()
            val featureCollection = JsonFeatureCollection()

            // Small bounding box radius (~100m, ~0.0009 degrees) around each avoid point
            val delta = 0.0009

            avoidPoints.forEachIndexed { idx, pt ->
                val areaId = "avoid_$idx"
                val coords = arrayOf(
                    Coordinate(pt.lon - delta, pt.lat - delta),
                    Coordinate(pt.lon + delta, pt.lat - delta),
                    Coordinate(pt.lon + delta, pt.lat + delta),
                    Coordinate(pt.lon - delta, pt.lat + delta),
                    Coordinate(pt.lon - delta, pt.lat - delta) // Closed ring
                )
                val polygon = geomFactory.createPolygon(coords)
                val feature = JsonFeature(areaId, "Polygon", polygon.envelopeInternal, polygon, emptyMap())
                featureCollection.features.add(feature)

                // Multiply priority by 0 for roads inside this area
                customModel.addToPriority(
                    Statement.If("in_area_$areaId", Statement.Op.MULTIPLY, "0")
                )
            }

            customModel.areas = featureCollection
            req.customModel = customModel

            // Disable CH to allow custom model dynamic weighting
            req.putHint(Parameters.CH.DISABLE, true)
        }

        val resp = gh.route(req)
        if (resp.hasErrors()) {
            val errorMsg = resp.errors.joinToString("; ") { it.message ?: it.toString() }
            error("GraphHopper routing failed: $errorMsg")
        }

        val path = resp.best
        val points = ArrayList<LatLon>(path.points.size())
        for (i in 0 until path.points.size()) {
            points.add(LatLon(path.points.getLat(i), path.points.getLon(i)))
        }

        val maneuvers = path.instructions?.map { instruction ->
            val dist = instruction.distance.toInt()
            val text = instruction.name.ifBlank { "unnamed road" }
            "$text ($dist m)"
        } ?: emptyList()

        RouteResult(
            polyline = points,
            distanceM = path.distance,
            durationS = path.time / 1000L,
            maneuvers = maneuvers
        )
    }

    @Synchronized
    override fun close() {
        hopper?.close()
        hopper = null
    }
}
