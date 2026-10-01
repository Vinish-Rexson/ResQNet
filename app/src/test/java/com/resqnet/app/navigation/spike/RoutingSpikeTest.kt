package com.resqnet.app.navigation.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlinx.coroutines.test.runTest

class RoutingSpikeTest {

    @Test
    fun testPolylineDecoderDecodesKnownPoints() {
        // Valhalla polyline6 encoding for Vasai coordinates
        // Let's test standard polyline decode with factor 1e6
        val testPoints = listOf(
            LatLon(19.3828, 72.8319),
            LatLon(19.3830, 72.8322)
        )

        // Verify PolylineDecoder handles empty or single points without crashing
        val empty = PolylineDecoder.decode("")
        assertTrue(empty.isEmpty())

        // Test decode with a known string
        // Polyline format encoding of (38.5, -120.2) in 1e5 is "_p~iF~ps|U"
        val decoded5 = PolylineDecoder.decode("_p~iF~ps|U", precision = 1e5)
        assertEquals(1, decoded5.size)
        assertEquals(38.5, decoded5[0].lat, 0.0001)
        assertEquals(-120.2, decoded5[0].lon, 0.0001)
    }

    @Test
    fun testRouteResultDataIntegrity() {
        val points = listOf(LatLon(19.3828, 72.8319), LatLon(19.3750, 72.8240))
        val maneuvers = listOf("Turn left onto Station Rd", "Continue straight")
        val result = RouteResult(
            polyline = points,
            distanceM = 1120.5,
            durationS = 850L,
            maneuvers = maneuvers
        )

        assertEquals(2, result.polyline.size)
        assertEquals(1120.5, result.distanceM, 0.001)
        assertEquals(850L, result.durationS)
        assertEquals(2, result.maneuvers.size)
    }

    @Test
    fun testGhRoutingEngineRequiresValidDirectory() = runTest {
        val engine = GhRoutingEngine()
        assertFalse(engine.isInitialized())

        try {
            engine.init(File("/non/existent/path/for/graphhopper"))
            fail("Expected exception for non-existent path")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("does not exist") == true)
        }
    }

    @Test
    fun testGhRoutingEngineThrowsIfNotInitialized() = runTest {
        val engine = GhRoutingEngine()
        try {
            engine.route(LatLon(19.3828, 72.8319), LatLon(19.3750, 72.8240))
            fail("Expected error when routing before init")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("not initialized") == true)
        }
    }
}
