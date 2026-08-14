package com.resqnet.app.mesh

import com.resqnet.app.data.ConversationMessageEntity
import com.resqnet.app.data.ConversationRepository
import com.resqnet.app.data.PacketEntity
import com.resqnet.app.data.PacketRepository
import com.resqnet.app.data.PeerRepository
import com.resqnet.app.data.ProjectionState
import com.resqnet.app.protocol.*
import com.resqnet.app.security.IdentitySigner
import java.security.MessageDigest
import java.util.UUID

sealed interface IngestResult {
    val packetId: String?
    val hopAckEligible: Boolean

    data class Rejected(val reason: String) : IngestResult {
        override val packetId: String? = null
        override val hopAckEligible = false
    }
    data class Duplicate(override val packetId: String) : IngestResult {
        override val hopAckEligible = true
    }
    data class StoredOnly(override val packetId: String) : IngestResult {
        override val hopAckEligible = true
    }
    data class Projected(override val packetId: String) : IngestResult {
        override val hopAckEligible = true
    }
}

class MessageRouter(
    private val packets: PacketRepository,
    private val conversations: ConversationRepository,
    private val peers: PeerRepository,
    private val signer: IdentitySigner,
    private val displayName: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun localProfile() = NodeProfile(signer.nodeId, displayName(), signer.publicKey, signer.fingerprint)

    suspend fun createMessage(text: String): ConversationMessageEntity {
        val clean = text.trim()
        require(clean.isNotEmpty()) { "Message cannot be empty" }
        require(clean.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES) { "Message is longer than 500 UTF-8 bytes" }
        val now = clock()
        val payload = PayloadV2(
            UUID.randomUUID(), PacketKind.PUBLIC_TEXT, Audience.PublicChannel, signer.nodeId,
            displayName(), packets.nextSequence(), now, now + PROPAGATION_WINDOW_MS,
            RelayPolicy.EPHEMERAL, PublicTextBody(clean),
        )
        val payloadBytes = ProtocolCodec.encodePayload(payload)
        val packet = SignedPacket(payloadBytes, signer.sign(payloadBytes), signer.publicKey)
        val envelope = RelayEnvelope(packet, DEFAULT_TTL, 0, listOf(signer.nodeId))
        val packetEntity = packetEntity(payload, envelope, projected = false, receivedAt = now)
        check(packets.insert(packetEntity)) { "Packet ID collision" }
        val message = conversationEntity(payload, displayName(), outgoing = true, hopCount = 0)
        check(conversations.insert(message)) { "Message ID collision" }
        check(packets.markProjected(payload.packetId.toString())) { "Could not mark message projected" }
        return message
    }

    suspend fun ingest(envelope: RelayEnvelope, fromPeerId: String): IngestResult {
        envelope.boundsViolation(requireRelayable = true)?.let { return IngestResult.Rejected(it) }

        val payload = runCatching { ProtocolCodec.decodePayload(envelope.packet.payloadBytes) }
            .getOrElse { return IngestResult.Rejected("Malformed RQP2 payload: ${it.message}") }
        if (payload.payloadVersion != PAYLOAD_VERSION) return IngestResult.Rejected("Unsupported payload version")
        if (payload.expiresAt <= clock()) return IngestResult.Rejected("Packet expired")
        if (payload.expiresAt <= payload.createdAt) return IngestResult.Rejected("Invalid packet timing")
        val lifetime = runCatching { Math.subtractExact(payload.expiresAt, payload.createdAt) }
            .getOrElse { return IngestResult.Rejected("Invalid packet timing") }
        if (payload.relayPolicy == RelayPolicy.EPHEMERAL && lifetime > PROPAGATION_WINDOW_MS) {
            return IngestResult.Rejected("Ephemeral lifetime exceeds 24 hours")
        }
        if (payload.originSequence <= 0) return IngestResult.Rejected("Invalid origin sequence")

        if (!signer.verify(envelope.packet.payloadBytes, envelope.packet.signature, envelope.packet.originPublicKey)) {
            return IngestResult.Rejected("Invalid signature")
        }
        if (fingerprintNodeId(envelope.packet.originPublicKey) != payload.originNodeId) {
            return IngestResult.Rejected("Origin identity mismatch")
        }

        val packetId = payload.packetId.toString()
        packets.find(packetId)?.let { existing ->
            return handleExisting(existing, envelope.packet, payload)
        }

        val forwarded = envelope.copy(
            ttlRemaining = envelope.ttlRemaining - 1,
            hopCount = envelope.hopCount + 1,
            hopTrace = (envelope.hopTrace + signer.nodeId).distinct().take(MAX_HOP_TRACE),
        )
        val shouldProject = payload.kind == PacketKind.PUBLIC_TEXT && payload.audience == Audience.PublicChannel
        val raw = packetEntity(payload, forwarded, projected = false, receivedAt = clock())
        if (!packets.insert(raw)) {
            val existing = packets.find(packetId) ?: return IngestResult.Rejected("Packet persistence race")
            return handleExisting(existing, envelope.packet, payload)
        }

        if (!shouldProject) return IngestResult.StoredOnly(packetId)
        return projectPublic(payload, forwarded.hopCount)
    }

    suspend fun onHello(profile: NodeProfile): Boolean {
        if (profile.transportVersion != TRANSPORT_VERSION || profile.nodeId != fingerprintNodeId(profile.publicKey)) return false
        peers.upsert(profile)
        return true
    }

    suspend fun inventory(): List<String> = packets.inventoryIds()

    suspend fun requestedPackets(ids: List<String>): List<RelayEnvelope> = packets.findAll(ids).mapNotNull { entity ->
        if (!entity.relayEligible) return@mapNotNull null
        if (entity.relayPolicy == RelayPolicy.EPHEMERAL && entity.expiresAt <= clock()) return@mapNotNull null
        runCatching { ProtocolCodec.decodeEnvelope(entity.rawEnvelope) }.getOrNull()
    }

    suspend fun missingIds(remoteIds: List<String>): List<String> = remoteIds.filter { packets.find(it) == null }

    suspend fun acknowledged(packetId: String, peerId: String) {
        packets.markRelayed(packetId, peerId)
        conversations.markRelayed(packetId)
    }

    suspend fun cleanup() {
        packets.cleanup()
        conversations.cleanup()
    }

    private fun packetEntity(
        payload: PayloadV2,
        envelope: RelayEnvelope,
        projected: Boolean,
        receivedAt: Long,
    ): PacketEntity {
        return PacketEntity(
            packetId = payload.packetId.toString(), kind = payload.kind,
            audienceType = payload.audience.type, audienceId = payload.audience.id, originNodeId = payload.originNodeId,
            originName = payload.originDisplayName, originSequence = payload.originSequence,
            createdAt = payload.createdAt, expiresAt = payload.expiresAt,
            relayPolicy = payload.relayPolicy, supersessionKey = supersessionKey(payload),
            rawEnvelope = ProtocolCodec.encodeEnvelope(envelope), ttlRemaining = envelope.ttlRemaining,
            hopCount = envelope.hopCount, hopTrace = envelope.hopTrace.joinToString(","),
            relayEligible = envelope.ttlRemaining > 0,
            projectionState = if (projected) ProjectionState.PROJECTED else ProjectionState.STORED_ONLY,
            receivedAt = receivedAt,
        )
    }

    private fun conversationEntity(
        payload: PayloadV2,
        originName: String,
        outgoing: Boolean,
        hopCount: Int,
    ): ConversationMessageEntity {
        val body = payload.body as PublicTextBody
        return ConversationMessageEntity(
            messageId = payload.packetId.toString(), conversationId = CHANNEL_ID,
            kind = payload.kind, audienceType = AudienceType.PUBLIC_CHANNEL, audienceId = CHANNEL_ID,
            originNodeId = payload.originNodeId, originName = originName,
            originSequence = payload.originSequence, createdAt = payload.createdAt,
            expiresAt = payload.expiresAt, text = body.text, outgoing = outgoing, hopCount = hopCount,
        )
    }

    private fun supersessionKey(payload: PayloadV2): String? = when (val body = payload.body) {
        is CircleMembershipSnapshotBody -> "circle-snapshot:${body.circleId}"
        else -> null
    }

    private fun sameSignedPacket(existing: PacketEntity, incoming: SignedPacket): Boolean {
        val stored = runCatching { ProtocolCodec.decodeEnvelope(existing.rawEnvelope).packet }.getOrNull() ?: return false
        return stored.payloadBytes.contentEquals(incoming.payloadBytes) &&
            stored.signature.contentEquals(incoming.signature) &&
            stored.originPublicKey.contentEquals(incoming.originPublicKey)
    }

    private suspend fun handleExisting(
        existing: PacketEntity,
        incoming: SignedPacket,
        payload: PayloadV2,
    ): IngestResult {
        if (!sameSignedPacket(existing, incoming)) return IngestResult.Rejected("Packet ID collision")
        val shouldProject = payload.kind == PacketKind.PUBLIC_TEXT && payload.audience == Audience.PublicChannel
        return if (shouldProject && existing.projectionState == ProjectionState.STORED_ONLY) {
            projectPublic(payload, existing.hopCount)
        } else {
            IngestResult.Duplicate(existing.packetId)
        }
    }

    private suspend fun projectPublic(payload: PayloadV2, hopCount: Int): IngestResult {
        val packetId = payload.packetId.toString()
        val peer = peers.find(payload.originNodeId)
        val originName = peer?.displayName
            ?: payload.originDisplayName.take(32).ifBlank { "Node ${payload.originNodeId.take(8)}" }
        val message = conversationEntity(payload, originName, outgoing = false, hopCount = hopCount)
        val inserted = conversations.insert(message)
        if (!inserted) {
            val existing = conversations.find(packetId)
            if (existing == null || !sameProjection(existing, message)) return IngestResult.StoredOnly(packetId)
        }
        if (!packets.markProjected(packetId)) {
            val state = packets.find(packetId)?.projectionState
            if (state != ProjectionState.PROJECTED) return IngestResult.StoredOnly(packetId)
        }
        return IngestResult.Projected(packetId)
    }

    private fun sameProjection(
        existing: ConversationMessageEntity,
        expected: ConversationMessageEntity,
    ): Boolean = existing.copy(
        originName = expected.originName,
        relayed = expected.relayed,
    ) == expected

    private fun fingerprintNodeId(publicKey: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(publicKey).joinToString("") { "%02x".format(it) }.take(32)
}
