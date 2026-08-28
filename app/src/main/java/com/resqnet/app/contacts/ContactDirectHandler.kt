package com.resqnet.app.contacts

import com.resqnet.app.data.*
import com.resqnet.app.mesh.IngestResult
import com.resqnet.app.protocol.*
import java.security.MessageDigest
import java.util.UUID

const val DISCOVERY_FRESHNESS_WINDOW_MS = 2L * 60 * 1000

internal interface ContactDirectPort {
    val localNodeId: String
    fun localDisplayName(): String
    fun now(): Long
    suspend fun createLocalPacket(
        kind: PacketKind,
        audience: Audience.DirectNode,
        expiresAt: Long,
        packetId: UUID = UUID.randomUUID(),
        body: (UUID) -> PacketBody,
    ): PayloadV2
    suspend fun createLocalDirectMessage(
        targetNodeId: String,
        conversationId: String,
        text: String,
    ): ConversationMessageEntity
    suspend fun projectConversation(
        payload: PayloadV2,
        originName: String,
        outgoing: Boolean,
        hopCount: Int,
    ): IngestResult
    suspend fun promoteProcessed(packetId: String): IngestResult
    suspend fun suppress(packetId: String): IngestResult
}

internal class ContactDirectHandler(
    private val port: ContactDirectPort,
    private val packets: PacketRepository,
    private val conversations: ConversationRepository,
    private val peers: PeerRepository,
    private val contacts: ContactRepository?,
    private val receipts: ReceiptRepository?,
    private val discoveryFreshnessWindowMs: Long,
) {
    suspend fun createDirectMessage(nodeId: String, text: String): ConversationMessageEntity {
        val clean = text.trim()
        require(clean.isNotEmpty()) { "Message cannot be empty" }
        require(clean.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES) {
            "Message is longer than 500 UTF-8 bytes"
        }
        val contact = requireNotNull(contacts?.find(nodeId)) { "Direct messages require a trusted contact" }
        require(contact.state == ContactState.TRUSTED) { "Direct messages require a trusted, unblocked contact" }
        val conversationId = directConversationId(port.localNodeId, nodeId)
        return port.createLocalDirectMessage(nodeId, conversationId, clean)
    }

    suspend fun requestContact(nodeId: String, confirmedFingerprint: String): ContactEntity {
        val contactStore = requireNotNull(contacts) { "Contact persistence is unavailable" }
        val peer = requireNotNull(peers.find(nodeId)) { "Contact requests require a currently discovered peer" }
        val discoveryAge = port.now() - peer.lastSeenAt
        require(discoveryAge in 0..discoveryFreshnessWindowMs) { "Peer discovery is stale" }
        require(peer.fingerprint == confirmedFingerprint) { "Displayed fingerprint was not confirmed" }
        currentContact(nodeId)?.let { throw IllegalStateException("A contact state already exists for this node") }
        val payload = port.createLocalPacket(
            PacketKind.CONTACT_REQUEST, Audience.DirectNode(nodeId), port.now() + PROPAGATION_WINDOW_MS,
        ) { packetId -> ContactRequestBody(packetId.toString(), port.localDisplayName()) }
        val contact = ContactEntity(
            nodeId, peer.displayName, peer.publicKey, peer.fingerprint, ContactState.PENDING_OUTGOING,
            payload.packetId.toString(), payload.expiresAt, null, null, port.now(),
        )
        contactStore.upsert(contact)
        packets.markProjected(payload.packetId.toString())
        return contact
    }

    suspend fun acceptContact(nodeId: String): ContactEntity {
        val contactStore = requireNotNull(contacts) { "Contact persistence is unavailable" }
        val existing = requireNotNull(currentContact(nodeId)) { "No pending contact request" }
        require(
            existing.state == ContactState.PENDING_INCOMING &&
                existing.incomingRequestId != null &&
                (existing.incomingRequestExpiresAt ?: Long.MIN_VALUE) > port.now()
        ) {
            "No active incoming contact request"
        }
        val requestId = requireNotNull(existing.incomingRequestId)
        val payload = port.createLocalPacket(
            PacketKind.CONTACT_ACCEPT, Audience.DirectNode(nodeId), port.now() + PROPAGATION_WINDOW_MS,
        ) { ContactAcceptBody(requestId) }
        val trusted = existing.copy(
            state = ContactState.TRUSTED,
            outgoingRequestId = null, outgoingRequestExpiresAt = null,
            incomingRequestId = null, incomingRequestExpiresAt = null,
            updatedAt = port.now(),
        )
        contactStore.upsert(trusted)
        packets.markProjected(payload.packetId.toString())
        return trusted
    }

    suspend fun declineContact(nodeId: String) {
        val contactStore = requireNotNull(contacts) { "Contact persistence is unavailable" }
        val existing = requireNotNull(currentContact(nodeId)) { "No pending contact request" }
        require(
            existing.state == ContactState.PENDING_INCOMING &&
                existing.incomingRequestId != null &&
                (existing.incomingRequestExpiresAt ?: Long.MIN_VALUE) > port.now()
        ) {
            "No active incoming contact request"
        }
        val requestId = requireNotNull(existing.incomingRequestId)
        val payload = port.createLocalPacket(
            PacketKind.CONTACT_DECLINE, Audience.DirectNode(nodeId), port.now() + PROPAGATION_WINDOW_MS,
        ) { ContactDeclineBody(requestId) }
        val remaining = existing.copy(
            incomingRequestId = null,
            incomingRequestExpiresAt = null,
        ).withoutExpiredRequests(port.now())
        if (remaining == null) contactStore.delete(nodeId) else contactStore.upsert(remaining)
        packets.markProjected(payload.packetId.toString())
    }

    suspend fun removeContact(nodeId: String) =
        requireNotNull(contacts) { "Contact persistence is unavailable" }.delete(nodeId)

    suspend fun blockContact(nodeId: String): ContactEntity {
        val contactStore = requireNotNull(contacts) { "Contact persistence is unavailable" }
        val existing = contactStore.find(nodeId)
        val peer = if (existing == null) requireNotNull(peers.find(nodeId)) { "Unknown node" } else null
        return ContactEntity(
            nodeId, existing?.displayName ?: peer!!.displayName,
            existing?.publicKey ?: peer!!.publicKey, existing?.fingerprint ?: peer!!.fingerprint,
            ContactState.BLOCKED, null, null, null, null, port.now(),
        ).also { contactStore.upsert(it) }
    }

    suspend fun unblockContact(nodeId: String) {
        val contactStore = requireNotNull(contacts) { "Contact persistence is unavailable" }
        require(contactStore.find(nodeId)?.state == ContactState.BLOCKED) { "Node is not blocked" }
        contactStore.delete(nodeId)
    }

    suspend fun cleanup() = contacts?.deleteExpiredPending(port.now())

    suspend fun project(payload: PayloadV2, originPublicKey: ByteArray, hopCount: Int): IngestResult =
        when (payload.kind) {
            PacketKind.CONTACT_REQUEST -> projectContactRequest(payload, originPublicKey)
            PacketKind.CONTACT_ACCEPT -> projectContactAccept(payload)
            PacketKind.CONTACT_DECLINE -> projectContactDecline(payload)
            PacketKind.DIRECT_TEXT -> projectDirect(payload, originPublicKey, hopCount)
            PacketKind.DELIVERY_RECEIPT -> projectReceipt(payload)
            else -> IngestResult.StoredOnly(payload.packetId.toString())
        }

    suspend fun recoverReceiptForProjectedDirect(payload: PayloadV2) {
        if ((payload.audience as Audience.DirectNode).nodeId == port.localNodeId) generateReceiptOnce(payload)
    }

    private suspend fun projectContactRequest(payload: PayloadV2, originPublicKey: ByteArray): IngestResult {
        val packetId = payload.packetId.toString()
        val contactStore = contacts ?: return IngestResult.StoredOnly(packetId)
        if ((payload.audience as Audience.DirectNode).nodeId != port.localNodeId) return IngestResult.StoredOnly(packetId)
        val body = payload.body as ContactRequestBody
        if (body.requestId != packetId) return port.suppress(packetId)
        val current = currentContact(payload.originNodeId)
        if (current?.state == ContactState.BLOCKED) return port.suppress(packetId)
        val contact = when (current?.state) {
            ContactState.TRUSTED -> current
            ContactState.PENDING_INCOMING -> current.copy(
                incomingRequestId = packetId,
                incomingRequestExpiresAt = payload.expiresAt, updatedAt = port.now(),
            )
            ContactState.PENDING_OUTGOING -> current.copy(
                state = ContactState.PENDING_INCOMING, incomingRequestId = packetId,
                incomingRequestExpiresAt = payload.expiresAt, updatedAt = port.now(),
            )
            null -> ContactEntity(
                payload.originNodeId, body.requesterName, originPublicKey, displayFingerprint(originPublicKey),
                ContactState.PENDING_INCOMING, null, null, packetId, payload.expiresAt, port.now(),
            )
            ContactState.BLOCKED -> return port.suppress(packetId)
        }
        contactStore.upsert(contact)
        return port.promoteProcessed(packetId)
    }

    private suspend fun projectContactAccept(payload: PayloadV2): IngestResult {
        val packetId = payload.packetId.toString()
        val contactStore = contacts ?: return IngestResult.StoredOnly(packetId)
        if ((payload.audience as Audience.DirectNode).nodeId != port.localNodeId) return IngestResult.StoredOnly(packetId)
        val current = currentContact(payload.originNodeId)
        if (current == null) {
            com.resqnet.app.mesh.MeshRuntime.event("Accept suppressed: no current contact")
            return port.suppress(packetId)
        }
        val requestId = (payload.body as ContactAcceptBody).requestId
        if (current.state == ContactState.BLOCKED) {
            com.resqnet.app.mesh.MeshRuntime.event("Accept suppressed: blocked")
            return port.suppress(packetId)
        }
        if (current.outgoingRequestId != requestId) {
            com.resqnet.app.mesh.MeshRuntime.event("Accept suppressed: id mismatch. current=${current.outgoingRequestId}, req=$requestId")
            return port.suppress(packetId)
        }
        if ((current.outgoingRequestExpiresAt ?: Long.MIN_VALUE) <= port.now()) {
            com.resqnet.app.mesh.MeshRuntime.event("Accept suppressed: expired")
            return port.suppress(packetId)
        }
        contactStore.upsert(current.copy(
            state = ContactState.TRUSTED,
            outgoingRequestId = null, outgoingRequestExpiresAt = null,
            incomingRequestId = null, incomingRequestExpiresAt = null,
            updatedAt = port.now(),
        ))
        com.resqnet.app.mesh.MeshRuntime.event("Accept projected successfully")
        return port.promoteProcessed(packetId)
    }

    private suspend fun projectContactDecline(payload: PayloadV2): IngestResult {
        val packetId = payload.packetId.toString()
        val contactStore = contacts ?: return IngestResult.StoredOnly(packetId)
        if ((payload.audience as Audience.DirectNode).nodeId != port.localNodeId) return IngestResult.StoredOnly(packetId)
        val current = currentContact(payload.originNodeId) ?: return port.suppress(packetId)
        val requestId = (payload.body as ContactDeclineBody).requestId
        if (
            current.state == ContactState.BLOCKED || current.outgoingRequestId != requestId ||
            (current.outgoingRequestExpiresAt ?: Long.MIN_VALUE) <= port.now()
        ) {
            return port.suppress(packetId)
        }
        val remaining = current.copy(
            outgoingRequestId = null,
            outgoingRequestExpiresAt = null,
        ).withoutExpiredRequests(port.now())
        if (remaining == null) contactStore.delete(payload.originNodeId) else contactStore.upsert(remaining)
        return port.promoteProcessed(packetId)
    }

    private suspend fun projectDirect(payload: PayloadV2, originPublicKey: ByteArray, hopCount: Int): IngestResult {
        val packetId = payload.packetId.toString()
        val contactStore = contacts ?: return IngestResult.StoredOnly(packetId)
        if ((payload.audience as Audience.DirectNode).nodeId != port.localNodeId) return IngestResult.StoredOnly(packetId)
        val contact = contactStore.find(payload.originNodeId) ?: return port.suppress(packetId)
        if (contact.state != ContactState.TRUSTED || !contact.publicKey.contentEquals(originPublicKey)) {
            return port.suppress(packetId)
        }
        val body = payload.body as DirectTextBody
        if (body.conversationId != directConversationId(port.localNodeId, payload.originNodeId)) {
            return port.suppress(packetId)
        }
        val projected = port.projectConversation(payload, contact.displayName, false, hopCount)
        if (projected !is IngestResult.Projected) return projected
        generateReceiptOnce(payload)
        return projected
    }

    private suspend fun generateReceiptOnce(original: PayloadV2) {
        val receiptStore = receipts ?: return
        val messageId = original.packetId.toString()
        val targetNodeId = original.originNodeId
        var claim = receiptStore.find(messageId, targetNodeId)
        if (claim == null) {
            val receiptPacketId = UUID.randomUUID()
            receiptStore.insert(MessageReceiptEntity(messageId, targetNodeId, receiptPacketId.toString(), port.now()))
            claim = receiptStore.find(messageId, targetNodeId) ?: return
        }
        if (packets.find(claim.receiptPacketId) != null) return
        val receiptPacketId = UUID.fromString(claim.receiptPacketId)
        val receiptPayload = port.createLocalPacket(
            PacketKind.DELIVERY_RECEIPT, Audience.DirectNode(targetNodeId),
            port.now() + PROPAGATION_WINDOW_MS, receiptPacketId,
        ) { DeliveryReceiptBody(messageId) }
        packets.markProjected(receiptPayload.packetId.toString())
    }

    private suspend fun projectReceipt(payload: PayloadV2): IngestResult {
        val packetId = payload.packetId.toString()
        if ((payload.audience as Audience.DirectNode).nodeId != port.localNodeId) return port.suppress(packetId)
        val body = payload.body as DeliveryReceiptBody
        val original = conversations.find(body.messageId) ?: return port.suppress(packetId)
        if (
            original.kind != PacketKind.DIRECT_TEXT || !original.outgoing ||
            original.originNodeId != port.localNodeId || original.audienceId != payload.originNodeId
        ) return port.suppress(packetId)
        receipts?.insert(MessageReceiptEntity(body.messageId, payload.originNodeId, packetId, port.now()))
        conversations.markDelivered(body.messageId)
        return port.promoteProcessed(packetId)
    }

    private suspend fun currentContact(nodeId: String): ContactEntity? {
        val contactStore = contacts ?: return null
        val current = contactStore.find(nodeId) ?: return null
        val normalized = current.withoutExpiredRequests(port.now())
        if (normalized == null) {
            contactStore.delete(nodeId)
        } else if (normalized != current) {
            contactStore.upsert(normalized)
        }
        return normalized
    }

    private fun displayFingerprint(publicKey: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(publicKey).joinToString("") { "%02x".format(it) }.take(16).chunked(4).joinToString("-")
}
