package com.resqnet.app.navigation

import com.valhalla.api.models.RouteResponse

internal object ValhallaRouteMapper {
    fun map(response: RouteResponse, packVersion: String): RoutePlan {
        val geometry = mutableListOf<GeoPoint>()
        val maneuvers = mutableListOf<RouteManeuver>()
        response.trip.legs.forEach { leg ->
            val legPoints = Polyline6Decoder.decode(leg.shape)
            if (geometry.isNotEmpty() && legPoints.isNotEmpty() && geometry.last() == legPoints.first()) {
                geometry.removeAt(geometry.lastIndex)
            }
            val offset = geometry.size
            geometry += legPoints
            leg.maneuvers.forEach { maneuver ->
                maneuvers += RouteManeuver(
                    instruction = maneuver.instruction,
                    distanceMeters = maneuver.length * 1_000.0,
                    durationSeconds = maneuver.time,
                    beginShapeIndex = maneuver.beginShapeIndex + offset,
                    endShapeIndex = maneuver.endShapeIndex + offset
                )
            }
        }
        if (geometry.isEmpty()) throw RoutingException.PackCorrupt("Valhalla response contains no route geometry")
        return RoutePlan(
            geometry = geometry,
            distanceMeters = response.trip.summary.length * 1_000.0,
            durationSeconds = response.trip.summary.time,
            maneuvers = maneuvers,
            packVersion = packVersion
        )
    }
}
