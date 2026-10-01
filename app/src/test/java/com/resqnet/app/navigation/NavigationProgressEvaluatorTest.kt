package com.resqnet.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationProgressEvaluatorTest {
    private val origin = GeoPoint(19.0, 72.0)
    private val destination = GeoPoint(19.1, 72.1)
    private val route = listOf(origin, destination)

    @Test fun `rejects stale and inaccurate fixes`() {
        val evaluator = NavigationProgressEvaluator()
        assertEquals(FixDecision.Ignored, evaluator.evaluate(origin, 51f, 1000, 1001, destination, route))
        assertEquals(FixDecision.Ignored, evaluator.evaluate(origin, 5f, 0, 10_001, destination, route))
    }

    @Test fun `uses three accepted off route fixes and a debounce`() {
        val evaluator = NavigationProgressEvaluator()
        val offRoute = GeoPoint(19.01, 72.03)
        assertEquals(FixDecision.KeepRoute, evaluator.evaluate(offRoute, 5f, 1_000, 1_001, destination, route))
        assertEquals(FixDecision.KeepRoute, evaluator.evaluate(offRoute, 5f, 2_000, 2_001, destination, route))
        assertEquals(FixDecision.Reroute, evaluator.evaluate(offRoute, 5f, 3_000, 3_001, destination, route))
        assertEquals(FixDecision.KeepRoute, evaluator.evaluate(offRoute, 5f, 4_000, 4_001, destination, route))
        assertEquals(FixDecision.KeepRoute, evaluator.evaluate(offRoute, 5f, 5_000, 5_001, destination, route))
        assertEquals(FixDecision.KeepRoute, evaluator.evaluate(offRoute, 5f, 6_000, 6_001, destination, route))
    }

    @Test fun `arrives inside twenty five metres`() {
        val evaluator = NavigationProgressEvaluator()
        val nearDestination = GeoPoint(19.1001, 72.1)
        assertEquals(FixDecision.Arrived, evaluator.evaluate(nearDestination, 5f, 1_000, 1_001, destination, route))
    }
}
