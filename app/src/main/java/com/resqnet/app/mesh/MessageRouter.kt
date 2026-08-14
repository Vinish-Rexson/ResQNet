package com.resqnet.app.mesh

import com.resqnet.app.contacts.ContactDirectHandler
import com.resqnet.app.contacts.ContactDirectPort
import com.resqnet.app.data.*
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
    private val contacts: ContactRepository? = null,
    private val receipts: ReceiptRepository? = null,
) : ContactDirectPort {
    private val contactDirect = ContactDirectHandler(this, packets, conversations, peers, contacts, receipts)
    override val localNodeId: String get() = signer.nodeId
    override fun localDisplayName() = displayName()
    override fun now() = clock()

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
        check(projectPublic(payload, hopCount = 0) is IngestResult.Projected) { "Could not project local message" }
        return checkNotNull(conversations.find(payload.packetId.toString())) { "Projected message is missing" }
    }

    suspend fun createDirectMessage(nodeId: String, text: String) = contactDirect.createDirectMessage(nodeId, text)
    suspend fun requestContact(nodeId: String, confirmedFingerprint: String) =
        contactDirect.requestContact(nodeId, confirmedFingerprint)
    suspend fun acceptContact(nodeId: String) = contactDirect.acceptContact(nodeId)
    suspend fun declineContact(nodeId: String) = contactDirect.declineContact(nodeId)
    suspend fun removeContact(nodeId: String) = contactDirect.removeContact(nodeId)
    suspend fun blockContact(nodeId: String) = contactDirect.blockContact(nodeId)
    suspend fun unblockContact(nodeId: String) = contactDirect.unblockContact(nodeId)

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
        val raw = packetEntity(payload, forwarded, projected = false, receivedAt = clock())
        if (!packets.insert(raw)) {
            val existing = packets.find(packetId) ?: return IngestResult.Rejected("Packet persistence race")
            return handleExisting(existing, envelope.packet, payload)
        }

        return projectPayload(payload, envelope.packet.originPublicKey, forwarded.hopCount)
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
        contactDirect.cleanup()
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
        val text = when (val body = payload.body) {
            is PublicTextBody -> body.text
            is DirectTextBody -> body.text
            else -> error("Packet is not a conversation message")
        }
        val conversationId = (payload.body as? DirectTextBody)?.conversationId ?: CHANNEL_ID
        return ConversationMessageEntity(
            messageId = payload.packetId.toString(), conversationId = conversationId,
            kind = payload.kind, audienceType = payload.audience.type, audienceId = payload.audience.id,
            originNodeId = payload.originNodeId, originName = originName,
            originSequence = payload.originSequence, createdAt = payload.createdAt,
            expiresAt = payload.expiresAt, text = text, outgoing = outgoing, hopCount = hopCount,
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
        if (
            payload.kind == PacketKind.DIRECT_TEXT &&
            existing.projectionState == ProjectionState.PROJECTED &&
            (payload.audience as Audience.DirectNode).nodeId == signer.nodeId
        ) {
            contactDirect.recoverReceiptForProjectedDirect(payload)
            return IngestResult.Duplicate(existing.packetId)
        }
        val canRetryProjection = existing.projectionState == ProjectionState.STORED_ONLY && when (payload.kind) {
            PacketKind.PUBLIC_TEXT,
            PacketKind.CONTACT_REQUEST,
            PacketKind.CONTACT_ACCEPT,
            PacketKind.CONTACT_DECLINE,
            PacketKind.DIRECT_TEXT,
            PacketKind.DELIVERY_RECEIPT,
            -> true
            else -> false
        }
        return if (canRetryProjection) {
            projectPayload(payload, incoming.originPublicKey, existing.hopCount)
        } else {
            IngestResult.Duplicate(existing.packetId)
        }
    }

    private suspend fun projectPayload(payload: PayloadV2, originPublicKey: ByteArray, hopCount: Int): IngestResult =
        when (payload.kind) {
            PacketKind.PUBLIC_TEXT -> projectPublic(payload, hopCount)
            else -> contactDirect.project(payload, originPublicKey, hopCount)
        }

    override suspend fun promoteProcessed(packetId: String): IngestResult {
        if (!packets.markProjected(packetId) && packets.find(packetId)?.projectionState != ProjectionState.PROJECTED) {
            return IngestResult.StoredOnly(packetId)
        }
        return IngestResult.Projected(packetId)
    }

    override suspend fun suppress(packetId: String): IngestResult {
        packets.markSuppressed(packetId)
        return IngestResult.StoredOnly(packetId)
    }

    override suspend fun createLocalPacket(
        kind: PacketKind,
        audience: Audience.DirectNode,
        expiresAt: Long,
        packetId: UUID,
        body: (UUID) -> PacketBody,
    ): PayloadV2 {
        val now = clock()
        val payload = PayloadV2(
            packetId, kind, audience, signer.nodeId, displayName(), packets.nextSequence(), now,
            expiresAt, kind.requiredRelayPolicy, body(packetId),
        )
        val payloadBytes = ProtocolCodec.encodePayload(payload)
        val packet = SignedPacket(payloadBytes, signer.sign(payloadBytes), signer.publicKey)
        val envelope = RelayEnvelope(packet, DEFAULT_TTL, 0, listOf(signer.nodeId))
        check(packets.insert(packetEntity(payload, envelope, projected = false, receivedAt = now))) {
            "Packet ID collision"
        }
        return payload
    }

    private suspend fun projectPublic(payload: PayloadV2, hopCount: Int): IngestResult {
        val outgoing = payload.originNodeId == signer.nodeId
        val originName = if (outgoing) {
            displayName()
        } else {
            peers.find(payload.originNodeId)?.displayName
                ?: payload.originDisplayName.take(32).ifBlank { "Node ${payload.originNodeId.take(8)}" }
        }
        return projectConversation(payload, originName, outgoing, hopCount)
    }

    override suspend fun projectConversation(
        payload: PayloadV2,
        originName: String,
        outgoing: Boolean,
        hopCount: Int,
    ): IngestResult {
        val packetId = payload.packetId.toString()
        val message = conversationEntity(payload, originName, outgoing = outgoing, hopCount = hopCount)
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
        delivered = expected.delivered,
    ) == expected

    private fun fingerprintNodeId(publicKey: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(publicKey).joinToString("") { "%02x".format(it) }.take(32)

}
