# ResQNet — On-Device Routing Engine Spike (Evaluation Guide)

This document details the engineering evaluation spike comparing **GraphHopper (v9.1)** and **Valhalla (v3.6.3 via valhalla-mobile 0.6.1)** for fully offline, on-device pedestrian routing in ResQNet.

---

## 1. Context & Objectives

During catastrophic disasters, cellular base stations fail, power grids blackout, and cloud services (Google Maps, Mapbox, OSRM servers) become unreachable. ResQNet operates over a peer-to-peer BLE mesh. For navigation to relief shelters, first aid, and circle members, the routing engine must:

1. Run **100% on-device** (zero network packets, airplane mode).
2. Have **no Google Play Services** dependency.
3. Compute routes over the **Mumbai Metropolitan Region (MMR)** (bounding box: `72.70, 18.85` to `73.10, 19.60`, covering Vasai-Virar, Mira-Bhayandar, Thane, and Mumbai).
4. Default to a **pedestrian profile** (`foot`) suited for navigating flooded streets, blocked corridors, and footpaths.
5. Support **dynamic obstacle avoidance** to route around reported flooded intersections and collapsed structures.

---

## 2. Architecture & Design

Both candidates are implemented behind a unified, decoupled interface:

```kotlin
interface RoutingEngine {
    val name: String
    suspend fun init(dataDir: File)
    suspend fun route(
        from: LatLon,
        to: LatLon,
        profile: RoutingProfile = RoutingProfile.PEDESTRIAN,
        avoidPoints: List<LatLon> = emptyList()
    ): RouteResult
    fun isInitialized(): Boolean
    fun close()
}
```

### Candidates Under Test

| Metric / Aspect | Candidate A: GraphHopper | Candidate B: Valhalla |
|---|---|---|
| **Version** | `com.graphhopper:graphhopper-core:9.1` | `io.github.rallista:valhalla-mobile:0.6.1` (Valhalla 3.6.3) |
| **Engine Core** | Pure Java (Memory-mapped via `MMAP`) | C++ native library (`.so` compiled for arm64-v8a, armeabi-v7a, x86_64) |
| **Data Format** | Graph cache folder (`nodes`, `edges`, `geometry`, etc.) | Tiled hierarchy (`.tar` archive or hierarchical directory) |
| **Avoidance Mechanism** | `CustomModel` with spatial priority multiplier = 0 (disabling CH) | `exclude_locations` / `avoid_locations` in costing options |
| **Memory Footprint** | Java Heap + Direct NIO MMAP | Native C++ memory (outside Android JVM heap limits) |

---

## 3. Data Preparation Pipeline

> **IMPORTANT**: Run these data preparation steps on a development PC or server (Linux/macOS or WSL2 on Windows). **Never** attempt to parse raw OSM PBF data on an Android device.

### Step 1: Acquire Source OSM Data

Download the Western Zone extract of India from Geofabrik:

```bash
mkdir -p ~/resqnet-data && cd ~/resqnet-data
wget https://download.geofabrik.de/asia/india/western-zone-latest.osm.pbf
```

### Step 2: Clip to Mumbai Metropolitan Region (MMR)

Using `osmium-tool` (install via `sudo apt install osmium-tool` or `brew install osmium-tool`):

```bash
# Bounding box: min_lon=72.70, min_lat=18.85, max_lon=73.10, max_lat=19.60
osmium extract \
  --bbox 72.70,18.85,73.10,19.60 \
  --set-bounds \
  western-zone-latest.osm.pbf \
  -o mmr-pedestrian.osm.pbf
```

*Expected output: ~45–75 MB PBF file.*

---

### Step 3A: Build GraphHopper Graph Cache

GraphHopper requires pre-building the routing graph with a pedestrian profile. We provide two configurations:

#### Option 1: Contraction Hierarchies (CH - Speed Mode)
Fastest query time (<10ms), but requires disabling CH for custom obstacle avoidance.

Create `config-ch.yml`:
```yaml
graphhopper:
  datareader.file: mmr-pedestrian.osm.pbf
  graph.location: ./graph-cache
  graph.dataaccess: MMAP
  profiles:
    - name: foot
      vehicle: foot
      weighting: shortest
  profiles_ch:
    - profile: foot
```

#### Option 2: Flexible Mode with Custom Model (Avoidance-Ready)
Allows dynamic runtime obstacle avoidance without pre-calculated CH shortcuts.

Create `config-flexible.yml`:
```yaml
graphhopper:
  datareader.file: mmr-pedestrian.osm.pbf
  graph.location: ./graph-cache
  graph.dataaccess: MMAP
  profiles:
    - name: foot
      vehicle: foot
      weighting: custom
      custom_model_files: []
  profiles_ch: []
```

#### Run GraphHopper Import (requires Java 17+):
```bash
# Download GraphHopper Web JAR v9.1
wget https://repo1.maven.org/maven2/com/graphhopper/graphhopper-web/9.1/graphhopper-web-9.1.jar

# Run import to generate graph-cache
java -Xmx4g -jar graphhopper-web-9.1.jar import config-ch.yml
```

*Expected output: A directory named `graph-cache/` (~25–50 MB) containing files like `nodes`, `edges`, `geometry`, `properties`, and `location_index`.*

---

### Step 3B: Build Valhalla Routing Tiles

Valhalla uses hierarchical routing tiles. To guarantee format compatibility with `valhalla-mobile 0.6.1`, use the official Valhalla 3.6.3 Docker image.

```bash
# 1. Create a minimal Valhalla config
docker run --rm -v $(pwd):/data ghcr.io/valhalla/valhalla:3.6.3 \
  valhalla_build_config \
  --mjolnir-tile-dir /data/valhalla_tiles \
  --mjolnir-tile-extract /data/valhalla_tiles.tar \
  --mjolnir-concurrency 2 \
  > valhalla.json

# 2. Build the routing tiles from the clipped PBF
docker run --rm -v $(pwd):/data ghcr.io/valhalla/valhalla:3.6.3 \
  valhalla_build_tiles \
  -c /data/valhalla.json \
  /data/mmr-pedestrian.osm.pbf

# 3. Package tiles into tar archive for compact mobile storage
docker run --rm -v $(pwd):/data ghcr.io/valhalla/valhalla:3.6.3 \
  valhalla_build_extract \
  -c /data/valhalla.json
```

*Expected output: `valhalla_tiles.tar` (~20–40 MB) and `valhalla.json`.*

---

## 4. Deploying Test Data to Device via ADB

Connect your Android phone via USB with USB Debugging enabled.

### Option A: App Internal Storage (`filesDir` - Recommended for MMAP performance)

```bash
# GraphHopper deployment
adb shell "mkdir -p /data/user/0/com.resqnet.app/files/graph-cache"
adb push ./graph-cache/. /data/user/0/com.resqnet.app/files/graph-cache/

# Valhalla deployment
adb shell "mkdir -p /data/user/0/com.resqnet.app/files/valhalla_tiles"
adb push ./valhalla_tiles.tar /data/user/0/com.resqnet.app/files/valhalla_tiles/tiles.tar
adb push ./valhalla.json /data/user/0/com.resqnet.app/files/valhalla_tiles/valhalla.json
```

### Option B: App External Files Dir (`getExternalFilesDir` - No root required)

```bash
# GraphHopper
adb push ./graph-cache /sdcard/Android/data/com.resqnet.app/files/graph-cache

# Valhalla
adb push ./valhalla_tiles /sdcard/Android/data/com.resqnet.app/files/valhalla_tiles
```

---

## 5. Running the Spike on Device

### Launching SpikeActivity

Run the following command in terminal or launch directly from Android Studio:

```bash
adb shell am start -n com.resqnet.app/.ui.SpikeActivity
```

### In-App UI Controls

1. **Storage Status Card**: Shows whether `graph-cache` and `valhalla_tiles` are detected on device, file counts, and disk size.
2. **Engine Selector**: Toggle between **GraphHopper 9.1** and **Valhalla 3.6.3**.
3. **Route Pair Selector**:
   - **Short (~1.1 km)**: Vasai Road Station East (`19.3828, 72.8319`) to Manikpur Cross (`19.3750, 72.8240`).
   - **Medium (~8.2 km)**: Vasai Road Station (`19.3828, 72.8319`) to Arnala Beach (`19.4530, 72.7750`).
   - **Long (~28.5 km)**: Vasai-Virar (`19.3900, 72.8300`) to Borivali Station (`19.2288, 72.8541`).
4. **Obstacle Avoidance Selector**:
   - `0 Points`: Direct baseline route.
   - `1 Point`: Flooded point placed directly in the primary corridor.
   - `10 Points`: 10 flooded/debris points scattered along the corridor.
5. **Action Buttons**:
   - **Run Single Route**: Computes the selected configuration and outputs coordinates, distance, duration, turn maneuvers, memory delta, and execution time.
   - **Run Test Matrix**: Runs the automated 5-phase test benchmark suite and prints the complete evaluation report.
   - **Copy / Clear**: Copies the entire output log for archiving.

---

## 6. Test Matrix & Evaluation Protocol

Before running tests, ensure:
- Device is in **Airplane Mode** (Wi-Fi, Bluetooth, and Mobile Data switched OFF).
- Close background applications.
- Note device model, processor, and total RAM.

### Evaluation Criteria

| Test Phase | Metric | What to Observe |
|---|---|---|
| **1. Cold Start** | Time from `init()` to first route (ms); Size on disk (MB) | Measures app startup penalty and storage requirement. |
| **2. Route Latency** | Short, Medium, Long (First run + 5 warm-up runs, median ms) | Responsiveness on pedestrian routing. |
| **3. Avoidance Detour** | Distance increase ($\Delta$ meters) & Latency with 1 and 10 avoid points | Verifies that engine successfully steers around blocked points. |
| **4. Peak Memory** | JVM Heap in use (MB) & Process PSS (MB) | Checks for risk of Android low-memory killer (LMK). |
| **5. Robustness** | 20 consecutive routes back-to-back | Verifies zero crashes, zero native segmentation faults, zero leaks. |

---

## 7. Results Recording Template

```markdown
### Benchmark Environment
- **Device Model**: [e.g. Samsung Galaxy A54 / Xiaomi Redmi Note 12 / Pixel 7a]
- **Processor**: [e.g. Exynos 1380 / Snapdragon 4 Gen 1 / Tensor G2] (arm64-v8a)
- **RAM**: [e.g. 6 GB / 8 GB]
- **OS**: Android [e.g. 13 / 14]
- **Network**: Airplane Mode ON

### Results Summary

| Metric | GraphHopper 9.1 | Valhalla 3.6.3 | Winner / Notes |
|---|---|---|---|
| **On-Disk Size** | ____ MB | ____ MB | |
| **Cold Init Time** | ____ ms | ____ ms | |
| **Cold First Query** | ____ ms | ____ ms | |
| **Short Route (~1.1 km) Median** | ____ ms | ____ ms | |
| **Medium Route (~8.2 km) Median** | ____ ms | ____ ms | |
| **Long Route (~28.5 km) Median** | ____ ms | ____ ms | |
| **Avoidance 1 Point** | $\Delta$ +____ m in ____ ms | $\Delta$ +____ m in ____ ms | |
| **Avoidance 10 Points** | $\Delta$ +____ m in ____ ms | $\Delta$ +____ m in ____ ms | |
| **JVM Heap Peak** | ____ MB | ____ MB | |
| **Process PSS Peak** | ____ MB | ____ MB | |
| **Robustness (20 runs)** | __ / 20 pass | __ / 20 pass | |
```

---

## 8. Recommendation Criteria for Final Implementation

1. **Memory Safety**: If GraphHopper causes `OutOfMemoryError` or virtual memory allocation failures during long-route routing or cold start on 4GB/6GB RAM devices, Valhalla is preferred due to native memory handling.
2. **Avoidance Capability**: If CH-mode GraphHopper fails to reroute around flooded points without significant latency penalties (>500ms), Valhalla's dynamic costing offers superior real-time rerouting.
3. **Storage vs CPU**: If Valhalla's `.tar` extract is smaller and faster to initialize than GraphHopper's uncompressed graph folder, choose Valhalla.
