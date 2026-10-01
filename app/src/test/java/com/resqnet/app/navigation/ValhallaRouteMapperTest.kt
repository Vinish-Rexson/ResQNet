package com.resqnet.app.navigation

import com.valhalla.api.models.RouteLeg
import com.valhalla.api.models.RouteManeuver
import com.valhalla.api.models.RouteResponse
import com.valhalla.api.models.RouteResponseTrip
import com.valhalla.api.models.RouteSummary
import com.valhalla.api.models.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ValhallaRouteMapperTest {
    @Test
    fun `deduplicates joined geometry and offsets maneuvers after the join`() {
        val summary = RouteSummary(time = 10.0, length = 0.001, minLat = 0.0, maxLat = 0.0, minLon = 0.0, maxLon = 0.0)
        val firstLeg = RouteLeg(
            maneuvers = listOf(maneuver("Start")),
            shape = "??",
            summary = summary
        )
        val secondLeg = RouteLeg(
            maneuvers = listOf(maneuver("Arrive")),
            shape = "??",
            summary = summary
        )
        val response = RouteResponse(
            trip = RouteResponseTrip(
                status = 0,
                statusMessage = "",
                locations = emptyList(),
                legs = listOf(firstLeg, secondLeg),
                summary = summary
            )
        )

        val plan = ValhallaRouteMapper.map(response, "test-pack")

        assertEquals(1, plan.geometry.size)
        assertEquals(2, plan.maneuvers.size)
        assertEquals(0, plan.maneuvers[1].beginShapeIndex)
        assertEquals(0, plan.maneuvers[1].endShapeIndex)
        assertEquals(1.0, plan.distanceMeters, 0.0001)
    }

    private fun maneuver(instruction: String) = RouteManeuver(
        type = 1,
        instruction = instruction,
        time = 5.0,
        length = 0.0005,
        beginShapeIndex = 0,
        endShapeIndex = 0,
        travelMode = TravelMode.pedestrian,
        travelType = RouteManeuver.TravelType.foot
    )
}
