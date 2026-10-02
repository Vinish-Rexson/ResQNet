package com.resqnet.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HazardAvoidanceTest {
    @Test fun avoidancePolygonIsClosedAndUsesLongitudeLatitudePairs() {
        val area = AvoidanceArea(GeoPoint(19.0760, 72.8777), 100, "report-1")

        val polygon = area.toValhallaPolygon()

        assertEquals(13, polygon.size)
        assertEquals(polygon.first(), polygon.last())
        assertEquals(2, polygon.first().size)
        // Mumbai longitude is greater than its latitude, which makes order errors obvious.
        assertTrue(polygon.first()[0] > polygon.first()[1])
    }

    @Test fun hazardDefaultsAreTailoredToType() {
        assertEquals(100, HazardType.FLOOD.defaultRadiusMeters)
        assertEquals(50, HazardType.UNSAFE_AREA.defaultRadiusMeters)
        assertTrue(HazardType.UNSAFE_AREA.defaultDurationMillis > HazardType.FLOOD.defaultDurationMillis)
    }
}
