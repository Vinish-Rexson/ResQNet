# ResQNet protocol v1

All multi-byte numeric values use Java `DataOutputStream` big-endian encoding. Strings and byte arrays are encoded as a signed 32-bit length followed by UTF-8 or raw bytes. Decoders reject invalid bounds and trailing bytes.

## Signed message

`ChatPayload` begins with `RQP1` and includes protocol version, UUID, channel, origin node ID, display name, origin sequence, creation/expiry times, and text. The originating installation signs these exact bytes with P-256 ECDSA/SHA-256.

`SignedChatPacket` (`RQS1`) carries the unchanged payload bytes, signature, and X.509 public key. The node ID is the first 128 bits of the public-key SHA-256 digest. Receivers verify the signature and node ID before persistence.

`RelayEnvelope` (`RQE1`) adds mutable TTL, hop count, and a diagnostic hop trace. Relay metadata is deliberately outside the origin signature.

## Session frames

Frames begin with `RQF1` and one type byte:

1. `HELLO`: node profile and protocol version.
2. `INVENTORY`: page number, final-page flag, and recent message UUIDs.
3. `REQUEST`: UUIDs missing locally.
4. `PACKET`: a relay envelope.
5. `ACK`: UUID persisted by the peer.

Frames are split into GATT chunks containing magic, transfer ID, chunk index, chunk count, and payload. Complete frames are decoded only after all chunks arrive. A packet is acknowledged only after validation and persistence.

Global ordering and global delivery are impossible to prove in an open partitionable mesh. The UI therefore uses deterministic best-effort ordering and reports only local queue or one-peer relay acknowledgement.
