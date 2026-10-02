package com.resqnet.app.mesh.barp

import org.junit.Assert.assertEquals
import org.junit.Test

class BarpPolicyTest {
    @Test fun normalAndConservationUseTheConfiguredHysteresis() {
        val normal = RelayMode.NORMAL
        assertEquals(RelayMode.NORMAL, BarpPolicy.automaticMode(BatteryState(40, false, false), normal))
        assertEquals(RelayMode.CONSERVATION, BarpPolicy.automaticMode(BatteryState(39, false, false), normal))
        assertEquals(RelayMode.CONSERVATION, BarpPolicy.automaticMode(BatteryState(44, false, false), RelayMode.CONSERVATION))
        assertEquals(RelayMode.NORMAL, BarpPolicy.automaticMode(BatteryState(45, false, false), RelayMode.CONSERVATION))
    }

    @Test fun criticalModeExitsAtTwentyPercent() {
        assertEquals(RelayMode.CRITICAL, BarpPolicy.automaticMode(BatteryState(15, false, false), RelayMode.NORMAL))
        assertEquals(RelayMode.CRITICAL, BarpPolicy.automaticMode(BatteryState(19, false, false), RelayMode.CRITICAL))
        assertEquals(RelayMode.CONSERVATION, BarpPolicy.automaticMode(BatteryState(20, false, false), RelayMode.CRITICAL))
    }

    @Test fun powerSaverRequestsConservationAndChargingRestoresNormal() {
        assertEquals(RelayMode.CONSERVATION, BarpPolicy.automaticMode(BatteryState(90, false, true), RelayMode.NORMAL))
        assertEquals(RelayMode.NORMAL, BarpPolicy.automaticMode(BatteryState(10, true, true), RelayMode.CRITICAL))
    }

    @Test fun scanModeChangesWaitForThirtySeconds() {
        val dwell = ScanModeDwell()
        dwell.recordChange(1_000)
        assertEquals(20_000, dwell.delayUntilChangeAllowed(11_000))
        assertEquals(0, dwell.delayUntilChangeAllowed(31_000))
    }
}
