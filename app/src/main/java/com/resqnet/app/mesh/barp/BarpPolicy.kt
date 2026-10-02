package com.resqnet.app.mesh.barp

import com.resqnet.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RelayMode { NORMAL, CONSERVATION, CRITICAL }

enum class ScanPowerMode { BALANCED, LOW_POWER }

class ScanModeDwell(private val minimumMs: Long = 30_000L) {
    private var lastChangeAtMs: Long? = null

    fun delayUntilChangeAllowed(nowMs: Long): Long = lastChangeAtMs
        ?.let { (minimumMs - (nowMs - it)).coerceAtLeast(0L) }
        ?: 0L

    fun recordChange(nowMs: Long) { lastChangeAtMs = nowMs }
}

data class BatteryState(
    val levelPercent: Int?,
    val charging: Boolean,
    val powerSaveMode: Boolean,
)

data class BarpState(
    val battery: BatteryState = BatteryState(null, charging = false, powerSaveMode = false),
    val automaticMode: RelayMode = RelayMode.NORMAL,
    val mode: RelayMode = RelayMode.NORMAL,
    val debugOverride: RelayMode? = null,
) {
    val scanPowerMode: ScanPowerMode
        get() = if (mode == RelayMode.NORMAL) ScanPowerMode.BALANCED else ScanPowerMode.LOW_POWER

    val syncIntervalMs: Long
        get() = when (mode) {
            RelayMode.NORMAL -> 10_000L
            RelayMode.CONSERVATION -> 30_000L
            RelayMode.CRITICAL -> 60_000L
        }
}

/**
 * Hysteresis keeps battery readings near a threshold from repeatedly changing BLE scan mode.
 * Power Saver asks for conservation whenever the battery is not in the critical range.
 */
object BarpPolicy {
    const val CONSERVATION_ENTER_PERCENT = 40
    const val NORMAL_EXIT_PERCENT = 45
    const val CRITICAL_ENTER_PERCENT = 15
    const val CRITICAL_EXIT_PERCENT = 20

    fun automaticMode(input: BatteryState, previous: RelayMode): RelayMode {
        if (input.charging) return RelayMode.NORMAL
        val level = input.levelPercent ?: return if (input.powerSaveMode) RelayMode.CONSERVATION else previous

        return when (previous) {
            RelayMode.CRITICAL -> when {
                level < CRITICAL_EXIT_PERCENT -> RelayMode.CRITICAL
                input.powerSaveMode || level < NORMAL_EXIT_PERCENT -> RelayMode.CONSERVATION
                else -> RelayMode.NORMAL
            }
            RelayMode.CONSERVATION -> when {
                level <= CRITICAL_ENTER_PERCENT -> RelayMode.CRITICAL
                input.powerSaveMode || level < NORMAL_EXIT_PERCENT -> RelayMode.CONSERVATION
                else -> RelayMode.NORMAL
            }
            RelayMode.NORMAL -> when {
                level <= CRITICAL_ENTER_PERCENT -> RelayMode.CRITICAL
                input.powerSaveMode || level < CONSERVATION_ENTER_PERCENT -> RelayMode.CONSERVATION
                else -> RelayMode.NORMAL
            }
        }
    }
}

class BarpController {
    private val mutable = MutableStateFlow(BarpState())
    val state: StateFlow<BarpState> = mutable.asStateFlow()

    fun updateBattery(input: BatteryState) {
        val current = mutable.value
        val automatic = BarpPolicy.automaticMode(input, current.automaticMode)
        mutable.value = current.copy(
            battery = input,
            automaticMode = automatic,
            mode = current.debugOverride ?: automatic,
        )
    }

    /** Available only from debug builds through Diagnostics. */
    fun setDebugOverride(mode: RelayMode?) {
        check(BuildConfig.DEBUG) { "BARP overrides are only available in debug builds" }
        val current = mutable.value
        mutable.value = current.copy(debugOverride = mode, mode = mode ?: current.automaticMode)
    }
}
