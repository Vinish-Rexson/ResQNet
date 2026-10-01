package com.resqnet.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.util.Log
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.resqnet.app.R
import com.resqnet.app.navigation.spike.GhRoutingEngine
import com.resqnet.app.navigation.spike.LatLon
import com.resqnet.app.navigation.spike.RoutingEngine
import com.resqnet.app.navigation.spike.RoutingProfile
import com.resqnet.app.navigation.spike.ValhallaRoutingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Memory snapshot capturing Total PSS from Debug.MemoryInfo plus
 * allocated native heap from Debug.getNativeHeapAllocatedSize().
 */
data class MemorySnapshot(
    val totalPssKb: Int,
    val nativeAllocatedBytes: Long
) {
    val totalPssMb: Double get() = totalPssKb / 1024.0
    val nativeAllocatedMb: Double get() = nativeAllocatedBytes / (1024.0 * 1024.0)

    fun format(): String =
        String.format(Locale.US, "Total PSS: %.2f MB | Native Heap: %.2f MB", totalPssMb, nativeAllocatedMb)
}

/**
 * Debug-only activity for benchmarking and validating on-device routing engine candidates
 * (GraphHopper vs Valhalla) without requiring full map rendering or network connectivity.
 */
class SpikeActivity : AppCompatActivity() {

    private lateinit var tvStorageStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollViewLog: ScrollView
    private lateinit var rgEngine: RadioGroup
    private lateinit var rgRoutePair: RadioGroup
    private lateinit var rgAvoid: RadioGroup
    private lateinit var btnRunSingle: Button
    private lateinit var btnRunBenchmark: Button

    private var activeEngine: RoutingEngine? = null

    // Test coordinates within Mumbai Metropolitan Region (Vasai-Virar to Mumbai)
    companion object {
        // Short: Vasai Station East to Manikpur (~1.1 km)
        val SHORT_FROM = LatLon(19.3828, 72.8319)
        val SHORT_TO = LatLon(19.3750, 72.8240)

        // Medium: Vasai Station to Arnala (~8.2 km)
        val MED_FROM = LatLon(19.3828, 72.8319)
        val MED_TO = LatLon(19.4530, 72.7750)

        // Long: Vasai-Virar to Borivali (~28.5 km)
        val LONG_FROM = LatLon(19.3900, 72.8300)
        val LONG_TO = LatLon(19.2288, 72.8541)

        // Single blocked point along the medium corridor (Gas/Nalasopara Link)
        val AVOID_1 = listOf(LatLon(19.4180, 72.8050))

        // 10 blocked points scattered along the corridor
        val AVOID_10 = listOf(
            LatLon(19.3900, 72.8250),
            LatLon(19.3970, 72.8200),
            LatLon(19.4050, 72.8150),
            LatLon(19.4120, 72.8100),
            LatLon(19.4180, 72.8050),
            LatLon(19.4250, 72.8000),
            LatLon(19.4320, 72.7950),
            LatLon(19.4390, 72.7900),
            LatLon(19.4450, 72.7850),
            LatLon(19.4500, 72.7800)
        )

        // Strict acceptance thresholds specified for the spike (Do not alter)
        const val THRESHOLD_COLD_START_MS = 5000L   // <= 5 s
        const val THRESHOLD_MEDIUM_ROUTE_MS = 3000L // <= 3 s
        const val THRESHOLD_LONG_ROUTE_MS = 8000L   // <= 8 s
        const val THRESHOLD_EXTRA_PSS_MB = 300.0    // <= 300 MB
        const val THRESHOLD_AVOIDANCE_FACTOR = 2.0  // <= 2x unblocked latency
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_spike)

        tvStorageStatus = findViewById(R.id.tvStorageStatus)
        tvLog = findViewById(R.id.tvLog)
        scrollViewLog = findViewById(R.id.scrollViewLog)
        rgEngine = findViewById(R.id.rgEngine)
        rgRoutePair = findViewById(R.id.rgRoutePair)
        rgAvoid = findViewById(R.id.rgAvoid)
        btnRunSingle = findViewById(R.id.btnRunSingle)
        btnRunBenchmark = findViewById(R.id.btnRunBenchmark)

        findViewById<Button>(R.id.btnRefreshStorage).setOnClickListener {
            checkStorageStatus()
        }

        findViewById<Button>(R.id.btnCopyLog).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("ResQNet Spike Log", tvLog.text))
            Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            tvLog.text = ""
        }

        btnRunSingle.setOnClickListener {
            executeSingleRoute()
        }

        btnRunBenchmark.setOnClickListener {
            executeBenchmarkSuite()
        }

        checkStorageStatus()
        logDeviceInfo()
    }

    override fun onDestroy() {
        activeEngine?.close()
        super.onDestroy()
    }

    private fun logDeviceInfo() {
        val initialMem = sampleMemory()
        log("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}, ABI: ${Build.SUPPORTED_ABIS.firstOrNull()})")
        log("Initial Memory Baseline -> ${initialMem.format()}")
        log("---")
    }

    private fun checkStorageStatus() {
        val internalGh = File(filesDir, "graph-cache")
        val externalGh = getExternalFilesDir(null)?.let { File(it, "graph-cache") }

        val internalValhalla = File(filesDir, "valhalla_tiles")
        val externalValhalla = getExternalFilesDir(null)?.let { File(it, "valhalla_tiles") }

        val sb = StringBuilder()
        sb.append("Internal filesDir: ${filesDir.absolutePath}\n")

        val ghDir = if (internalGh.exists()) internalGh else externalGh
        if (ghDir != null && ghDir.exists()) {
            val sizeMb = getFolderSize(ghDir) / (1024 * 1024)
            sb.append("✓ GraphHopper data found: ${ghDir.name}/ (~${sizeMb} MB, ${ghDir.listFiles()?.size ?: 0} files)\n")
        } else {
            sb.append("✗ GraphHopper data missing (expecting 'graph-cache' dir)\n")
        }

        val vDir = if (internalValhalla.exists()) internalValhalla else externalValhalla
        if (vDir != null && vDir.exists()) {
            val sizeMb = getFolderSize(vDir) / (1024 * 1024)
            sb.append("✓ Valhalla data found: ${vDir.name}/ (~${sizeMb} MB)\n")
        } else {
            sb.append("✗ Valhalla data missing (expecting 'valhalla_tiles' dir or tiles.tar)\n")
        }

        tvStorageStatus.text = sb.toString().trim()
    }

    private fun getFolderSize(dir: File): Long {
        var size = 0L
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) getFolderSize(file) else file.length()
        }
        return size
    }

    /**
     * Samples process Total PSS from Debug.MemoryInfo and allocated native heap.
     * Replaces any Java heap measurements.
     */
    private fun sampleMemory(): MemorySnapshot {
        val memInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memInfo)
        val nativeBytes = Debug.getNativeHeapAllocatedSize()
        return MemorySnapshot(memInfo.totalPss, nativeBytes)
    }

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        tvLog.append("[$time] $message\n")
        scrollViewLog.post { scrollViewLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun getSelectedEngine(): RoutingEngine {
        val isGh = findViewById<RadioButton>(R.id.rbGraphHopper).isChecked
        return if (isGh) {
            if (activeEngine !is GhRoutingEngine) {
                activeEngine?.close()
                activeEngine = GhRoutingEngine()
            }
            activeEngine!!
        } else {
            if (activeEngine !is ValhallaRoutingEngine) {
                activeEngine?.close()
                activeEngine = ValhallaRoutingEngine(applicationContext)
            }
            activeEngine!!
        }
    }

    private fun getDataDirForEngine(engine: RoutingEngine): File {
        val dirName = if (engine is GhRoutingEngine) "graph-cache" else "valhalla_tiles"
        val internal = File(filesDir, dirName)
        if (internal.exists()) return internal
        val external = getExternalFilesDir(null)?.let { File(it, dirName) }
        if (external != null && external.exists()) return external
        return internal
    }

    private fun getSelectedRoutePair(): Pair<LatLon, LatLon> {
        return when {
            findViewById<RadioButton>(R.id.rbShort).isChecked -> Pair(SHORT_FROM, SHORT_TO)
            findViewById<RadioButton>(R.id.rbMedium).isChecked -> Pair(MED_FROM, MED_TO)
            else -> Pair(LONG_FROM, LONG_TO)
        }
    }

    private fun getSelectedAvoidPoints(): List<LatLon> {
        return when {
            findViewById<RadioButton>(R.id.rbAvoid1).isChecked -> AVOID_1
            findViewById<RadioButton>(R.id.rbAvoid10).isChecked -> AVOID_10
            else -> emptyList()
        }
    }

    private fun executeSingleRoute() {
        val engine = getSelectedEngine()
        val dataDir = getDataDirForEngine(engine)
        val (from, to) = getSelectedRoutePair()
        val avoids = getSelectedAvoidPoints()

        lifecycleScope.launch {
            setButtonsEnabled(false)
            log("=== Executing Single Route (${engine.name}) ===")
            log("From: $from -> To: $to | Avoids: ${avoids.size}")

            try {
                if (!engine.isInitialized()) {
                    log("Initializing ${engine.name} from: ${dataDir.absolutePath}")
                    val memBeforeInit = sampleMemory()
                    val initStart = System.currentTimeMillis()
                    engine.init(dataDir)
                    val initTime = System.currentTimeMillis() - initStart
                    val memAfterInit = sampleMemory()
                    val deltaPss = memAfterInit.totalPssMb - memBeforeInit.totalPssMb
                    log("✓ Initialized in ${initTime} ms (After: ${memAfterInit.format()}, Δ PSS: ${String.format(Locale.US, "%+.2f", deltaPss)} MB)")
                }

                val memBefore = sampleMemory()
                val routeStart = System.currentTimeMillis()
                val result = engine.route(from, to, RoutingProfile.PEDESTRIAN, avoids)
                val durationMs = System.currentTimeMillis() - routeStart
                val memAfter = sampleMemory()

                val deltaPss = memAfter.totalPssMb - memBefore.totalPssMb
                val deltaNative = memAfter.nativeAllocatedMb - memBefore.nativeAllocatedMb

                log("✓ Success in ${durationMs} ms")
                log("  Distance: ${String.format(Locale.US, "%.1f", result.distanceM)} m (${String.format(Locale.US, "%.2f", result.distanceM / 1000.0)} km)")
                log("  Duration: ${result.durationS} s (~${result.durationS / 60} min)")
                log("  Polyline points: ${result.polyline.size}")
                log("  Maneuvers: ${result.maneuvers.size}")
                if (result.maneuvers.isNotEmpty()) {
                    log("  First maneuver: ${result.maneuvers.first()}")
                    if (result.maneuvers.size > 1) {
                        log("  Last maneuver: ${result.maneuvers.last()}")
                    }
                }
                log("  Memory Before: ${memBefore.format()}")
                log("  Memory After : ${memAfter.format()}")
                log("  Memory Delta : Δ Total PSS: ${String.format(Locale.US, "%+.2f", deltaPss)} MB | Δ Native: ${String.format(Locale.US, "%+.2f", deltaNative)} MB")
            } catch (t: Throwable) {
                log("✗ ERROR (${t.javaClass.name}):")
                log(Log.getStackTraceString(t))
            } finally {
                setButtonsEnabled(true)
            }
        }
    }

    private fun executeBenchmarkSuite() {
        val engine = getSelectedEngine()
        val dataDir = getDataDirForEngine(engine)

        lifecycleScope.launch {
            setButtonsEnabled(false)
            log("==================================================")
            log("STARTING TEST MATRIX BENCHMARK: ${engine.name}")
            log("==================================================")

            try {
                // Pre-test cleanup & baseline memory
                engine.close()
                System.gc()
                withContext(Dispatchers.IO) { Thread.sleep(500) }

                val baselineMem = sampleMemory()
                log("Pre-benchmark Baseline: ${baselineMem.format()}")

                // 1. Cold start measurement
                log("--- Test 1: Cold Start Initialization ---")
                val diskSizeMb = getFolderSize(dataDir) / (1024 * 1024)
                log("Data dir: ${dataDir.absolutePath} (${diskSizeMb} MB on disk)")

                val memBeforeColdInit = sampleMemory()
                val initStart = System.currentTimeMillis()
                engine.init(dataDir)
                val coldInitMs = System.currentTimeMillis() - initStart
                val memAfterColdInit = sampleMemory()
                val coldInitExtraPssMb = memAfterColdInit.totalPssMb - memBeforeColdInit.totalPssMb
                log("Cold init time: ${coldInitMs} ms | Post-init: ${memAfterColdInit.format()} (Δ PSS: ${String.format(Locale.US, "%+.2f", coldInitExtraPssMb)} MB)")

                // First usable route (cold query)
                val firstQueryStart = System.currentTimeMillis()
                val firstResult = engine.route(SHORT_FROM, SHORT_TO)
                val firstRouteMs = System.currentTimeMillis() - firstQueryStart
                log("First route latency (cold): ${firstRouteMs} ms (${firstResult.polyline.size} points)")

                // 2. Route Latency Matrix (Short, Medium, Long) - 5 warm runs each
                log("--- Test 2: Route Latency Matrix (5 warm runs each) ---")
                val pairs = listOf(
                    Triple("Short (~1.1 km)", SHORT_FROM, SHORT_TO),
                    Triple("Medium (~8.2 km)", MED_FROM, MED_TO),
                    Triple("Long (~28.5 km)", LONG_FROM, LONG_TO)
                )

                var shortMedian = 0L
                var medMedian = 0L
                var longMedian = 0L

                for ((label, from, to) in pairs) {
                    val latencies = mutableListOf<Long>()
                    var lastDist = 0.0
                    for (i in 1..5) {
                        val t0 = System.currentTimeMillis()
                        val res = engine.route(from, to)
                        val elapsed = System.currentTimeMillis() - t0
                        latencies.add(elapsed)
                        lastDist = res.distanceM
                    }
                    latencies.sort()
                    val median = latencies[2]
                    val min = latencies.first()
                    val max = latencies.last()
                    log("$label: Median: ${median}ms (Min: ${min}ms, Max: ${max}ms) | Dist: ${String.format(Locale.US, "%.1f", lastDist)}m")

                    when {
                        label.startsWith("Short") -> shortMedian = median
                        label.startsWith("Medium") -> medMedian = median
                        label.startsWith("Long") -> longMedian = median
                    }
                }

                // 3. Avoidance Matrix (Medium route with 0, 1, 10 blocked points)
                log("--- Test 3: Avoidance Test (Blocked points on medium route) ---")
                val tAvoid0 = System.currentTimeMillis()
                val res0 = engine.route(MED_FROM, MED_TO, RoutingProfile.PEDESTRIAN, emptyList())
                val msAvoid0 = System.currentTimeMillis() - tAvoid0

                val tAvoid1 = System.currentTimeMillis()
                val res1 = engine.route(MED_FROM, MED_TO, RoutingProfile.PEDESTRIAN, AVOID_1)
                val msAvoid1 = System.currentTimeMillis() - tAvoid1

                val tAvoid10 = System.currentTimeMillis()
                val res10 = engine.route(MED_FROM, MED_TO, RoutingProfile.PEDESTRIAN, AVOID_10)
                val msAvoid10 = System.currentTimeMillis() - tAvoid10

                val diff1 = res1.distanceM - res0.distanceM
                val diff10 = res10.distanceM - res0.distanceM
                log("0 Avoids : Dist = ${String.format(Locale.US, "%.1f", res0.distanceM)} m in ${msAvoid0} ms")
                log("1 Avoid  : Dist = ${String.format(Locale.US, "%.1f", res1.distanceM)} m (Δ ${String.format(Locale.US, "%+.1f", diff1)} m) in ${msAvoid1} ms")
                log("10 Avoids: Dist = ${String.format(Locale.US, "%.1f", res10.distanceM)} m (Δ ${String.format(Locale.US, "%+.1f", diff10)} m) in ${msAvoid10} ms")

                val detourOccurred = diff10 > 0.0
                if (detourOccurred) {
                    log("✓ Detour confirmed: route adjusted around blocked points (Δ +${String.format(Locale.US, "%.1f", diff10)} m).")
                } else {
                    log("✗ No detour: route distance did not increase despite 10 blocked points.")
                }

                // 4. Memory Profiling (Total PSS + Native Allocated)
                log("--- Test 4: Memory Profiling (Total PSS & Native Heap) ---")
                val peakMem = sampleMemory()
                val extraTotalPssMb = peakMem.totalPssMb - baselineMem.totalPssMb
                val extraNativeMb = peakMem.nativeAllocatedMb - baselineMem.nativeAllocatedMb
                log("Baseline Memory     : ${baselineMem.format()}")
                log("Current Sampled Mem : ${peakMem.format()}")
                log("Extra Total PSS     : ${String.format(Locale.US, "%.2f", extraTotalPssMb)} MB")
                log("Extra Native Heap   : ${String.format(Locale.US, "%.2f", extraNativeMb)} MB")

                // 5. Robustness: 20 consecutive routes back-to-back
                log("--- Test 5: Robustness (20 consecutive routes back-to-back) ---")
                var errors = 0
                val robStart = System.currentTimeMillis()
                for (i in 1..20) {
                    try {
                        val r = engine.route(MED_FROM, MED_TO)
                        if (r.polyline.isEmpty()) errors++
                    } catch (e: Throwable) {
                        errors++
                        log("  Iteration #$i crashed: ${e.javaClass.simpleName} - ${e.message}")
                    }
                }
                val robTotalMs = System.currentTimeMillis() - robStart
                log("20 iterations completed in ${robTotalMs} ms (~${robTotalMs / 20} ms/op)")
                log("Crash / Failure count: $errors / 20")

                // ================================================================
                // Strict Threshold Evaluation (PASS / FAIL)
                // ================================================================
                val passColdStart = coldInitMs <= THRESHOLD_COLD_START_MS
                val passMedRoute = medMedian <= THRESHOLD_MEDIUM_ROUTE_MS
                val passLongRoute = longMedian <= THRESHOLD_LONG_ROUTE_MS
                val passExtraPss = extraTotalPssMb <= THRESHOLD_EXTRA_PSS_MB

                val unblockedLatencyBaseline = if (msAvoid0 > 0) msAvoid0 else medMedian
                val avoidanceLatencyThreshold = (THRESHOLD_AVOIDANCE_FACTOR * unblockedLatencyBaseline).toLong()
                val passAvoidance = (msAvoid10 <= avoidanceLatencyThreshold) && detourOccurred
                val passRobustness = errors == 0

                val allPass = passColdStart && passMedRoute && passLongRoute && passExtraPss && passAvoidance && passRobustness
                val passedCount = listOf(passColdStart, passMedRoute, passLongRoute, passExtraPss, passAvoidance, passRobustness).count { it }

                log("==================================================")
                log("BENCHMARK REPORT & THRESHOLD VERIFICATION: ${engine.name}")
                log("==================================================")
                log(String.format(Locale.US, "%-26s | %-16s | %-28s | %s", "CRITERION", "OBSERVED", "THRESHOLD", "STATUS"))
                log("--------------------------------------------------------------------------------------")
                log(String.format(Locale.US, "%-26s | %-16s | %-28s | %s", "1. Cold Start Init", "${coldInitMs} ms", "<= 5000 ms (5 s)", if (passColdStart) "PASS" else "FAIL"))
                log(String.format(Locale.US, "%-26s | %-16s | %-28s | %s", "2. Medium Route Latency", "${medMedian} ms (med)", "<= 3000 ms (3 s)", if (passMedRoute) "PASS" else "FAIL"))
                log(String.format(Locale.US, "%-26s | %-16s | %-28s | %s", "3. Long Route Latency", "${longMedian} ms (med)", "<= 8000 ms (8 s)", if (passLongRoute) "PASS" else "FAIL"))
                log(String.format(Locale.US, "%-26s | %-16s | %-28s | %s", "4. Extra Total PSS", String.format(Locale.US, "%.1f MB", extraTotalPssMb), "<= 300 MB", if (passExtraPss) "PASS" else "FAIL"))
                log(String.format(Locale.US, "%-26s | %-16s | %-28s | %s", "5. 10-Pt Avoidance Detour", "${msAvoid10}ms (+${String.format(Locale.US, "%.0f", diff10)}m)", "<= 2x base (${avoidanceLatencyThreshold}ms) & detour", if (passAvoidance) "PASS" else "FAIL"))
                log(String.format(Locale.US, "%-26s | %-16s | %-28s | %s", "6. Robustness (20 runs)", "$errors failures", "0 crashes / failures", if (passRobustness) "PASS" else "FAIL"))
                log("--------------------------------------------------------------------------------------")
                log("OVERALL RESULT: ${if (allPass) "ALL PASS (Candidate Meets Acceptance Criteria)" else "FAIL ($passedCount/6 criteria met)"}")
                log("==================================================")
            } catch (t: Throwable) {
                log("✗ BENCHMARK FAILED WITH CRITICAL EXCEPTION (${t.javaClass.name}):")
                log(Log.getStackTraceString(t))
            } finally {
                setButtonsEnabled(true)
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        btnRunSingle.isEnabled = enabled
        btnRunBenchmark.isEnabled = enabled
    }
}
