# ResQNet mesh-chat prototype

ResQNet is an infrastructure-independent Android group chat. Nearby phones discover one another over Bluetooth Low Energy, exchange signed messages through GATT, and retain messages for later store-and-forward synchronization.

## Prototype capabilities

- Shared `local-emergency` text channel with a 500-byte message limit.
- P-256 ECDSA installation identity backed by Android Keystore.
- BLE advertising, scanning, bidirectional GATT sessions, MTU-aware chunking, acknowledgements, retry-safe inventories, and deduplication.
- Room-backed messages, peers, delivery state, and origin sequence.
- Six-hop TTL, 24-hour propagation window, seven-day history, and 2,000-message retention cap.
- User-started connected-device foreground service and clear `Queued`, `Relayed`, and `Received` states.
- Debug-only A ↔ B ↔ C topology filter for a repeatable three-phone relay demonstration.

Messages are signed but not encrypted. A fingerprint identifies an installation; it does not prove a person's real-world identity.

## Build

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

## Developer Diagnostics & UI Preview (Mock Data)

ResQNet includes an isolated developer diagnostic suite for UI previewing and offline testing without requiring multiple physical mesh nodes:

- **Accessing Diagnostics**:
  - **In-App**: Navigate to the **Mesh** tab in the bottom bar and tap **"Open Diagnostics & Mock Data"**.
  - **Via ADB**: `adb shell am start -n com.resqnet.app/.ui.DiagnosticsActivity`
- **Mock Data Tooling**:
  - **Load Mock Data**: Populates local Room SQLite database (`resqnet.db`) with realistic broadcast messages, trusted & pending contacts, active emergency circles (*Alpha Evac Squad*, *Sector 4 Community Shelter*), circle chat messages, and nearby peers.
  - **Clear Data**: Safely and surgically purges mock records (identifiable by the `mock-` prefix), leaving real user identities, contacts, and circles intact.
  - **Non-Invasive**: Operates purely at the local presentation/database observation layer without modifying or disrupting BLE mesh networking, cryptography, or message routing.

## Architecture

```text
Activities / ChatViewModel
          |
MessageRouter ---- AndroidIdentitySigner
     |                    |
Room repositories     Android Keystore
     |
MeshCoordinator
     |
BleMeshTransport -- GATT chunk/session protocol
```

`MessageRouter` has no dependency on BLE. A future Wi-Fi Direct transport or SOS message type can reuse the protocol validation, identity, persistence, and synchronization layers.

See [docs/DEMO_RUNBOOK.md](docs/DEMO_RUNBOOK.md) for the three-device acceptance flow and [docs/PROTOCOL.md](docs/PROTOCOL.md) for the wire contract.

## Navigation (Navigate Tab)

The Navigate tab provides offline-capable, turn-by-turn evacuation routing to verified and community-verified temporary shelters loaded from the installed map pack.

### Features

1. **Single-session guard** — Tapping *Start* when a navigation session is already active prompts the user to keep the current session or stop and restart, preventing silent duplicate foreground services.

2. **Personal pins with amber markers** — Long-pressing the map places start and destination pins rendered as amber (#F2B544) filled circles with a dark border, visually distinct from the grey shelter markers. A *Clear Pins* mini-FAB (bottom-right stack) removes all user-placed pins, resets the route, and restores the GPS location as the start point when one is available.

3. **Zoom-to-fit route button** — After a route is computed a *Zoom Fit* mini-FAB appears. Tapping it calculates the bounding box of the entire route geometry (start pin + all waypoints + destination) and animates the camera to show the full route with 80 dp padding via `CameraUpdateFactory.newLatLngBounds`.

4. **Integrated turn guidance with dynamic arrows** — Rather than cluttering the map with a floating maneuver pill, live turn guidance and directional arrows for all maneuver types (straight ⬆️, turn right ➡️, turn left ⬅️, sharp right ⮠, sharp left ⮡, slight right ↗️, slight left ↖️, roundabout/u-turn 🔄, arrived 🏁, ramp/fork 🔀) are integrated directly inside the bottom card alongside live distance/time countdowns and the dynamic Start/Stop Navigation button.

5. **High-quality offline Text-to-Speech** — Targets Google Speech Services (`com.google.android.tts`) prioritizing offline installed voices (`!isNetworkConnectionRequired`), speech rate (0.95x), pitch tuning, and clean speech sanitization that strips emojis and expands distance/street abbreviations before speaking.

6. **Google Maps-style custom live notification** — Implements a custom dark card notification (`RemoteViews`) matching Google Maps navigation:
   - Left: Circular white location pin badge.
   - Center: Real-time turn instruction and distance (e.g. `200 m · Turn right onto Station Rd`) plus estimated arrival time (`Arrive 9:34 pm`).
   - Right: Bold directional maneuver turn arrow.
   - Middle: Horizontal progress bar tracking route completion.
   - Bottom: Full-width dark rounded **Exit navigation** button allowing one-tap cancellation without opening the app.
   - Ongoing, non-dismissible (`setOngoing(true)` and `FOREGROUND_SERVICE_IMMEDIATE`).

7. **Floating window (Picture-in-Picture) mode** — When navigation is active, exiting or swiping to Home automatically transitions `NavigateActivity` into a Google Maps-style floating Picture-in-Picture window (`android:supportsPictureInPicture="true"`). Toolbars and buttons cleanly collapse, leaving an unobstructed live map and turn guidance overlay.

8. **Clean active glow on bottom navbar** — While navigation is active, the Navigate tab in the bottom bar displays a clean, non-gradient active amber outline ring and status badge across every screen in the app.

9. **High-visibility Google Maps location puck** — Replaced the small static marker with a 56 dp density-scaled navigation puck featuring a soft pulsing blue halo, directional compass flashlight cone (`headingDegrees`), crisp white elevation ring, vibrant Google Blue core, and center pinpoint.

10. **Shelter list bottom sheet** — A `FloatingActionButton` (house icon, resq_primary) appears on the right edge of the map when shelters are loaded. Tapping it opens a `BottomSheetDialog` containing a `RecyclerView` with verified/unverified badges, addresses, and instant routing selection.

11. **Chat coordinate deep-linking with nearby area lookup** — Tapping any coordinate link in chat or circle messages brings the existing `NavigateActivity` to the foreground without spawning duplicate instances. It identifies the nearby area and closest shelter (both offline and online). If a navigation session is already active, it shows a dialog asking whether to stop and navigate to the new coordinate, view it on the map, or keep the current session.

