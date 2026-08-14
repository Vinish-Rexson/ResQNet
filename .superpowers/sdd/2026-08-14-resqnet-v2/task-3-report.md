# Task 3 report: Family Circles, chat, status, and lifecycle

## Status

Complete backend vertical slice. Implementation commit: this Task 3 commit.

## Files

- Added focused Circle models, repository/Room implementation, lifecycle handler, command services, and packet-level tests under `app/src/{main,test}/java/com/resqnet/app/circles/`.
- Extended typed Circle wire bodies, Room schema/DAO/converters, application dependency wiring, packet supersession/resolution, and the narrow `MessageRouter` delegation seam.
- No Task 4 UI/navigation/notification changes.

## RED/GREEN evidence

1. Circle wire roles, previews, status subjects, and version-bound receipts
   - RED: `.\gradlew.bat testDebugUnitTest --tests com.resqnet.app.protocol.ProtocolCodecTest` failed compilation on missing `CircleSnapshotMember`, roles, subject, preview, and receipt metadata.
   - GREEN: same command -> `BUILD SUCCESSFUL`.
2. Lifecycle/chat/status vertical slice
   - RED: `.\gradlew.bat testDebugUnitTest --tests com.resqnet.app.circles.CircleRouterTest` failed compilation on all missing Circle entities/repository/router commands.
   - GREEN: same command -> 7 tests passed.
3. Room v4 Circle schema
   - RED: `.\gradlew.bat testDebugUnitTest --tests com.resqnet.app.data.DatabaseVersionPolicyTest` failed because schema remained v3.
   - GREEN: combined database/Circle command -> `BUILD SUCCESSFUL`; KAPT/Room compilation passed.
4. Snapshot supersession
   - RED: focused owner lifecycle test failed compilation before `PacketRepository.supersede` existed.
   - GREEN: only the newest owner snapshot remains relayable.
5. Leave pending and durable-control resolution
   - RED: leave retry threw read-only and a rename changed `LEAVE_PENDING` back to `ACTIVE`; durable accept/leave packets remained relayable.
   - GREEN: retry is deterministic, rename preserves read-only state, removal archives, and resolved controls leave inventory.
6. Receipt crash recovery
   - RED: `duplicateProjectedCircleTextRecoversDeterministicReceiptAfterClaimInterruption` retained the claim but created no receipt packet.
   - GREEN: exact duplicate recovers the deterministic signed receipt without creating a second claim.

## Self-review

- Owner identity is checked against both invitation/local metadata and snapshot history; malformed membership, duplicate owner/member, size, UUID, expiry, target, and version invariants fail closed while raw packets remain relayable.
- Referenced snapshot history authorizes chat/status/receipts independently of later contact removal. Pending audience packets replay only when their exact referenced snapshot arrives.
- Leave/removal/dissolution never newly project delayed Circle content. Effective status derives after 24 hours without changing signed history; equal/older sequences cannot replace the winner.
- Latest snapshot supersedes older relay inventory; durable accept/leave controls resolve after their owner snapshot.
- Two-axis review findings for wrong initial-owner validation, premature pending deletion, equal status ordering, late decline, direct-receipt wire compatibility, and receipt crash recovery were corrected.

## Concerns

- Room behavior is KAPT/compile verified; on-device DAO/reset instrumentation remains Task 5.
- Circle message retention shares the project-wide Task 5 cleanup/cap follow-up; authorized archive history is intentionally retained here.
- `CircleHandler` is focused outside `MessageRouter`, but can be split further into lifecycle/chat/status collaborators if those areas grow.
