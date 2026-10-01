package com.resqnet.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Polyline6DecoderTest {
    @Test
    fun `decodes a six decimal polyline`() {
        val points = Polyline6Decoder.decode("_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI")

        assertEquals(3, points.size)
        assertEquals(38.5, points.first().latitude, 0.000001)
        assertEquals(-120.2, points.first().longitude, 0.000001)
    }

    @Test
    fun `empty or truncated input does not fabricate points`() {
        assertTrue(Polyline6Decoder.decode("").isEmpty())
        assertTrue(Polyline6Decoder.decode("_").isEmpty())
    }
}
