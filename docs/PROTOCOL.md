# ResQNet protocol v2

All multi-byte numeric values use Java `DataOutputStream` big-endian encoding. Strings and byte arrays are encoded as a signed 32-bit length followed by UTF-8 or raw bytes. Decoders reject invalid bounds, unsupported enum tags, and trailing bytes.

## Signed message

`PayloadV2` begins with magic `RQP2` (bytes `52 51 50 32`) and includes:
- Protocol version (`int`, must be 2)
- Packet UUID (two `long` values: most/least significant bits)
- Packet kind (`byte` wire ID, see table below)
- Audience type and audience ID (see Audiences)
- Origin node ID (SHA-256 fingerprint, first 32 hex chars)
- Origin display name (≤ 64 UTF-8 bytes)
- Origin sequence (`long`, monotonically increasing per origin node)
- Creation timestamp and expiry timestamp (`long`, epoch ms)
- Relay policy (`byte` wire ID, see Relay Policies)
- Typed body (kind-specific fields)

The originating installation signs these exact bytes with P-256 ECDSA/SHA-256 using a key stored in Android Keystore.

`SignedPacket` (`RQS2`, bytes `52 51 53 32`) carries the unchanged payload bytes, ECDSA signature, and X.509-encoded public key. The node ID is the first 128 bits (32 hex chars) of the public-key SHA-256 digest. Receivers verify the signature and node ID before persistence.

`RelayEnvelope` (`RQE2`, bytes `52 51 45 32`) adds mutable TTL (starts at 6), hop count, and a bounded diagnostic hop trace. Relay metadata is deliberately outside the origin signature. Relays decrement TTL, increment hop count, append their node ID to the trace, and preserve payload bytes and signature unchanged.

## Packet kinds

| Wire ID | Kind | Audience | Relay Policy |
|---|---|---|---|
| 1 | `PUBLIC_TEXT` | `PUBLIC_CHANNEL` | Ephemeral |
| 2 | `CONTACT_REQUEST` | `DIRECT_NODE` | Ephemeral |
| 3 | `CONTACT_ACCEPT` | `DIRECT_NODE` | Ephemeral |
| 4 | `CONTACT_DECLINE` | `DIRECT_NODE` | Ephemeral |
| 5 | `DIRECT_TEXT` | `DIRECT_NODE` | Ephemeral |
| 6 | `DELIVERY_RECEIPT` | `DIRECT_NODE` | Ephemeral |
| 7 | `CIRCLE_INVITE` | `DIRECT_NODE` | Ephemeral |
| 8 | `CIRCLE_INVITE_ACCEPT` | `DIRECT_NODE` | Durable until resolved |
| 9 | `CIRCLE_INVITE_DECLINE` | `DIRECT_NODE` | Ephemeral |
| 10 | `CIRCLE_MEMBERSHIP_SNAPSHOT` | `CIRCLE` | Durable until superseded |
| 11 | `CIRCLE_TEXT` | `CIRCLE` | Ephemeral |
| 12 | `CIRCLE_STATUS` | `CIRCLE` | Ephemeral |
| 13 | `CIRCLE_LEAVE_REQUEST` | `DIRECT_NODE` | Durable until resolved |

## Audiences

Three audience types exist (wire ID `byte` prefix):

- `1` — `PUBLIC_CHANNEL`: no additional fields; implicitly targets the `local-emergency` channel.
- `2` — `DIRECT_NODE`: followed by the target node ID (`string`).
- `3` — `CIRCLE`: followed by the circle UUID (`string`).

Decoders reject any kind/audience mismatch.

## Relay policies (wire IDs)

| Wire ID | Policy | Relay eligibility |
|---|---|---|
| 1 | Ephemeral | Relay-eligible for 24 hours from `createdAt` |
| 2 | Durable until superseded | Relay-eligible until a higher-version packet with the same supersession key is received |
| 3 | Durable until resolved | Relay-eligible until the owner publishes a snapshot that resolves the request |

## Session frames

Frames begin with `RQF2` (bytes `52 51 46 32`) and one type byte:

1. `HELLO` (`0x01`): node ID, display name, X.509 public key, key fingerprint, transport version (must be 2).
2. `INVENTORY` (`0x02`): page number, final-page flag, and up to 2,000 recent packet UUIDs.
3. `REQUEST` (`0x03`): UUIDs missing locally.
4. `PACKET` (`0x04`): a relay envelope.
5. `ACK` (`0x05`): UUID confirmed stored by the peer (hop-level acknowledgement only).

Frames are split into GATT chunks containing magic, transfer ID, chunk index, chunk count, and payload. Complete frames are decoded only after all chunks arrive. A packet is acknowledged only after validation and persistence.

## Protocol limits

| Limit | Value |
|---|---|
| Default TTL | 6 hops |
| Max hop count | 6 |
| Max hop trace entries | 7 |
| Propagation window (ephemeral) | 24 hours |
| History retention | 7 days |
| Max retained messages | 10,000 |
| Max text payload | 500 UTF-8 bytes |
| Max status note | 160 UTF-8 bytes |
| Max display name | 64 UTF-8 bytes |
| Max Circle members | 20 (including owner) |
| Max inventory IDs per frame | 2,000 |

## Circle semantics

A circle is identified by a UUID and governed by monotonically increasing `CIRCLE_MEMBERSHIP_SNAPSHOT` packets signed exclusively by the owner. Snapshots carry a version number; only the highest version is relay-eligible at any time (supersession).

A dissolved circle is represented by a terminal snapshot with `dissolved = true` at a higher version number — not a separate packet kind. The dissolved state is permanent; later higher-version snapshots are ignored.

The owner's acceptance of a `CIRCLE_INVITE_ACCEPT` causes the owner to immediately publish the next snapshot adding the new member. `CIRCLE_LEAVE_REQUEST` causes the owner to publish the next snapshot removing the requesting member. Both acceptance and leave packets use `DURABLE_UNTIL_RESOLVED` and are resolved (relay-ineligible) as soon as the owner snapshot supersedes them.

Circle text and status packets reference the sender's `membershipVersion` at send time. Both sender and recipient must be active in the referenced snapshot version. Packets referencing an unknown snapshot are held pending and replayed when the required snapshot arrives.

## Safety status

Safety status values: `1 = UNKNOWN`, `2 = SAFE`, `3 = NEED_HELP`. Members report only their own status. When multiple status packets arrive from the same member, the one with the higher `originSequence` wins; timestamps do not decide ordering. After 24 hours without a newer update, the effective status is derived as `UNKNOWN` while preserving the last reported value for display. Stale `NEED_HELP` remains prominently displayed and labelled as potentially stale.

## Delivery receipts

A hop `ACK` (frame type 5) means only "stored by this peer" — it proves one-hop relay and is sufficient for the `Relayed` UI state. A separate `DELIVERY_RECEIPT` packet (kind 6) is signed by the recipient and routed back to the sender as proof of end-to-end delivery (`Delivered` UI state). Circle receipts include the circle ID and membership version to allow accurate N/M delivery progress counting.

## Global ordering

Global ordering and global delivery are impossible to prove in an open partitionable mesh. The UI uses deterministic best-effort ordering (by `createdAt`, then `originSequence`, then packet UUID) and reports only local queue state or one-peer relay/receipt acknowledgement.
