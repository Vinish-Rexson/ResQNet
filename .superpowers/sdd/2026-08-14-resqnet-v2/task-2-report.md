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
