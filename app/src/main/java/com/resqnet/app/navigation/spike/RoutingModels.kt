package com.resqnet.app.navigation.spike

import java.io.File

/**
 * Coordinate pair (WGS84).
 */
data class LatLon(
    val lat: Double,
    val lon: Double
) {
    override fun toString(): String = "($lat, $lon)"
}

/**
 * Routing profile supported by the on-device engines.
 */
enum class RoutingProfile {
    PEDESTRIAN
}

/**
 * Normalized result of a routing calculation.
 */
data class RouteResult(
    val polyline: List<LatLon>,
    val distanceM: Double,
    val durationS: Long,
    val maneuvers: List<String>
)

/**
 * Unified interface for on-device routing engines.
 */
interface RoutingEngine {
    val name: String

    /**
     * Initializes the engine with the directory containing pre-built graph / tile files.
     */
    suspend fun init(dataDir: File)

    /**
     * Computes a route between [from] and [to].
     * @param profile Routing profile (default is PEDESTRIAN).
     * @param avoidPoints Coordinates of blocked obstacles / flooded intersections to route around.
     */
    suspend fun route(
        from: LatLon,
        to: LatLon,
        profile: RoutingProfile = RoutingProfile.PEDESTRIAN,
        avoidPoints: List<LatLon> = emptyList()
    ): RouteResult

    /**
     * Returns true if the engine has been successfully initialized and is ready to route.
     */
    fun isInitialized(): Boolean

    /**
     * Closes and releases any native or memory-mapped resources.
     */
    fun close()
}
