package com.resqnet.app.navigation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ShelterRouteSelectorTest {
    @Test fun `routes only five nearest candidates and chooses fastest successful route`() = runTest {
        val calls = mutableListOf<Double>()
        val engine = object : RoutingEngine {
            override suspend fun initialize(pack: InstalledRegionPack) = Unit
            override fun isInitialized() = true
            override fun close() = Unit
            override suspend fun calculateRoute(request: NavigationRouteRequest): RoutePlan {
                calls += request.destination.longitude
                if (request.destination.longitude == 2.0) throw RoutingException.NoRoute("blocked")
                return route(duration = if (request.destination.longitude == 3.0) 10.0 else 20.0)
            }
        }
        val shelters = (1..6).map { index -> shelter(index.toDouble()) }
        val result = ShelterRouteSelector(engine).chooseBest(GeoPoint(0.0, 0.0), shelters)
        assertEquals(5, calls.size)
        assertEquals("3", result?.shelter?.id)
    }

    private fun shelter(longitude: Double) = Shelter(longitude.toInt().toString(), "S$longitude", longitude, 0.0, null, null, null, true, null)
    private fun route(duration: Double) = RoutePlan(emptyList(), 0.0, duration, emptyList(), "test")
}
