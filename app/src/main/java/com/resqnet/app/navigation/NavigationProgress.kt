package com.resqnet.app.navigation

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

sealed class NavigationState {
    data object Idle : NavigationState()
    data class WaitingForFix(val destination: GeoPoint) : NavigationState()
    data class Active(
        val destination: GeoPoint,
        val nextInstruction: String,
        val distanceMeters: Double,
        val durationSeconds: Double,
        val geometry: List<GeoPoint> = emptyList(),
        val currentPoint: GeoPoint? = null
    ) : NavigationState()
    data class Arrived(val destination: GeoPoint) : NavigationState()
    data class Failed(val message: String) : NavigationState()
}

sealed class FixDecision {
    data object Ignored : FixDecision()
    data object KeepRoute : FixDecision()
    data object Reroute : FixDecision()
    data object Arrived : FixDecision()
}

/** Deterministic accepted-fix, off-route, and reroute-debounce policy. */
class NavigationProgressEvaluator {
    private var consecutiveOffRoute = 0
    private var lastRerouteAt = Long.MIN_VALUE

    fun evaluate(
        point: GeoPoint,
        accuracyMeters: Float,
        fixTimeMillis: Long,
        nowMillis: Long,
        destination: GeoPoint,
        routeGeometry: List<GeoPoint>
    ): FixDecision {
        if (!accuracyMeters.isFinite() || accuracyMeters > MAX_ACCURACY_METERS || nowMillis - fixTimeMillis > MAX_FIX_AGE_MILLIS) {
            return FixDecision.Ignored
        }
        if (distanceMeters(point, destination) <= ARRIVAL_METERS) return FixDecision.Arrived
        val threshold = maxOf(MIN_OFF_ROUTE_METERS, 2.0 * accuracyMeters)
        val offRoute = routeGeometry.isNotEmpty() && routeGeometry.minOf { distanceMeters(point, it) } > threshold
        if (!offRoute) {
            consecutiveOffRoute = 0
            return FixDecision.KeepRoute
        }
        consecutiveOffRoute += 1
        if (consecutiveOffRoute >= REQUIRED_OFF_ROUTE_FIXES &&
            (lastRerouteAt == Long.MIN_VALUE || nowMillis - lastRerouteAt >= REROUTE_DEBOUNCE_MILLIS)) {
            consecutiveOffRoute = 0
            lastRerouteAt = nowMillis
            return FixDecision.Reroute
        }
        return FixDecision.KeepRoute
    }

    fun distanceMeters(first: GeoPoint, second: GeoPoint): Double = Companion.distanceMeters(first, second)

    companion object {
        const val MAX_ACCURACY_METERS = 50f
        const val MAX_FIX_AGE_MILLIS = 10_000L
        const val MIN_OFF_ROUTE_METERS = 30.0
        const val REQUIRED_OFF_ROUTE_FIXES = 3
        const val REROUTE_DEBOUNCE_MILLIS = 15_000L
        const val ARRIVAL_METERS = 25.0

        fun distanceMeters(first: GeoPoint, second: GeoPoint): Double {
            val radius = 6_371_000.0
            val lat = Math.toRadians(second.latitude - first.latitude)
            val lon = Math.toRadians(second.longitude - first.longitude)
            val a = sin(lat / 2) * sin(lat / 2) + cos(Math.toRadians(first.latitude)) *
                cos(Math.toRadians(second.latitude)) * sin(lon / 2) * sin(lon / 2)
            return radius * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
