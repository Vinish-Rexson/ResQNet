package com.resqnet.app.circles

import com.resqnet.app.data.PacketRepository
import com.resqnet.app.mesh.IngestResult
import com.resqnet.app.protocol.*
import java.nio.charset.StandardCharsets
import java.util.UUID

internal class CircleContentHandler(
    private val port: CirclePort,
    private val packets: PacketRepository,
    private val circles: CircleRepository?,
) {
    suspend fun createMessage(circleId: String, text: String): CircleMessageEntity {
        val store = requireNotNull(circles) { "Circle persistence is unavailable" }
        val circle = requireActiveCircle(store, circleId)
        val clean = cleanText(text)
        val payload = port.createLocalCirclePacket(
            PacketKind.CIRCLE_TEXT, Audience.Circle(circleId), port.now() + PROPAGATION_WINDOW_MS,
        ) { CircleTextBody(circleId, circle.currentMembershipVersion, clean) }
        val message = payload.messageEntity(clean, outgoing = true, hopCount = 0)
        check(store.projectMessage(message, port.localNodeId) == CircleStoreResult.PROJECTED) {
            "Circle changed while sending"
        }
        return message
    }

    suspend fun updateStatus(circleId: String, status: SafetyStatus, note: String?): CircleStatusEventEntity {
        val store = requireNotNull(circles) { "Circle persistence is unavailable" }
        val circle = requireActiveCircle(store, circleId)
        val cleanNote = note?.trim()?.takeIf { it.isNotEmpty() }
        require((cleanNote?.toByteArray(Charsets.UTF_8)?.size ?: 0) <= MAX_NOTE_BYTES) {
            "Safety note is longer than 160 UTF-8 bytes"
        }
        val payload = port.createLocalCirclePacket(
            PacketKind.CIRCLE_STATUS, Audience.Circle(circleId), port.now() + PROPAGATION_WINDOW_MS,
        ) { CircleStatusBody(circleId, circle.currentMembershipVersion, port.localNodeId, status, cleanNote) }
        val event = payload.statusEntity(status, cleanNote)
        check(store.projectStatus(event, port.localNodeId) == CircleStoreResult.PROJECTED) {
            "Circle changed while updating status"
        }
        return event
    }

    suspend fun effectiveStatus(circleId: String, memberNodeId: String): EffectiveCircleStatus? {
        val event = circles?.latestStatus(circleId, memberNodeId) ?: return null
        val stale = port.now() - event.createdAt >= PROPAGATION_WINDOW_MS
        return EffectiveCircleStatus(
            if (stale) SafetyStatus.UNKNOWN else event.status,
            event.status, event.note, event.createdAt,
            stale && event.status == SafetyStatus.NEED_HELP,
        )
    }

    suspend fun deliveryProgress(messageId: String): CircleDeliveryProgress {
        val store = requireNotNull(circles) { "Circle persistence is unavailable" }
        val message = requireNotNull(store.message(messageId)) { "Unknown Circle message" }
        val possible = store.members(message.circleId, message.membershipVersion).count {
            it.nodeId != message.originNodeId
        }
        val delivered = store.receipts(messageId).map { it.recipientNodeId }.distinct().size
        return CircleDeliveryProgress(delivered, possible)
    }

    suspend fun project(payload: PayloadV2, hopCount: Int): IngestResult = when (payload.kind) {
        PacketKind.CIRCLE_TEXT -> projectText(payload, hopCount)
        PacketKind.CIRCLE_STATUS -> projectStatus(payload)
        PacketKind.DELIVERY_RECEIPT -> projectReceipt(payload)
        else -> IngestResult.StoredOnly(payload.packetId.toString())
    }

    suspend fun recoverReceipt(payload: PayloadV2) {
        if (payload.kind == PacketKind.CIRCLE_TEXT && payload.originNodeId != port.localNodeId) {
            generateReceiptOnce(payload)
        }
    }

    suspend fun replayPending(circleId: String) {
        val store = circles ?: return
        store.pending(circleId).sortedBy { it.packetId }.forEach { pending ->
            val raw = packets.find(pending.packetId)
            val payload = raw?.let {
                runCatching {
                    ProtocolCodec.decodePayload(ProtocolCodec.decodeEnvelope(it.rawEnvelope).packet.payloadBytes)
                }.getOrNull()
            }
            if (payload != null) when (payload.kind) {
                PacketKind.CIRCLE_TEXT -> projectText(payload, raw.hopCount)
                PacketKind.CIRCLE_STATUS -> projectStatus(payload)
                else -> Unit
            }
            if (payload == null && store.snapshot(pending.circleId, pending.membershipVersion) != null) {
                store.removePending(pending.packetId)
            }
        }
    }

    private suspend fun projectText(payload: PayloadV2, hopCount: Int): IngestResult {
        val store = circles ?: return stored(payload)
        val body = payload.body as CircleTextBody
        val result = store.projectMessage(
            payload.messageEntity(body.text, payload.originNodeId == port.localNodeId, hopCount),
            port.localNodeId,
        )
        if (result == CircleStoreResult.PROJECTED && payload.originNodeId != port.localNodeId) {
            generateReceiptOnce(payload)
        }
        return result.asIngestResult(payload.packetId.toString())
    }

    private suspend fun projectStatus(payload: PayloadV2): IngestResult {
        val store = circles ?: return stored(payload)
        val body = payload.body as CircleStatusBody
        if (body.subjectNodeId != payload.originNodeId) return port.suppressCircle(payload.packetId.toString())
        val result = store.projectStatus(payload.statusEntity(body.status, body.note), port.localNodeId)
        return result.asIngestResult(payload.packetId.toString())
    }

    private suspend fun projectReceipt(payload: PayloadV2): IngestResult {
        val body = payload.body as DeliveryReceiptBody
        if (body.circleId == null) return stored(payload)
        val store = circles ?: return stored(payload)
        if ((payload.audience as Audience.DirectNode).nodeId != port.localNodeId) {
            return port.suppressCircle(payload.packetId.toString())
        }
        val original = store.message(body.messageId) ?: return port.suppressCircle(payload.packetId.toString())
        if (!original.outgoing || original.originNodeId != port.localNodeId || original.circleId != body.circleId ||
            original.membershipVersion != body.membershipVersion
        ) return port.suppressCircle(payload.packetId.toString())
        val members = store.members(body.circleId, requireNotNull(body.membershipVersion))
        if (payload.originNodeId == port.localNodeId || members.none { it.nodeId == payload.originNodeId }) {
            return port.suppressCircle(payload.packetId.toString())
        }
        store.insertReceipt(CircleMessageReceiptEntity(
            body.messageId, payload.originNodeId, payload.packetId.toString(), port.now(),
        ))
        return port.promoteCircleProcessed(payload.packetId.toString())
    }

    private suspend fun generateReceiptOnce(original: PayloadV2) {
        if (original.originNodeId == port.localNodeId) return
        val store = circles ?: return
        val messageId = original.packetId.toString()
        val body = original.body as CircleTextBody
        val claim = store.receipt(messageId, port.localNodeId)
        val receiptId = claim?.receiptPacketId?.let(UUID::fromString)
            ?: deterministicUuid("circle-receipt:$messageId:${port.localNodeId}").also {
                store.insertReceipt(CircleMessageReceiptEntity(messageId, port.localNodeId, it.toString(), port.now()))
            }
        if (packets.find(receiptId.toString()) != null) return
        val receipt = port.createLocalCirclePacket(
            PacketKind.DELIVERY_RECEIPT, Audience.DirectNode(original.originNodeId),
            port.now() + PROPAGATION_WINDOW_MS, receiptId,
        ) { DeliveryReceiptBody(messageId, body.circleId, body.membershipVersion) }
        port.promoteCircleProcessed(receipt.packetId.toString())
    }

    private suspend fun requireActiveCircle(store: CircleRepository, circleId: String): CircleEntity {
        val circle = requireNotNull(store.circle(circleId)) { "Unknown Circle" }
        require(circle.localState in setOf(CircleLocalState.OWNER_ACTIVE, CircleLocalState.ACTIVE)) {
            "Circle is read-only"
        }
        return circle
    }

    private fun cleanText(value: String) = value.trim().also {
        require(it.isNotEmpty()) { "Message cannot be empty" }
        require(it.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES) {
            "Message is longer than 500 UTF-8 bytes"
        }
    }

    private fun PayloadV2.messageEntity(text: String, outgoing: Boolean, hopCount: Int) = CircleMessageEntity(
        packetId.toString(), (body as CircleTextBody).circleId, body.membershipVersion,
        originNodeId, originDisplayName, originSequence, createdAt, text, outgoing, hopCount,
    )

    private fun PayloadV2.statusEntity(status: SafetyStatus, note: String?) = CircleStatusEventEntity(
        packetId.toString(), (body as CircleStatusBody).circleId, body.membershipVersion,
        originNodeId, status, note, originSequence, createdAt,
    )

    private fun deterministicUuid(value: String): UUID =
        UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8))

    private fun stored(payload: PayloadV2) = IngestResult.StoredOnly(payload.packetId.toString())
}
