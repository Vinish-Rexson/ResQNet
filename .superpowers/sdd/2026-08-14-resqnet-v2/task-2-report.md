# Task 2 report: Trusted contacts and direct messages

## Status

Complete. Implementation commit: `b087967` (`feat: add trusted contacts and direct messages`).

## Files

- Added `app/src/main/java/com/resqnet/app/contacts/ContactDirectHandler.kt`
- Added `app/src/main/java/com/resqnet/app/contacts/DirectConversation.kt`
- Added `app/src/main/java/com/resqnet/app/contacts/MessagingServices.kt`
- Added `app/src/test/java/com/resqnet/app/contacts/ContactRouterTest.kt`
- Added `app/src/test/java/com/resqnet/app/contacts/DirectConversationIdTest.kt`
- Added `app/src/test/java/com/resqnet/app/contacts/DirectMessageRouterTest.kt`
- Modified application dependency wiring, Room entities/DAO/converters/repositories/database, and `MessageRouter`.

No top-level screen, activity, manifest, resource, or navigation changes were made.

## Observed RED/GREEN cycles

1. Deterministic direct conversation ID
   - RED: focused test failed compilation because `directConversationId` did not exist.
   - GREEN: SHA-256 of a length-prefixed, sorted node-ID pair passed symmetry and ordering-collision tests.

2. Fingerprint-confirmed request and request-linked acceptance
   - RED: contact entities/repository, states, and router commands were unresolved.
   - GREEN: a discovered peer with the confirmed displayed fingerprint could receive a signed request; acceptance referenced that request packet ID and trusted both sides.

3. Crossed requests, decline, expiry, remove, block, and duplicate controls
   - RED: contact lifecycle commands were unresolved.
   - GREEN: crossed requests merged into incoming confirmation; accept/decline were idempotent; expiry cleanup, remove/re-request, and block/unblock passed.

4. Direct A to relay to B with signed receipt
   - RED: direct creation, receipt repository, delivered state, and related projection APIs were unresolved.
   - GREEN: relay held zero visible rows, B projected once and emitted one signed receipt, and A transitioned Queued to Relayed to Delivered.

5. Permanently suppressed packet replay
   - RED: after later trust/unblock, an exact duplicate of a packet received while untrusted/blocked became visible or processed.
   - GREEN: `SUPPRESSED` raw state preserves relay storage while preventing later authorization changes from revealing or processing an already-suppressed packet.

6. Conversation repository flow
   - RED: `observeConversation` was unresolved.
   - GREEN: the repository flow filtered the shared visible stream by deterministic conversation ID; application contact/direct services expose the focused command/flow seams needed by Task 4.

7. Crossed-request expiry ownership
   - RED: a newer incoming crossed request inherited the older outgoing request's expiry and could not be confirmed while still valid.
   - GREEN: merged confirmation now uses the incoming request's own 24-hour expiry.

8. Incoming fingerprint formatting
   - RED: a verified incoming request persisted a compact fingerprint that did not match the app's displayed hyphenated fingerprint.
   - GREEN: contacts derived from signed incoming requests persist the same display format as `AndroidIdentitySigner`.

9. Receipt recovery after projection interruption
   - RED: cancellation after visible direct projection left no receipt, and exact redelivery returned Duplicate without repairing it.
   - GREEN: a projected recipient duplicate idempotently ensures the one required receipt exists.

10. Expired request replacement
    - RED: an expired incoming request shadowed a later valid request from the same node.
    - GREEN: receipt of a new request first removes an expired pending state, allowing the new request to be accepted.

11. Receipt claim crash window
    - RED: receipt packet creation happened before durable idempotency claim, so interruption could leave an untracked packet and allow a second receipt.
    - GREEN: the receiver reserves one deterministic receipt packet ID in the receipt table first, then creates or recovers exactly that raw packet on duplicate delivery.

## Commands and results

- Focused RED/GREEN commands used `\.\gradlew.bat testDebugUnitTest --tests ...` for each contact/direct behavior above.
- Final: `\.\gradlew.bat testDebugUnitTest assembleDebug` -> `BUILD SUCCESSFUL` in 22s.
- Final test XML: 32 tests, 0 failures, 0 errors across 6 suites.
- `git diff --check` -> no whitespace errors (only Git's existing LF-to-CRLF notices).

## Self-review

- Transport validation, exact signed-byte dedupe, immutable relay envelopes, raw-before-projection ordering, hop ACK behavior, and public-text projection remain owned by `MessageRouter`.
- Contact/direct state transitions and authorization were extracted into focused `ContactDirectHandler` behind a narrow router port; repository-facing UI services remain small and do not add navigation.
- `PeerEntity` remains separate from durable `ContactEntity`; incoming contacts copy only authenticated identity data needed after discovery.
- Remove and unblock delete local trust only. Blocked/untrusted/removed direct packets and blocked requests raw-persist and relay but cannot later become visible on replay. Public text is unaffected.
- Receipt validation binds the receipt signer to the original direct audience, updates only an outgoing direct row, never creates a receipt for a receipt, and remains independent from hop-level Relayed state.
- Mutation review is covered for wrong target/signer, wrong conversation ID, missing trust, blocked state, duplicate controls/receipts, wrong request reference, expiry boundaries, and recovery windows.

## Concerns

- Room behavior is compile/KAPT verified here; on-device DAO/destructive-reset instrumentation remains explicitly scheduled for Task 5.
- Top-level UI/navigation and notifications are intentionally deferred to Task 4.

## Fix round 1/5: Review findings

Original Task 2 commits:

- `b087967` — `feat: add trusted contacts and direct messages`
- `a8dc349` — `docs: report trusted contacts task`

### RED/GREEN evidence

1. Room schema version and destructive reset scope
   - RED command: `.\gradlew.bat testDebugUnitTest --tests com.resqnet.app.data.DatabaseVersionPolicyTest`
   - RED outcome: compilation failed because `RESQNET_DATABASE_VERSION` and `canDestructivelyResetFrom` did not exist.
   - GREEN command: same focused command.
   - GREEN outcome: `BUILD SUCCESSFUL` in 11s; schema version 3 resets only versions 1 and 2. The database builder uses `fallbackToDestructiveMigrationFrom(true, 1, 2)`; `ProfileStore` and Android Keystore remain outside Room, preserving profile/identity and the existing one-time upgrade-notice intent.

2. Independent crossed-request expiries and decline convergence
   - RED command: `.\gradlew.bat testDebugUnitTest --tests com.resqnet.app.contacts.ContactRouterTest.crossedRequestConfirmationUsesIncomingRequestsOwnExpiry --tests com.resqnet.app.contacts.ContactRouterTest.crossedDeclinePreservesTheOtherRequestSoAValidAcceptConvergesBothSides`
   - RED outcome: compilation failed because independent outgoing/incoming expiry fields did not exist.
   - GREEN command: `.\gradlew.bat testDebugUnitTest --tests com.resqnet.app.contacts.ContactRouterTest`
   - GREEN outcome: `BUILD SUCCESSFUL` in 10s. Each request ID retains its own expiry; accept/decline validate the referenced side. Declining a crossed incoming request preserves the local outgoing request, and a later valid accept converges both devices to trusted.

3. Fresh discovery requirement
   - RED command: `.\gradlew.bat testDebugUnitTest --tests com.resqnet.app.contacts.ContactRouterTest.contactRequestRequiresPeerSeenInsideDiscoveryFreshnessWindow`
   - RED outcome: compilation failed because the named discovery window did not exist; the old command path accepted any historical peer row.
   - GREEN command: same focused command.
   - GREEN outcome: `BUILD SUCCESSFUL` in 6s. `DISCOVERY_FRESHNESS_WINDOW_MS` is an injectable two-minute window; stale peer rows are rejected, while a fresh HELLO permits the request.

4. Atomic/retryable local direct persistence
   - RED command: `.\gradlew.bat testDebugUnitTest --tests com.resqnet.app.contacts.DirectMessageRouterTest.cancelledLocalDirectPersistenceExposesNoRelayablePacketWithoutSenderProjection`
   - RED outcome: compilation failed because `LocalProjectionRepository` and the router injection seam did not exist.
   - GREEN command: same focused command.
   - GREEN outcome: `BUILD SUCCESSFUL` in 7s. Room now persists the raw direct packet, outgoing visible row, and projected state in one transaction with exact-row reconciliation for retry. Cancellation exposes neither a relayable packet nor a sender-less projection; successful retry restores Queued state and receipt applicability.

### Regression and final verification

- Combined focused command: `.\gradlew.bat testDebugUnitTest --tests com.resqnet.app.contacts.ContactRouterTest --tests com.resqnet.app.contacts.DirectMessageRouterTest --tests com.resqnet.app.mesh.MessageRouterTest --tests com.resqnet.app.data.DatabaseVersionPolicyTest`
- First combined outcome: two long-duration fixtures failed because they intentionally advanced beyond the new discovery window without a fresh HELLO. Fixtures were corrected to rediscover before originating the later request; expiry assertions were unchanged.
- Second combined outcome: `BUILD SUCCESSFUL` in 2s; 25 focused tests passed.
- Final command: `.\gradlew.bat testDebugUnitTest assembleDebug`
- Final outcome: `BUILD SUCCESSFUL` in 14s; 36 tests passed, 0 failed, 0 errors across 7 suites, and the debug APK assembled.
- `git diff --check`: no whitespace errors; only the repository's LF-to-CRLF notices.

### Fix-round self-review and concerns

- `ContactEntity` now stores independent nullable outgoing/incoming IDs and expiries. Normalization clears only expired sides and derives pending state from the surviving request.
- `RoomLocalProjectionRepository` owns the only production direct local-write path and uses a Room transaction; the router fallback remains solely for isolated tests that do not inject Room.
- Destructive reset is explicitly restricted to schemas 1 and 2 rather than accepting every unknown historical/future version.
- On-device migration/reset execution remains scheduled for Task 5 instrumentation; this round compile/KAPT verifies the v3 schema and builder API.

## Fix round 2/5: Conditional pending expiry

Task 2 commits entering this round:

- `b087967` — `feat: add trusted contacts and direct messages`
- `a8dc349` — `docs: report trusted contacts task`
- `56453c7` — `fix: harden contact persistence invariants`

### RED/GREEN evidence

1. Concurrent pending-request replacement
   - RED command: `.\gradlew.bat testDebugUnitTest --tests com.resqnet.app.data.ContactExpiryNormalizerTest`
   - RED outcome: compilation failed because `PendingContactExpiry`, `ContactExpiryStore`, and `samePendingSnapshot` did not exist. The two new tests model a stale expiry scan racing with a valid replacement before either a full-row delete or crossed-request normalization write.
   - GREEN command: same focused command.
   - GREEN outcome: `BUILD SUCCESSFUL` in 15s; both stale-snapshot races preserve the concurrent replacement.

2. Contact-state regression check
   - Focused command: `.\gradlew.bat testDebugUnitTest --tests com.resqnet.app.data.ContactExpiryNormalizerTest --tests com.resqnet.app.contacts.ContactRouterTest`
   - Focused outcome: `BUILD SUCCESSFUL` in 3s; 10 tests passed, 0 failed, 0 errors across 2 suites.

### Design and self-review

- Expiry still scans pending rows, but each mutation is now a single conditional SQL statement. Delete and normalization update require the same node ID, pending state, outgoing request ID/expiry, and incoming request ID/expiry that were read.
- A failed conditional mutation is benign: it means another operation changed the pending snapshot, so expiry does not replay stale state or delete the replacement.
- The normalization update changes only request state/IDs/expiries; it cannot overwrite concurrently refreshed authenticated identity fields.
- Crossed requests retain independent IDs and expiries. When only one side expires, the guarded update clears that side and preserves the valid side exactly as before.

### Final verification

- Final command: `.\gradlew.bat testDebugUnitTest assembleDebug`
- Final outcome: `BUILD SUCCESSFUL` in 9s; 38 tests passed, 0 failed, 0 errors across 8 suites, and the debug APK assembled.
- `git diff --check`: no whitespace errors; only the repository's LF-to-CRLF notices.
