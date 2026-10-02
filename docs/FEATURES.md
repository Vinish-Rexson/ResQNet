# ResQNet feature status

Last verified against commit `2660832286741a37620d62514be849a8569f4fcc`, 2026-10-02. The working tree was also inspected and contains uncommitted BARP and mesh changes; those changes are identified explicitly below and are not represented by that commit hash.

Status values used here are exactly: `Implemented and device-verified`, `Implemented, compiled and unit-tested only`, `Partial`, `Stub or fixture`, and `Not started`.

## Summary

| Feature | Status | Branch / commit location | Verification |
|---|---|---|---|
| BLE GATT transport, sessions, chunking, relay, TTL, dedup, A-B-C filter, foreground service | Implemented, compiled and unit-tested only | `main`: `cc05544`, `8243103`, `89c99cc`, `82d80e4`; current relay/UI changes are uncommitted | JVM unit tests pass; no recorded three-device run |
| RQP2 packet kinds and relay policies | Implemented, compiled and unit-tested only | `main`: `89c99cc`, `23caaa9` | `ProtocolCodecTest`, `MessageRouterTest` pass |
| Identity, Android Keystore signing, verification | Implemented, compiled and unit-tested only | `main`: `cc05544`, `89c99cc` | Protocol/router tests use JVM signers; no recorded Android Keystore device run |
| Encryption | Not started | No commit or source evidence for AES-GCM | README explicitly says messages are signed but not encrypted |
| Contacts, direct messages, delivery states | Implemented, compiled and unit-tested only | `main`: `b087967`, `56453c7`, `4693e69`, `23caaa9` | Contact and direct-message tests pass |
| Family Circles, snapshots, safety status | Implemented, compiled and unit-tested only | `main`: `a9faf2a`, `23caaa9`, `bfd7b9a` | `CircleRouterTest` passes; no device record |
| Room persistence | Implemented, compiled and unit-tested only | `main`: `cc05544` through `2128768`; database version 6 in current tree | `DatabaseVersionPolicyTest` passes |
| UI screens and bottom navigation | Partial | `main`: `59ed154`, `4170910`, `7388799`, `ad1c906`, `18d140d`, `11fed70` | Compiled; no UI/instrumentation run recorded |
| Offline navigation, Valhalla, pack download/import, map, shelters, live guidance | Partial | Spike branch `spike/routing-engine` through `60c98c1`, merged by `966517c`; later main commits `b6c2055`, `83d4da3`, `2128768`, `11fed70` | Pack/mapper/progress unit tests pass; no device navigation record |
| Basemap and shelter data in bundled Mumbai pack | Stub or fixture | `main`: `60c98c1` and `app/src/main/assets/mumbai-demo-pack.zip` | Synthetic basemap and explicitly unverified test shelter; not real emergency data |
| BARP battery-aware relay stages | Implemented, compiled and unit-tested only | Current `main` working tree only; no commit yet | Four BARP JVM tests pass; battery measurement procedure is unrun |
| Routing spike: GraphHopper vs Valhalla | Partial | `spike/routing-engine`: `a3162c5`-`60c98c1`; mirrored/merged main history; production adapter on main | All recorded device metrics are not measured |
| Hazard reporting and route-avoidance plumbing | Partial | `main`: `2128768`, `2660832` | Geometry/unit tests pass; Valhalla avoidance has not been device-validated |

## Branch and merge audit

`git branch -a` found only `main`, `spike/routing-engine`, and their `origin/*` counterparts. `spike/routing-engine` points at `60c98c1` and is an ancestor of `main`; its navigation work was merged by `966517c`. `origin/main` and `main` both point at `2660832` at audit time. No other feature branch was present.

Feature commits before the routing spike are on `main`, beginning with `cc05544` for the mesh foundation, `89c99cc` for typed RQP2, `b087967` for contacts/direct messages, and `a9faf2a` for circles. The current BARP edits are uncommitted changes in the `main` worktree, not a branch or commit.

## 1. BLE GATT mesh transport and relay

Description: Nearby Android devices advertise and scan a ResQNet service, create bidirectional GATT client/server sessions, exchange framed data, and use store-and-forward relay.

Status: `Implemented, compiled and unit-tested only`.

Evidence: `app/src/main/java/com/resqnet/app/mesh/ble/BleMeshTransport.kt` implements advertising, scanning, GATT server/client callbacks, MTU negotiation, notification/write queues, reconnect backoff, and the debug A-B-C link filter. `ChunkCodec.kt` splits and reassembles frames. `MeshCoordinator.kt` performs HELLO, inventories, requests, packet ingest, ACKs, and relay. `MeshService.kt` owns the user-started connected-device foreground service. `docs/PROTOCOL.md` and `docs/DEMO_RUNBOOK.md` describe the wire/session and three-phone flow. Commits: `cc05544`, `8243103`, `89c99cc`, `82d80e4`.

Verification: `ChunkCodecTest`, `MessageRouterTest`, and `ProtocolCodecTest` passed in `testDebugUnitTest` on 2026-10-02. `docs/DEMO_RUNBOOK.md` is an acceptance procedure, not a recorded result. No repository record names a device model or records the A-B-C demo, airplane-mode run, relay latency, or foreground-service run; therefore this is not device-verified.

Known limitations: No Wi-Fi Direct transport exists. The A-B-C topology is debug-only. BLE behavior, OEM background limits, Android Keystore behavior, and three-phone multi-hop delivery remain unverified in the repository.

## 2. RQP2 packet kinds and relay policies

Description: Version 2 payloads are typed, audience-scoped, signed packets wrapped in mutable relay envelopes.

Status: `Implemented, compiled and unit-tested only`.

Evidence: `Models.kt` defines 13 packet kinds: public text, contact request/accept/decline, direct text, delivery receipt, circle invite/accept/decline, membership snapshot, circle text/status, and leave request. It defines `PUBLIC_CHANNEL`, `DIRECT_NODE`, and `CIRCLE` audiences; `EPHEMERAL`, `DURABLE_UNTIL_SUPERSEDED`, and `DURABLE_UNTIL_RESOLVED` relay policies; TTL 6, max hops 6, 24-hour ephemeral propagation, seven-day history, and 10,000 retained messages. `ProtocolCodec.kt` encodes/decodes the RQP2/RQS2/RQE2/RQF2 wire formats and rejects malformed bounds/trailing data. `docs/PROTOCOL.md` is the matching wire contract. Commits: `89c99cc`, `82d80e4`, `23caaa9`.

Verification: `ProtocolCodecTest` and `MessageRouterTest` passed. `MessageRouterTest` covers two-router relay metadata changes, deduplication, expiry, invalid timing, stored-only relay, ACK behavior, and tamper rejection. No device verification is recorded.

Known limitations: An ACK proves one-hop storage only; end-to-end delivery uses a separate signed receipt. The code and docs do not provide proof of global ordering or global delivery in a partitioned mesh.

## 3. Identity, signing, verification, and encryption boundary

Description: Each installation has a P-256 identity in Android Keystore; packet payloads are signed and peers verify the signature and public-key fingerprint-derived node ID.

Status: `Implemented, compiled and unit-tested only` for signing/verification; encryption is `Not started`.

Evidence: `AndroidIdentitySigner.kt` creates or loads `secp256r1` keys in `AndroidKeyStore`, signs with `SHA256withECDSA`, verifies X.509 public keys, and derives the node ID from SHA-256. `MessageRouter.kt` verifies signed packets before persistence. `README.md` states: “Messages are signed but not encrypted.” `docs/PROTOCOL.md` states that the origin signature covers the immutable payload while relay TTL/hop metadata remains outside it. Commits: `cc05544`, `89c99cc`, `82d80e4`.

Verification: Protocol and router tests passed using JVM test signers. No test or repo document records an Android Keystore signing/verification run on a physical device. `rg` found no AES, GCM, Cipher, encrypt, or decrypt implementation in the app source.

Known limitations: The fingerprint identifies an installation, not a real-world person. Payload confidentiality and protection against mesh eavesdropping are not implemented. Offline pack catalog signatures are a separate ECDSA integrity check in `PackSignatureVerifier.kt`; they do not encrypt map data.

## 4. Contacts, direct messages, and delivery states

Description: Users can discover trusted peers, exchange contact requests, send direct text, and observe queued, relayed, and delivered state.

Status: `Implemented, compiled and unit-tested only`.

Evidence: `ContactDirectHandler.kt`, `MessagingServices.kt`, `ContactRouterTest.kt`, `DirectConversation.kt`, `DirectMessageRouterTest.kt`, and `DirectConversationIdTest.kt`. `ContactEntity` stores pending/trusted/blocked state and request expiry. `ConversationMessageEntity` derives `QUEUED`, `RELAYED`, and `DELIVERED`; `MessageReceiptEntity` stores end-to-end receipts. Commits: `b087967`, `56453c7`, `4693e69`, `23caaa9`.

Verification: `ContactRouterTest` (8), `DirectMessageRouterTest` (6), and `DirectConversationIdTest` (1) passed. No device chat or two-node delivery run is recorded.

Known limitations: Delivery is best effort and receipt-based; there is no evidence of media, encrypted DM content, or device-level delivery testing.

## 5. Family Circles, snapshots, safety status, and location

Description: Owner-signed membership snapshots govern invited members, circle text, signed status updates, leave requests, dissolution, and receipts.

Status: `Implemented, compiled and unit-tested only` for the circle lifecycle/status model; the deck's `MISSING` and member-GPS promise is `Partial`.

Evidence: `CircleLifecycleHandler.kt`, `CircleContentHandler.kt`, `RoomCircleRepository.kt`, `CircleModels.kt`, `CirclesViewModel.kt`, and `CircleDetailActivity.kt`. `Models.kt` defines only `UNKNOWN`, `SAFE`, and `NEED_HELP`. Higher origin sequence wins; status becomes effectively unknown after 24 hours while preserving the last reported value. `CircleRouterTest` covers snapshots, invite lifecycle, owner-only changes, dissolution, membership versions, pending replay, receipts, self-authored status, sequence ordering, and stale status behavior. Commits: `a9faf2a`, `23caaa9`, `bfd7b9a`.

Verification: `CircleRouterTest` passed all 19 tests. The UI contains a current-coordinate insertion button in `activity_circle_detail.xml` and `CircleDetailActivity.kt`, but no circle packet/entity field for continuous member GPS was found. No physical-device circle run is recorded.

Known limitations: There is no `MISSING` enum or missing-specific packet/status behavior; stale status is derived as `UNKNOWN`. Location insertion is a coordinate text convenience, not circle-wide GPS sharing. Circle UI and synchronization are not device-verified.

## 6. Room database

Description: Room persists raw packets, projections, peers, contacts, deliveries, receipts, local state, circles, status events, and hazards.

Status: `Implemented, compiled and unit-tested only`.

Evidence: `ResQNetDatabase.kt` reports database version `6`, with `MIGRATION_5_6` adding `hazard_reports`; destructive reset is allowed only from versions 1-4. The current database declares 16 entity classes: `PacketEntity`, `ConversationMessageEntity`, `PeerEntity`, `ContactEntity`, `PeerDeliveryEntity`, `MessageReceiptEntity`, `LocalStateEntity`, `CircleEntity`, `CircleInvitationEntity`, `CircleSnapshotEntity`, `CircleMemberEntity`, `CircleMessageEntity`, `PendingCirclePacketEntity`, `CircleMessageReceiptEntity`, `CircleStatusEventEntity`, and `HazardReportEntity`. Commits include `cc05544`, `89c99cc`, `a9faf2a`, and `2128768`.

Verification: `DatabaseVersionPolicyTest` passed. Other router tests use in-memory repositories or test doubles; no on-device schema migration/upgrade run is recorded.

Known limitations: `exportSchema = false`; no checked-in Room schema artifact was found. Physical migration behavior is unverified.

## 7. UI screens and bottom navigation

Description: The native Android app exposes Chat, Contacts, Circles, Mesh, and Navigate bottom tabs, plus setup/onboarding, circle detail, direct conversation, diagnostics, and navigation screens.

Status: `Partial`.

Evidence: `app/src/main/java/com/resqnet/app/ui/` contains `ChatActivity`, `ContactsActivity`, `CirclesActivity`, `CircleDetailActivity`, `DirectConversationActivity`, `MeshControlActivity`, `NavigateActivity`, `DiagnosticsActivity`, setup, onboarding, splash, and spike activities. `app/src/main/res/menu/bottom_nav_menu.xml` defines the five tabs and `NavigationHelper.kt` routes them. Commits: `59ed154`, `4170910`, `7388799`, `ad1c906`, `18d140d`, `11fed70`.

Verification: The debug/unit-test build compiled and `testDebugUnitTest` passed. `ExampleInstrumentedTest.kt` exists but was not part of the latest unit-test task; no UI screenshot, instrumentation, or physical-device record is checked in.

Known limitations: UI correctness, permissions, navigation between activities, and screen behavior remain unverified by repository evidence.

## 8. Offline navigation, Valhalla, packs, basemap, shelters, and guidance

Description: The Navigate screen can install/import/download a signed region pack, initialize Valhalla, load a MapLibre style and shelter GeoJSON, calculate pedestrian routes, draw route geometry, use GPS progress, and expose foreground guidance/notifications/PiP.

Status: `Partial`.

Evidence: `ValhallaRoutingEngine.kt` is the production adapter and passes bounded avoidance polygons to Valhalla. `OfflinePackManager.kt`, `ZipRegionPackInstaller.kt`, `PackManifestCodec.kt`, and `PackSignatureVerifier.kt` implement catalog download, HTTPS/signature checks, user import, safe extraction, SHA-256 file checks, atomic activation, and bundled asset installation. `NavigateActivity.kt` implements MapLibre map setup, route drawing, shelter markers/list, pins, hazards, GPS location, turn guidance, text-to-speech, notification/PiP integration, and online fallback. `NavigationForegroundService.kt` implements location updates and route-progress evaluation. Commits: spike `a3162c5`-`60c98c1`, merged by `966517c`; main `b6c2055`, `83d4da3`, `2128768`, `11fed70`.

Verification: `ZipRegionPackInstallerTest` (3), `ValhallaRouteMapperTest` (1), `Polyline6DecoderTest` (2), `NavigationProgressEvaluatorTest` (3), `ShelterRouteSelectorTest` (1), `RouteGeometryTest` (4), `RoutingSpikeTest` (5), and `HazardAvoidanceTest` (2) passed. `docs/spike/RESULTS.md` has no device model or filled metric. No device navigation run is recorded.

Basemap and shelter truth: `tmp/create_mumbai_test_pack.py` says the pack is a “tiny local-only smoke-test pack; it is not emergency mapping data.” It generates a synthetic grid-tile basemap. The bundled `mumbai-demo-pack.zip` contains `basemap.pmtiles`, `valhalla_tiles.tar`, `valhalla.json`, `style.json`, `shelters.geojson`, and `region-pack.json`; the shelter record is named “Mumbai test destination,” has address “Test fixture only,” source “ResQNet test fixture,” and `verificationStatus: unverified`. `NavigateActivity.kt` rejects the small placeholder basemap for offline rendering and falls back to the online OpenStreetMap demonstration style. Therefore the bundled basemap is a test fixture, the bundled shelter data is a test fixture, and neither is verified emergency data.

Known limitations: The route engine may be integrated, but on-device cold start, route latency, memory, GPS guidance, offline rendering, and shelter correctness are not evidenced. Valhalla avoidance is not yet validated on a device. `docs/spike/RESULTS.md` also records the maintenance risk of the Valhalla bridge/library internals.

## 9. BARP battery-aware relay

Description: The current worktree reframes BARP as battery-aware operating policy for an opportunistic BLE mesh rather than a measured end-to-end routing algorithm.

Status: `Implemented, compiled and unit-tested only`.

Evidence and stages present in the current uncommitted `main` worktree:

- `AndroidBatteryStateProvider.kt` reads battery percentage, charging state, and Android Power Saver changes.
- `BarpPolicy.kt` implements `NORMAL`, `CONSERVATION`, and `CRITICAL` modes, thresholds at 40/45% and 15/20%, hysteresis, charging recovery, and a 30-second scan-mode dwell helper.
- `BarpState` maps normal to balanced scan, conservation/critical to low-power scan, and changes sync intervals from 10 to 30 to 60 seconds.
- `BleMeshTransport.kt` applies balanced/low-power BLE scan modes and defers changes inside the dwell window.
- `MeshCoordinator.kt` observes BARP state and prioritizes `CIRCLE_STATUS` packets whose status is `NEED_HELP`; `CriticalTraffic.kt` explicitly says an SOS packet kind does not yet exist.
- `DiagnosticsActivity.kt`, `activity_diagnostics.xml`, `MeshRuntime.kt`, and `MeshControlActivity.kt` expose debug overrides and status.

Verification: The latest forced unit run included `BarpPolicyTest` (4) and `CriticalTrafficTest` (1), all passing. `docs/BARP_TEST_PROCEDURE.md` is an uncommitted procedure, not a result; it requires two phones, one-hour screen-off comparisons, batterystats, and delivery/reliability measurements. No battery percentage saving, RSSI-based path choice, hop-cost optimization, or physical-device BARP result is recorded.

Known limitations: BARP currently changes scan power, synchronization cadence, and requested-packet ordering. It does not implement AODV, RSSI-aware route selection, a residual-battery path metric, or a signed SOS packet. Because the source/tests/docs are uncommitted, no BARP commit hash exists.

## 10. Routing spike: GraphHopper versus Valhalla

Description: The spike contains both engines; the main app now uses Valhalla for production offline routing.

Status: `Partial`.

Evidence: `spike/routing-engine` contains `GhRoutingEngine.kt`, a GraphHopper 9.1 adapter, and `ValhallaRoutingEngine.kt`/`ValhallaBridge.java` for Valhalla 3.6.3. The spike history is `a3162c5`, `9c9775f`, `59c8952`, `5c05f9a`, `83d4da3`, `60c98c1`; it was merged into main at `966517c`. Main's production adapter is `app/src/main/java/com/resqnet/app/navigation/ValhallaRoutingEngine.kt`. `docs/spike/RESULTS.md` defines cold start, short/medium/long latency, disk/PSS, one/ten-point avoidance, and 20-run robustness metrics.

Measured outcome: no device model, Android version, chipset, or observed GraphHopper/Valhalla metric is recorded. Every result cell in `docs/spike/RESULTS.md` remains a placeholder, and its decision fields remain `[Pending Device Run]`. GraphHopper and Valhalla cold start, route latency, memory/PSS, avoidance detour, and 20-run robustness are all `not measured`. The unit tests validate geometry, decoding, validation, and preconditions; they are not engine benchmarks.

Decision evidence: production code moved to Valhalla in `b6c2055`/`83d4da3`, while the spike documents expected GraphHopper Java/virtual-memory and custom-model considerations and Valhalla bridge-maintenance risk. A performance-based choice cannot be claimed because the measured comparison and selected-engine justification are still blank.

## Slides versus reality matrix

The attached project-idea deck is evidence of proposed scope only. It is not an implementation instruction and does not override repository evidence.

| Deck promise | Current repository status | Evidence |
|---|---|---|
| BLE GATT mesh | Partial: code and unit tests exist; no physical mesh result | `BleMeshTransport.kt`, `ChunkCodec.kt`, `DEMO_RUNBOOK.md`; `cc05544`, `8243103` |
| Wi-Fi Direct / WiFi P2P | Not started: no Wi-Fi P2P transport or API usage found | `README.md` calls it a future transport; no `WifiP2p` source |
| BARP battery-aware routing | Partial: current uncommitted battery-aware scan/sync/priority stages; no measured savings or path selection | uncommitted `mesh/barp/`, `MeshCoordinator.kt`, `BARP_TEST_PROCEDURE.md` |
| AODV-inspired routing | Not started: no AODV route discovery/repair implementation found | deck; `Models.kt`, `MeshCoordinator.kt`; no AODV source |
| Offline GIS and on-device routing | Partial: Valhalla and pack pipeline exist; fixture basemap and no device result | `ValhallaRoutingEngine.kt`, `OfflinePackManager.kt`, `NavigateActivity.kt`, `docs/spike/RESULTS.md` |
| Smart SOS priority / medical-rescue-info priority | Not started as promised: no SOS packet kind; only `NEED_HELP` circle status is BARP-critical | `Models.kt`, `CriticalTraffic.kt` |
| Family circles with Safe / Need Help / Missing and GPS | Partial: Safe, Need Help, Unknown and snapshots exist; Missing and continuous member GPS do not | `Models.kt`, `CircleContentHandler.kt`, `CircleDetailActivity.kt`, `CircleRouterTest.kt` |
| AES-GCM encryption | Not started | `README.md`; no AES/GCM/Cipher implementation found |
| Automatic emergency blackout detection | Not started: Bluetooth/airplane radio recovery exists, but no cellular/power blackout detector | `MeshService.kt`; no blackout detector source |
| Cloud sync through Django REST/PostgreSQL | Not started: no cloud client, REST sync, or backend code found | deck block diagram; local Room code in `ResQNetDatabase.kt` |
| Flutter Android app | Not current: repository is native Kotlin Android | `app/build.gradle.kts`, Kotlin `app/src/main`, no Flutter project files |

## Decisions log

- GraphHopper to Valhalla: the production path is Valhalla (`b6c2055`, `83d4da3`, `ValhallaRoutingEngine.kt`) after the dual-engine spike. The repository documents the expected tradeoffs - GraphHopper's Java 17/custom-model/virtual-memory concerns versus Valhalla's native bridge and dynamic exclusion support - but `docs/spike/RESULTS.md` leaves the measured reason and selection fields blank. The evidence supports “chosen in code,” not a measured winner.
- BARP was reframed from deck-level adaptive path routing to battery-aware relay policy on an opportunistic BLE mesh. The current worktree implements battery modes, scan power, sync cadence, dwell, and `NEED_HELP` prioritization, but not AODV or a battery/RSSI route optimizer. Evidence: uncommitted `mesh/barp/`, `MeshCoordinator.kt`, `BARP_TEST_PROCEDURE.md`.
- Native Kotlin replaced the deck's Flutter description. The actual build is Gradle/Kotlin Android with native activities, Room, MapLibre, and Valhalla adapters; no Flutter project or dependency exists. Evidence: `app/build.gradle.kts`, `app/src/main`, commit `59ed154` and later UI commits.
- The protocol was hardened around typed RQP2 packets, immutable signed payload bytes, mutable relay envelopes, bounded TTL, deduplication, and explicit relay policy. Evidence: `89c99cc`, `82d80e4`, `docs/PROTOCOL.md`.
- Navigation moved from a spike to a Valhalla-backed offline-pack flow, then added Mumbai demo assets and UI/hazard work. Evidence: `966517c`, `60c98c1`, `b6c2055`, `83d4da3`, `2128768`, `11fed70`.
- Security scope is signing and identity verification, not confidentiality. Evidence: `AndroidIdentitySigner.kt`, `README.md`, `docs/PROTOCOL.md`.

## Open issues and risks

- Valhalla avoidance is not yet validated: code constructs avoidance polygons and tests their geometry, but no device route detour, latency, or robustness measurement exists.
- Hazard reporting and avoidance plumbing exist, but operational hazard avoidance is not proven; do not claim the deck's flood-avoidance behavior from unit tests alone.
- Basemap and shelter records are test fixtures. The bundled shelter is explicitly unverified and the tiny basemap is synthetic; the UI intentionally uses an online OSM fallback when the placeholder is detected.
- GraphHopper versus Valhalla has no measured device outcome. Device model, cold/warm latency, PSS, disk, avoidance, and 20-run stability are all not measured.
- No physical-device evidence was found for BLE, Android Keystore, foreground service, circles, UI, offline rendering, live GPS guidance, or BARP. The runbook and BARP procedure are instructions, not results.
- Wi-Fi Direct, AODV, AES-GCM, SOS packet/priority, automatic blackout detection, cloud sync, Missing status, and continuous circle-member GPS have no implementation evidence.
- Current working tree is dirty. Uncommitted paths are `app/src/main/java/com/resqnet/app/ResQNetApplication.kt`, `app/src/main/java/com/resqnet/app/mesh/MeshCoordinator.kt`, `app/src/main/java/com/resqnet/app/mesh/MeshRuntime.kt`, `app/src/main/java/com/resqnet/app/mesh/MeshService.kt`, `app/src/main/java/com/resqnet/app/mesh/MeshTransport.kt`, `app/src/main/java/com/resqnet/app/mesh/ble/BleMeshTransport.kt`, `app/src/main/java/com/resqnet/app/ui/DiagnosticsActivity.kt`, `app/src/main/java/com/resqnet/app/ui/MeshControlActivity.kt`, `app/src/main/res/layout/activity_diagnostics.xml`, untracked `app/src/main/java/com/resqnet/app/mesh/barp/`, untracked BARP tests, and untracked `docs/BARP_TEST_PROCEDURE.md`.
- `docs/spike/RESULTS.md` notes Valhalla reliance on library internals through `ValhallaBridge.java`, creating upstream linkage/obfuscation maintenance risk.
- `exportSchema = false` means Room schema history is not checked in; only the version-policy unit test was run.

## Test inventory

Latest command: `./gradlew.bat testDebugUnitTest --no-daemon --rerun-tasks` on 2026-10-02. Result: `BUILD SUCCESSFUL`; 83 tests, 19 test classes, 0 failures, 0 errors, 0 skipped. The XML results are under `app/build/test-results/testDebugUnitTest/`.

| Test class | Tests | Result | Scope |
|---|---:|---|---|
| `CircleRouterTest` | 19 | Pass | Circle lifecycle, snapshots, status, receipts, ordering |
| `ContactRouterTest` | 8 | Pass | Contact request/accept/decline/expiry/blocking |
| `DirectConversationIdTest` | 1 | Pass | Symmetric conversation IDs |
| `DirectMessageRouterTest` | 6 | Pass | Direct projection, receipts, target/trust checks |
| `ContactExpiryNormalizerTest` | 2 | Pass | Concurrent expiry normalization |
| `DatabaseVersionPolicyTest` | 1 | Pass | Version 6/reset policy |
| `BarpPolicyTest` | 4 | Pass | Thresholds, hysteresis, dwell, charging/power saver |
| `CriticalTrafficTest` | 1 | Pass | `NEED_HELP` BARP classification |
| `ChunkCodecTest` | 1 | Pass | Out-of-order BLE chunk reassembly |
| `MessageRouterTest` | 10 | Pass | Relay, TTL/expiry, dedup, ACK, projection, tamper checks |
| `HazardAvoidanceTest` | 2 | Pass | Hazard defaults and polygon conversion |
| `NavigationProgressEvaluatorTest` | 3 | Pass | GPS fix acceptance, debounce, arrival |
| `ZipRegionPackInstallerTest` | 3 | Pass | Safe import, integrity, archive validation |
| `Polyline6DecoderTest` | 2 | Pass | Route geometry decoding |
| `ShelterRouteSelectorTest` | 1 | Pass | Shelter selection |
| `RouteGeometryTest` | 4 | Pass | Spike route geometry |
| `RoutingSpikeTest` | 5 | Pass | Spike preconditions/validation, not device benchmarks |
| `ValhallaRouteMapperTest` | 1 | Pass | Valhalla response mapping |
| `ProtocolCodecTest` | 9 | Pass | RQP2/RQS2/RQE2/RQF2 encoding and bounds |

Only the unit-test task was run in this audit. `ExampleInstrumentedTest.kt` exists under `app/src/androidTest`, but no connected-device/instrumentation result is recorded. `docs/DEMO_RUNBOOK.md`, `docs/BARP_TEST_PROCEDURE.md`, and `docs/spike/RESULTS.md` describe tests to run; they do not report completed device runs.

## Five most important gaps between slides and code

1. The deck promises BLE plus Wi-Fi Direct and AODV-style routing; the code currently has BLE only and no AODV.
2. The deck promises AES-GCM-secured broadcasts and smart SOS priority; the code signs plaintext packets, has no AES-GCM, no SOS packet kind, and only prioritizes `NEED_HELP` circle status in the uncommitted BARP work.
3. The deck promises real offline GIS and safe-shelter navigation; the bundled basemap and shelter are explicit test fixtures, the UI falls back online for the placeholder basemap, and no device navigation result is recorded.
4. The deck promises circles with `MISSING` and GPS; the code has `UNKNOWN`, `SAFE`, and `NEED_HELP`, plus coordinate insertion, but no Missing status or continuous circle-member GPS.
5. The deck promises blackout/cloud-backed operation and a Flutter stack; the repository has no blackout detector or cloud sync and is a native Kotlin Android app. The promised GraphHopper measurements are also entirely unfilled.
