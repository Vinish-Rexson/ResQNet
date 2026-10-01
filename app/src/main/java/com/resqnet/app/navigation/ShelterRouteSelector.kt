package com.resqnet.app.navigation

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Matrix-free shelter selection: route only the five closest valid candidates. */
class ShelterRouteSelector(private val routingEngine: RoutingEngine) {
    suspend fun chooseBest(origin: GeoPoint, shelters: List<Shelter>): ShelterRoute? {
        val candidates = shelters.asSequence()
            .filter { GeoPoint(it.latitude, it.longitude).isValid() }
            .sortedWith(compareBy<Shelter> { if (it.verified) 0 else 1 }.thenBy { distanceMeters(origin, it) })
            .take(MAX_CANDIDATES)
            .toList()
        return candidates.mapNotNull { shelter ->
            runCatching {
                ShelterRoute(shelter, routingEngine.calculateRoute(NavigationRouteRequest(
                    origin, GeoPoint(shelter.latitude, shelter.longitude)
                )))
            }.getOrNull()
        }.minByOrNull { it.route.durationSeconds }
    }

    private fun GeoPoint.isValid() = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0

    private fun distanceMeters(origin: GeoPoint, shelter: Shelter): Double {
        val radius = 6_371_000.0
        val latDelta = Math.toRadians(shelter.latitude - origin.latitude)
        val lonDelta = Math.toRadians(shelter.longitude - origin.longitude)
        val a = sin(latDelta / 2) * sin(latDelta / 2) + cos(Math.toRadians(origin.latitude)) *
            cos(Math.toRadians(shelter.latitude)) * sin(lonDelta / 2) * sin(lonDelta / 2)
        return radius * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private companion object { const val MAX_CANDIDATES = 5 }
}

data class ShelterRoute(val shelter: Shelter, val route: RoutePlan)
