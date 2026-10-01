# ResQNet — Routing Engine Spike Results & Evaluation

This document provides the benchmark recording matrix and integration cost analysis for the on-device routing engine candidates (**GraphHopper 9.1** vs. **Valhalla 3.6.3**).

> **NOTE**: All data in this file is to be populated directly from physical on-device measurements using `SpikeActivity` on an ARM64 Android device in Airplane Mode.

---

## 1. Test Environment & Hardware Specification

| Attribute | Device Under Test |
|---|---|
| **Manufacturer & Model** | `[To be recorded on device]` |
| **Android Version / API** | `[To be recorded on device]` |
| **SoC / Chipset** | `[To be recorded on device]` |
| **ABI Architecture** | `[e.g. arm64-v8a]` |
| **Total System RAM** | `[e.g. 6 GB / 8 GB]` |
| **Storage Type** | `[e.g. UFS 2.2 / UFS 3.1]` |
| **Network State** | Airplane Mode ON (Cellular, Wi-Fi, Bluetooth Data all OFF) |
| **App Build** | `ResQNet debug (git branch: spike/routing-engine)` |

---

## 2. Evaluation Summary & Acceptance Criteria

Thresholds are fixed and strictly evaluated against project requirements:

| # | Evaluation Criterion | Acceptance Threshold | GraphHopper 9.1 Observed | GH Status | Valhalla 3.6.3 Observed | Valhalla Status |
|---|---|---|---|---|---|---|
| **1** | **Cold Start Init** | $\le$ 5,000 ms (5.0 s) | `_____ ms` | `[PASS / FAIL]` | `_____ ms` | `[PASS / FAIL]` |
| **2** | **Medium Route Latency** | $\le$ 3,000 ms (3.0 s) | `_____ ms` | `[PASS / FAIL]` | `_____ ms` | `[PASS / FAIL]` |
| **3** | **Long Route Latency** | $\le$ 8,000 ms (8.0 s) | `_____ ms` | `[PASS / FAIL]` | `_____ ms` | `[PASS / FAIL]` |
| **4** | **Extra Total PSS** | $\le$ 300.0 MB | `_____ MB` | `[PASS / FAIL]` | `_____ MB` | `[PASS / FAIL]` |
| **5** | **10-Point Avoidance** | $\le 2\times$ unblocked latency AND route detours ($\Delta$ dist > 0) | `_____ ms` ($\Delta$ `____ m`) | `[PASS / FAIL]` | `_____ ms` ($\Delta$ `____ m`) | `[PASS / FAIL]` |
| **6** | **Robustness (20 runs)** | 0 crashes / 0 failures | `___ / 20 failures` | `[PASS / FAIL]` | `___ / 20 failures` | `[PASS / FAIL]` |

**Overall Recommendation**: `[Pending Device Run]`

---

## 3. Detailed Benchmark Data

### 3.1 Cold Start & On-Disk Footprint

*Measured on first launch after process kill, reading from device storage (`filesDir` or `getExternalFilesDir`).*

| Metric | GraphHopper 9.1 | Valhalla 3.6.3 | Delta / Ratio |
|---|---|---|---|
| **Data Directory Path** | `graph-cache/` | `valhalla_tiles/` | — |
| **Total Size on Disk** | `_____ MB` | `_____ MB` | `Valhalla / GH = ____` |
| **Total File Count** | `_____ files` | `_____ files` | — |
| **Cold Initialization Time** | `_____ ms` | `_____ ms` | — |
| **Cold First Query (Short)** | `_____ ms` | `_____ ms` | — |
| **Pre-init Total PSS** | `_____ MB` | `_____ MB` | — |
| **Post-init Total PSS** | `_____ MB` | `_____ MB` | — |
| **Cold Init Extra PSS ($\Delta$)** | `_____ MB` | `_____ MB` | — |

---

### 3.2 Route Latency Matrix (5 Warm Runs)

*Test coordinates within Mumbai Metropolitan Region (Vasai-Virar corridor).*
*Each route executed 5 times consecutively after cold query; median taken as representative latency.*

#### A) Short Pair (~1.1 km): Vasai Station East (`19.3828, 72.8319`) $\to$ Manikpur (`19.3750, 72.8240`)
| Metric | GraphHopper 9.1 | Valhalla 3.6.3 |
|---|---|---|
| Computed Distance | `_____ m` | `_____ m` |
| Estimated Duration | `_____ s` | `_____ s` |
| Polyline Points Count | `_____` | `_____` |
| Maneuvers Count | `_____` | `_____` |
| Run 1 / Run 2 / Run 3 / Run 4 / Run 5 | `__ / __ / __ / __ / __ ms` | `__ / __ / __ / __ / __ ms` |
| **Median Latency** | `_____ ms` | `_____ ms` |
| Min / Max Latency | `_____ ms / _____ ms` | `_____ ms / _____ ms` |

#### B) Medium Pair (~8.2 km): Vasai Station (`19.3828, 72.8319`) $\to$ Arnala Beach (`19.4530, 72.7750`)
| Metric | GraphHopper 9.1 | Valhalla 3.6.3 |
|---|---|---|
| Computed Distance | `_____ m` | `_____ m` |
| Estimated Duration | `_____ s` | `_____ s` |
| Polyline Points Count | `_____` | `_____` |
| Maneuvers Count | `_____` | `_____` |
| Run 1 / Run 2 / Run 3 / Run 4 / Run 5 | `__ / __ / __ / __ / __ ms` | `__ / __ / __ / __ / __ ms` |
| **Median Latency** | `_____ ms` | `_____ ms` |
| Min / Max Latency | `_____ ms / _____ ms` | `_____ ms / _____ ms` |

#### C) Long Pair (~28.5 km): Vasai-Virar (`19.3900, 72.8300`) $\to$ Borivali Station (`19.2288, 72.8541`)
| Metric | GraphHopper 9.1 | Valhalla 3.6.3 |
|---|---|---|
| Computed Distance | `_____ m` | `_____ m` |
| Estimated Duration | `_____ s` | `_____ s` |
| Polyline Points Count | `_____` | `_____` |
| Maneuvers Count | `_____` | `_____` |
| Run 1 / Run 2 / Run 3 / Run 4 / Run 5 | `__ / __ / __ / __ / __ ms` | `__ / __ / __ / __ / __ ms` |
| **Median Latency** | `_____ ms` | `_____ ms` |
| Min / Max Latency | `_____ ms / _____ ms` | `_____ ms / _____ ms` |

---

### 3.3 Dynamic Obstacle Avoidance (Flooded Intersections)

*Tested on the Medium Route (`Vasai Station -> Arnala Beach`).*
- **1 Blocked Point**: `19.4180, 72.8050` (Gas/Nalasopara Link Rd).
- **10 Blocked Points**: 10 coordinates placed along the primary corridor.

| Configuration | GraphHopper 9.1 Distance | GH Latency | Valhalla 3.6.3 Distance | Valhalla Latency |
|---|---|---|---|---|
| **0 Avoids (Baseline)** | `_____ m` | `_____ ms` | `_____ m` | `_____ ms` |
| **1 Avoid Point** | `_____ m` ($\Delta$ `___ m`) | `_____ ms` | `_____ m` ($\Delta$ `___ m`) | `_____ ms` |
| **10 Avoid Points** | `_____ m` ($\Delta$ `___ m`) | `_____ ms` | `_____ m` ($\Delta$ `___ m`) | `_____ ms` |
| **Detour Confirmed ($\Delta > 0$)?** | `[YES / NO]` | — | `[YES / NO]` | — |
| **Latency Factor ($\le 2.0\times$)?** | `___ x baseline` | `[PASS / FAIL]` | `___ x baseline` | `[PASS / FAIL]` |

---

### 3.4 Memory Footprint (Debug.MemoryInfo Total PSS + Native Allocated)

*All measurements capture operating system Total PSS (proportional set size) and Native Allocated Heap, replacing Java-heap approximations.*

| State / Operation | GraphHopper 9.1 (PSS / Native) | Valhalla 3.6.3 (PSS / Native) |
|---|---|---|
| **Baseline (Pre-Benchmark)** | `____ MB / ____ MB` | `____ MB / ____ MB` |
| **Post-Initialization** | `____ MB / ____ MB` | `____ MB / ____ MB` |
| **Peak During Long Routing** | `____ MB / ____ MB` | `____ MB / ____ MB` |
| **Peak During 10-Pt Avoidance**| `____ MB / ____ MB` | `____ MB / ____ MB` |
| **Maximum Extra PSS ($\Delta$)** | `____ MB` | `____ MB` |

---

### 3.5 Robustness & Process Stability

*20 consecutive route executions back-to-back without garbage collection pauses or thread termination.*

| Test | GraphHopper 9.1 | Valhalla 3.6.3 |
|---|---|---|
| 20 Back-to-Back Queries | `___ / 20 succeeded` | `___ / 20 succeeded` |
| Total Execution Time (20 runs) | `_____ ms` | `_____ ms` |
| Average Time Per Query | `_____ ms` | `_____ ms` |
| Process Kill & Relaunch Recovery | `[PASS / FAIL]` | `[PASS / FAIL]` |
| Unhandled Exceptions / Crashes | `0` (or list exceptions) | `0` (or list exceptions) |

---

## 4. Integration Cost Analysis

### 4.1 Valhalla: Bridge Reliance on Library Internals

In `ValhallaRoutingEngine.kt`, the spike relies on [`ValhallaBridge.java`](file:///c:/Users/vinis/AndroidStudioProjects/ResQNet/app/src/main/java/com/resqnet/app/navigation/spike/ValhallaBridge.java) to interface with the native C++ engine bundled in `io.github.rallista:valhalla-mobile:0.6.1`.

#### Technical Debt & Visibility Mechanics:
1. **Kotlin Internal Scope**: In the `valhalla-mobile` AAR artifact, the primary JNI binding class `com.valhalla.valhalla.ValhallaKotlin` and actor `ValhallaActor` are marked with Kotlin `internal` visibility.
2. **Bytecode Linkage**: In JVM bytecode, Kotlin `internal` modifiers compile to `public` methods (with metadata flags). Kotlin callers in separate Gradle modules are restricted by the compiler, but standard Java source files (`ValhallaBridge.java`) can directly invoke `new ValhallaKotlin()`, `createActor(configPath)`, `route(handle, json)`, and `deleteActor(handle)`.
3. **Maintenance Risk**:
   - **Upstream Renaming**: If a future patch of `valhalla-mobile` refactors internal class names, changes native JNI signatures, or alters memory management, `ValhallaBridge.java` will fail at link or runtime.
   - **Obfuscation / Minification**: If ProGuard / R8 rules are applied upstream to package-private symbols, the bridge will break with `NoSuchMethodError` or `NoClassDefFoundError`.
4. **Long-Term Production Mitigation**:
   - If Valhalla is chosen as the winner of this spike, ResQNet should **not** rely on `valhalla-mobile`'s internal classes for production release.
   - Instead, the app should directly compile a targeted, lightweight C++ JNI bridge against Valhalla's open-source C++ core using Android NDK (CMake), or fork `valhalla-mobile` to maintain public, stable, version-pinned bindings.

### 4.2 GraphHopper: Java 17 Compatibility & Virtual Memory Footprint

1. **Public API Stability**: GraphHopper (`graphhopper-core:9.1`) provides standard, public Java interfaces (`GraphHopper`, `GHRequest`, `CustomModel`). It does not require visibility bridges.
2. **Java 17 & Desugaring Requirement**: GraphHopper 9.1 requires bytecode compatibility with Java 17. The Android build was upgraded to `JavaVersion.VERSION_17` and desugaring dependencies enabled.
3. **Virtual Memory & MMAP**: While Java heap usage is kept moderate by memory-mapped files (`MMAP`), Android devices enforce strict contiguous virtual address limits per process. On devices with high address space fragmentation or 32-bit runtimes, large graph caches can trigger `OutOfMemoryError` on native `mmap` calls even if physical RAM is available.

---

## 5. Decision Log

*To be completed after running tests on physical device:*

- **Selected Engine**: `[GraphHopper 9.1 / Valhalla 3.6.3]`
- **Primary Justification**: `[Summary of latency, memory stability, and avoidance behavior]`
- **Approved Implementation Path**: `[Direct NDK build / Pinned graphhopper-core]`
