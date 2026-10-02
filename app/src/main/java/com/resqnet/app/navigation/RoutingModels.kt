package com.resqnet.app.navigation

import java.io.File

data class GeoPoint(val latitude: Double, val longitude: Double) {
    fun requireValid(): GeoPoint {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "Latitude must be between -90 and 90" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Longitude must be between -180 and 180" }
        return this
    }
}

data class InstalledRegionPack(
    val regionId: String,
    val version: String,
    val directory: File,
    val tileArchive: File = File(directory, "valhalla_tiles.tar"),
    val configFile: File = File(directory, "valhalla.json")
)

data class NavigationRouteRequest(
    val origin: GeoPoint,
    val destination: GeoPoint,
    val avoidanceAreas: List<AvoidanceArea> = emptyList(),
)

data class RouteManeuver(
    val instruction: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val beginShapeIndex: Int,
    val endShapeIndex: Int
)

data class RoutePlan(
    val geometry: List<GeoPoint>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val maneuvers: List<RouteManeuver>,
    val packVersion: String
)

sealed class RoutingException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause) {
    class PackMissing(message: String) : RoutingException(message)
    class PackCorrupt(message: String, cause: Throwable? = null) : RoutingException(message, cause)
    class OutsideCoverage(message: String) : RoutingException(message)
    class NoRoute(message: String) : RoutingException(message)
    class InvalidCoordinate(message: String) : RoutingException(message)
    class NativeFailure(message: String, cause: Throwable? = null) : RoutingException(message, cause)
}

interface RoutingEngine : AutoCloseable {
    suspend fun initialize(pack: InstalledRegionPack)
    suspend fun calculateRoute(request: NavigationRouteRequest): RoutePlan
    fun isInitialized(): Boolean
    override fun close()
}
