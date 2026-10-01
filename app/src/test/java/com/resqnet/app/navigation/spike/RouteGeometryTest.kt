package com.resqnet.app.navigation.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGeometryTest {
    @Test
    fun `samples requested points evenly through route interior`() {
        val route = (0..10).map { index -> LatLon(19.0, 72.0 + index * 0.01) }

        val samples = RouteGeometry.sampleInteriorPoints(route, 3)

        assertEquals(3, samples.size)
        assertEquals(72.03, samples[0].lon, 0.000001)
        assertEquals(72.05, samples[1].lon, 0.000001)
        assertEquals(72.07, samples[2].lon, 0.000001)
        assertTrue(samples.all { it.lat == 19.0 })
    }

    @Test
    fun `selects the road vertex nearest the requested distance`() {
        val route = listOf(
            LatLon(0.0, 0.0),
            LatLon(0.0, 0.001),
            LatLon(0.0, 0.010)
        )

        val sample = RouteGeometry.sampleInteriorPoints(route, 1).single()

        assertEquals(0.001, sample.lon, 0.000001)
    }

    @Test
    fun `handles empty count and unusable geometry`() {
        assertTrue(RouteGeometry.sampleInteriorPoints(emptyList(), 10).isEmpty())
        assertTrue(RouteGeometry.sampleInteriorPoints(listOf(LatLon(1.0, 2.0)), 10).isEmpty())
        assertTrue(RouteGeometry.sampleInteriorPoints(listOf(LatLon(1.0, 2.0)), 0).isEmpty())
    }

    @Test
    fun `measures distance to a route segment rather than only its vertices`() {
        val route = listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.01))

        val distance = RouteGeometry.distanceToPolylineMeters(LatLon(0.001, 0.005), route)

        assertEquals(111.0, distance, 1.0)
    }
}
