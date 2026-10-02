package com.resqnet.app.mesh.barp

import com.resqnet.app.protocol.*
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CriticalTrafficTest {
    @Test fun onlyNeedHelpCircleStatusIsCritical() {
        fun status(value: SafetyStatus) = PayloadV2(
            UUID.randomUUID(), PacketKind.CIRCLE_STATUS, Audience.Circle("circle"), "node", "Name", 1,
            1, 2, RelayPolicy.EPHEMERAL, CircleStatusBody("circle", 1, "node", value, null),
        )
        assertTrue(status(SafetyStatus.NEED_HELP).isBarpCriticalTraffic())
        assertFalse(status(SafetyStatus.SAFE).isBarpCriticalTraffic())
    }
}
