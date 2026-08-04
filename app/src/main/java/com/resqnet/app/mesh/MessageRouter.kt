package com.resqnet.app.mesh

import com.resqnet.app.data.MessageEntity
import com.resqnet.app.data.MessageRepository
import com.resqnet.app.data.PeerRepository
import com.resqnet.app.protocol.*
import com.resqnet.app.security.IdentitySigner
import java.util.UUID

sealed interface IngestResult {
    data class Accepted(val messageId: String) : IngestResult
    data class Duplicate(val messageId: String) : IngestResult
    data class Rejected(val reason: String) : IngestResult
}

class MessageRouter(
    private val messages: MessageRepository,
    private val peers: PeerRepository,
    private val signer: IdentitySigner,
    private val displayName: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun localProfile() = NodeProfile(signer.nodeId, displayName(), signer.publicKey, signer.fingerprint)

    suspend fun createMessage(text: String): MessageEntity {
        val clean = text.trim()
        require(clean.isNotEmpty()) { "Message cannot be empty" }
        require(clean.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES) { "Message is longer than 500 UTF-8 bytes" }
        val now = clock()
        val payload = ChatPayload(UUID.randomUUID(), originNodeId = signer.nodeId,
            originDisplayName = displayName(),
            originSequence = messages.nextSequence(), createdAt = now, expiresAt = now + PROPAGATION_WINDOW_MS, text = clean)
        val payloadBytes = ProtocolCodec.encodePayload(payload)
        val packet = SignedChatPacket(payloadBytes, signer.sign(payloadBytes), signer.publicKey)
        val envelope = RelayEnvelope(packet, DEFAULT_TTL, 0, listOf(signer.nodeId))
        val entity = entity(payload, envelope, displayName(), outgoing = true)
        check(messages.insert(entity)) { "Message ID collision" }
        return entity
    }

    suspend fun ingest(envelope: RelayEnvelope, fromPeerId: String): IngestResult {
        if (envelope.ttlRemaining <= 0) return IngestResult.Rejected("TTL exhausted")
        val payload = runCatching { ProtocolCodec.decodePayload(envelope.packet.payloadBytes) }
            .getOrElse { return IngestResult.Rejected("Malformed payload: ${it.message}") }
        if (payload.protocolVersion != PROTOCOL_VERSION) return IngestResult.Rejected("Unsupported protocol ${payload.protocolVersion}")
        if (payload.channelId != CHANNEL_ID) return IngestResult.Rejected("Unknown channel")
        if (payload.expiresAt <= clock()) return IngestResult.Rejected("Message expired")
        if (payload.text.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES) return IngestResult.Rejected("Message too large")
        if (!signer.verify(envelope.packet.payloadBytes, envelope.packet.signature, envelope.packet.originPublicKey))
            return IngestResult.Rejected("Invalid signature")
        val expectedNode = fingerprintNodeId(envelope.packet.originPublicKey)
        if (expectedNode != payload.originNodeId) return IngestResult.Rejected("Origin identity mismatch")
        if (messages.find(payload.messageId.toString()) != null) return IngestResult.Duplicate(payload.messageId.toString())
        val peer = peers.find(payload.originNodeId)
        val originName = peer?.displayName ?: payload.originDisplayName.take(32).ifBlank { "Node ${payload.originNodeId.take(8)}" }
        val forwarded = envelope.copy(
            ttlRemaining = envelope.ttlRemaining - 1,
            hopCount = envelope.hopCount + 1,
            hopTrace = (envelope.hopTrace + signer.nodeId).distinct().take(32),
        )
        messages.insert(entity(payload, forwarded, originName, outgoing = false))
        return IngestResult.Accepted(payload.messageId.toString())
    }

    suspend fun onHello(profile: NodeProfile): Boolean {
        if (profile.protocolVersion != PROTOCOL_VERSION || profile.nodeId != fingerprintNodeId(profile.publicKey)) return false
        peers.upsert(profile); return true
    }

    suspend fun inventory(): List<String> = messages.recentIds()
    suspend fun requestedPackets(ids: List<String>): List<RelayEnvelope> = messages.findAll(ids).mapNotNull { entity ->
        runCatching { ProtocolCodec.decodeEnvelope(entity.packetBytes) }.getOrNull()
    }
    suspend fun missingIds(remoteIds: List<String>): List<String> = remoteIds.filter { messages.find(it) == null }
    suspend fun acknowledged(messageId: String, peerId: String) = messages.markRelayed(messageId, peerId)
    suspend fun cleanup() = messages.cleanup()

    private fun entity(payload: ChatPayload, envelope: RelayEnvelope, name: String, outgoing: Boolean) = MessageEntity(
        messageId = payload.messageId.toString(), channelId = payload.channelId, originNodeId = payload.originNodeId,
        originName = name, originSequence = payload.originSequence, createdAt = payload.createdAt,
        expiresAt = payload.expiresAt, text = payload.text, packetBytes = ProtocolCodec.encodeEnvelope(envelope),
        ttlRemaining = envelope.ttlRemaining, hopCount = envelope.hopCount,
        hopTrace = envelope.hopTrace.joinToString(","), outgoing = outgoing,
    )

    private fun fingerprintNodeId(publicKey: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(publicKey).joinToString("") { "%02x".format(it) }.take(32)
}
