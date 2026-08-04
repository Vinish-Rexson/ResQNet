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
