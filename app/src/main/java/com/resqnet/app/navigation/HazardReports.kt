package com.resqnet.app.navigation

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.math.cos
import kotlin.math.sin

enum class HazardType(val label: String, val defaultRadiusMeters: Int, val defaultDurationMillis: Long) {
    FLOOD("Flood", 100, 6 * 60 * 60 * 1000L),
    UNSAFE_AREA("Unsafe area", 50, 24 * 60 * 60 * 1000L),
}

@Entity(
    tableName = "hazard_reports",
    indices = [Index("expiresAt"), Index("resolvedAt"), Index("updatedAt")],
)
data class HazardReportEntity(
    @PrimaryKey val reportId: String,
    val type: HazardType,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val expiresAt: Long,
    val resolvedAt: Long? = null,
)

data class HazardReport(
    val id: String,
    val type: HazardType,
    val center: GeoPoint,
    val radiusMeters: Int,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val expiresAt: Long,
    val resolvedAt: Long?,
) {
    fun toAvoidanceArea() = AvoidanceArea(center, radiusMeters, id)
}

data class AvoidanceArea(val center: GeoPoint, val radiusMeters: Int, val reportId: String) {
    init {
        center.requireValid()
        require(radiusMeters in MIN_RADIUS_METERS..MAX_RADIUS_METERS) { "Hazard radius must be between $MIN_RADIUS_METERS and $MAX_RADIUS_METERS metres" }
    }

    /** Closed longitude/latitude polygon accepted by Valhalla's exclude_polygons field. */
    fun toValhallaPolygon(points: Int = POLYGON_POINTS): List<List<Double>> {
        val latitudeRadians = Math.toRadians(center.latitude)
        return (0..points).map { index ->
            val bearing = (2.0 * Math.PI * (index % points)) / points
            val lat = center.latitude + (radiusMeters * cos(bearing) / METERS_PER_LATITUDE_DEGREE)
            val lon = center.longitude + (radiusMeters * sin(bearing) / (METERS_PER_LATITUDE_DEGREE * cos(latitudeRadians)))
            listOf(lon, lat)
        }
    }

    companion object {
        const val MIN_RADIUS_METERS = 25
        const val MAX_RADIUS_METERS = 250
        private const val POLYGON_POINTS = 12
        private const val METERS_PER_LATITUDE_DEGREE = 111_320.0
    }
}

fun HazardReportEntity.toDomain() = HazardReport(
    id = reportId,
    type = type,
    center = GeoPoint(latitude, longitude),
    radiusMeters = radiusMeters,
    note = note,
    createdAt = createdAt,
    updatedAt = updatedAt,
    expiresAt = expiresAt,
    resolvedAt = resolvedAt,
)
