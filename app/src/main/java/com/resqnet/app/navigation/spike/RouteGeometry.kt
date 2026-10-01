package com.resqnet.app.navigation.spike

import kotlin.math.cos
import kotlin.math.hypot

/** Pure geometry helpers shared by the on-device routing benchmark. */
object RouteGeometry {
    /**
     * Returns [count] points evenly spaced by distance through the interior of [polyline].
     * The first and last 15% are deliberately left clear so an excluded edge cannot trap
     * the route at its origin or destination.
     */
    fun sampleInteriorPoints(polyline: List<LatLon>, count: Int): List<LatLon> {
        require(count >= 0) { "count must not be negative" }
        if (count == 0 || polyline.size < 2) return emptyList()

        val cumulative = DoubleArray(polyline.size)
        for (index in 1 until polyline.size) {
            cumulative[index] = cumulative[index - 1] + distanceMeters(
                polyline[index - 1],
                polyline[index]
            )
        }

        val totalDistance = cumulative.last()
        if (totalDistance == 0.0) return List(count) { polyline.first() }

        val startFraction = 0.15
        val usableFraction = 0.70
        return List(count) { sampleIndex ->
            val fraction = startFraction + usableFraction * (sampleIndex + 1) / (count + 1)
            sampleAtDistance(polyline, cumulative, totalDistance * fraction)
        }
    }

    fun distanceMeters(a: LatLon, b: LatLon): Double {
        val meanLatitudeRadians = Math.toRadians((a.lat + b.lat) / 2.0)
        val deltaLatitude = (b.lat - a.lat) * METERS_PER_DEGREE
        val deltaLongitude = (b.lon - a.lon) * METERS_PER_DEGREE * cos(meanLatitudeRadians)
        return hypot(deltaLatitude, deltaLongitude)
    }

    /** Minimum equirectangular distance from a coordinate to the route segments. */
    fun distanceToPolylineMeters(point: LatLon, polyline: List<LatLon>): Double {
        if (polyline.isEmpty()) return Double.POSITIVE_INFINITY
        if (polyline.size == 1) return distanceMeters(point, polyline.first())

        var minimum = Double.POSITIVE_INFINITY
        for (index in 1 until polyline.size) {
            val start = polyline[index - 1]
            val end = polyline[index]
            val meanLatitudeRadians = Math.toRadians((start.lat + end.lat + point.lat) / 3.0)
            val startX = (start.lon - point.lon) * METERS_PER_DEGREE * cos(meanLatitudeRadians)
            val startY = (start.lat - point.lat) * METERS_PER_DEGREE
            val endX = (end.lon - point.lon) * METERS_PER_DEGREE * cos(meanLatitudeRadians)
            val endY = (end.lat - point.lat) * METERS_PER_DEGREE
            val deltaX = endX - startX
            val deltaY = endY - startY
            val segmentLengthSquared = deltaX * deltaX + deltaY * deltaY
            val projection = if (segmentLengthSquared == 0.0) {
                0.0
            } else {
                ((-startX * deltaX) + (-startY * deltaY)) / segmentLengthSquared
            }.coerceIn(0.0, 1.0)
            minimum = minOf(minimum, hypot(startX + projection * deltaX, startY + projection * deltaY))
        }
        return minimum
    }

    private fun sampleAtDistance(
        polyline: List<LatLon>,
        cumulative: DoubleArray,
        targetDistance: Double
    ): LatLon {
        var upper = cumulative.binarySearch(targetDistance)
        if (upper >= 0) return polyline[upper]
        upper = (-upper - 1).coerceIn(1, polyline.lastIndex)

        val lower = upper - 1
        // A decoded Valhalla shape contains road geometry vertices. Prefer one of
        // those vertices for an exclusion point; interpolating across a long shape
        // segment can put the point off the road at a bend.
        val nearestVertex = if (targetDistance - cumulative[lower] <= cumulative[upper] - targetDistance) {
            lower
        } else {
            upper
        }
        if (nearestVertex in 1 until polyline.lastIndex) {
            return polyline[nearestVertex]
        }

        val segmentLength = cumulative[upper] - cumulative[lower]
        if (segmentLength == 0.0) return polyline[upper]

        val ratio = (targetDistance - cumulative[lower]) / segmentLength
        val start = polyline[lower]
        val end = polyline[upper]
        return LatLon(
            lat = start.lat + (end.lat - start.lat) * ratio,
            lon = start.lon + (end.lon - start.lon) * ratio
        )
    }

    private const val METERS_PER_DEGREE = 111_000.0
}
