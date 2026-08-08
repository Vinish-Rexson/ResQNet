# Three-phone mesh demo

## Prepare

1. Install the same debug APK on three Android 12+ BLE-capable phones.
2. Create distinct profiles such as Alice, Relay, and Charlie.
3. Open **Logs** on each phone, enable **forced demo topology**, and assign A, B, and C respectively.
4. Open **Mesh** and grant Nearby Devices access.
5. Enable airplane mode, then explicitly turn Bluetooth back on if the phone disabled it. ResQNet automatically rebuilds its BLE sessions when the radio returns.
6. Start mesh mode on B, then A and C. Keep the persistent ResQNet notification visible.

The forced topology is labelled debug-only. A and C ignore each other's advertisements; B accepts both.

## Acceptance sequence

1. Wait for A and C to each report one peer and B to report two.
2. Send a message from A. It should change from **Queued** to **Relayed** after B acknowledges it, then appear as **Received · 2 hops** on C.
3. Reply from C and confirm the symmetric route through B.
4. Stop mesh on C, send several messages from A, restart C, and confirm history synchronization.
5. Restart mesh on B and confirm persisted history remains available and continues synchronizing.
6. Exchange at least 30 bidirectional messages. Confirm every UUID appears once and arrives within 30 seconds in the controlled setup.
7. Review **Logs** for peer discovery, connection, receive, acknowledgement, and rejection events.

If a device cannot advertise BLE, the diagnostics log reports that hardware limitation. OEM power-saving settings can interrupt long background tests; keep ResQNet excluded from aggressive battery optimization during the prototype evaluation.
