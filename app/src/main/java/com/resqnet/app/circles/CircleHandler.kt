package com.resqnet.app.circles

import com.resqnet.app.data.ContactRepository
import com.resqnet.app.data.ContactState
import com.resqnet.app.data.PacketRepository
import com.resqnet.app.mesh.IngestResult
import com.resqnet.app.protocol.*
import java.nio.charset.StandardCharsets
import java.util.UUID

internal interface CirclePort {
    val localNodeId: String
    fun localDisplayName(): String
    fun now(): Long
    suspend fun createLocalCirclePacket(
        kind: PacketKind,
        audience: Audience,
        expiresAt: Long,
        packetId: UUID = UUID.randomUUID(),
        body: () -> PacketBody,
    ): PayloadV2
    suspend fun promoteCircleProcessed(packetId: String): IngestResult
    suspend fun suppressCircle(packetId: String): IngestResult
}

internal class CircleHandler(
    private val port: CirclePort,
    private val packets: PacketRepository,
    private val contacts: ContactRepository?,
    private val circles: CircleRepository?,
) {
    suspend fun createCircle(name: String): CircleEntity {
        val store = requireNotNull(circles) { "Circle persistence is unavailable" }
        val clean = cleanName(name)
        val circleId = UUID.randomUUID().toString()
        val members = listOf(CircleSnapshotMember(port.localNodeId, CircleMemberRole.OWNER))
        val payload = port.createLocalCirclePacket(
            PacketKind.CIRCLE_MEMBERSHIP_SNAPSHOT, Audience.Circle(circleId), Long.MAX_VALUE,
        ) { CircleMembershipSnapshotBody(circleId, 1, clean, port.localNodeId, members) }
        check(store.insertSnapshot(payload.snapshotEntity(), members.toEntities(circleId, 1)))
        packets.supersede("circle-snapshot:$circleId", payload.packetId.toString())
        return CircleEntity(
            circleId, clean, port.localNodeId, 1, CircleLocalState.OWNER_ACTIVE,
            null, port.now(), port.now(),
        ).also {
            store.upsertCircle(it)
            port.promoteCircleProcessed(payload.packetId.toString())
        }
    }

    suspend fun invite(circleId: String, targetNodeId: String): CircleInvitationEntity {
        val store = requireNotNull(circles) { "Circle persistence is unavailable" }
        val circle = requireOwnerCircle(store, circleId)
        val contact = requireNotNull(contacts?.find(targetNodeId)) { "Circle invitations require a trusted contact" }
        require(contact.state == ContactState.TRUSTED) { "Circle invitations require a trusted, unblocked contact" }
        val members = store.members(circleId, circle.currentMembershipVersion)
        require(members.none { it.nodeId == targetNodeId }) { "Node is already a Circle member" }
        val pendingInvites = store.invitationsForCircle(circleId).count {
            it.state == CircleInvitationState.PENDING && it.expiresAt > port.now()
        }
        check(members.size + pendingInvites < MAX_CIRCLE_MEMBERS) { "Circle membership is full" }
        val inviteId = UUID.randomUUID().toString()
        val expiresAt = port.now() + PROPAGATION_WINDOW_MS
        val preview = members.map { CircleSnapshotMember(it.nodeId, it.role) }
        val payload = port.createLocalCirclePacket(
            PacketKind.CIRCLE_INVITE, Audience.DirectNode(targetNodeId), expiresAt,
        ) {
            CircleInviteBody(
                inviteId, circleId, circle.name, port.localNodeId,
                circle.currentMembershipVersion, expiresAt, preview,
            )
        }
        return CircleInvitationEntity(
            inviteId, payload.packetId.toString(), circleId, circle.name, port.localNodeId,
            targetNodeId, circle.currentMembershipVersion, expiresAt, CircleInvitationState.PENDING,
            null, encodeCircleMembers(preview), payload.createdAt, port.now(),
        ).also {
            store.upsertInvitation(it)
            port.promoteCircleProcessed(payload.packetId.toString())
        }
    }

    suspend fun acceptInvite(inviteId: String): String {
        val store = requireNotNull(circles) { "Circle persistence is unavailable" }
        val invite = requireNotNull(store.invitation(inviteId)) { "Unknown Circle invitation" }
        invite.acceptancePacketId?.let { return it }
        require(invite.targetNodeId == port.localNodeId && invite.state == CircleInvitationState.PENDING) {
            "Invitation is not active"
        }
        if (port.now() >= invite.expiresAt) {
            store.upsertInvitation(invite.copy(state = CircleInvitationState.EXPIRED, updatedAt = port.now()))
            throw IllegalArgumentException("Invitation expired")
        }
        val packetId = deterministicUuid("circle-accept:$inviteId:${port.localNodeId}")
        val payload = port.createLocalCirclePacket(
            PacketKind.CIRCLE_INVITE_ACCEPT, Audience.DirectNode(invite.ownerNodeId), Long.MAX_VALUE, packetId,
        ) { CircleInviteAcceptBody(inviteId, invite.circleId) }
        store.upsertInvitation(invite.copy(
            state = CircleInvitationState.ACCEPTANCE_PENDING,
            acceptancePacketId = payload.packetId.toString(), updatedAt = port.now(),
        ))
        store.circle(invite.circleId)?.let {
            store.upsertCircle(it.copy(localState = CircleLocalState.ACCEPTANCE_PENDING, updatedAt = port.now()))
        }
        port.promoteCircleProcessed(payload.packetId.toString())
        return payload.packetId.toString()
    }

    suspend fun declineInvite(inviteId: String) {
        val store = requireNotNull(circles) { "Circle persistence is unavailable" }
        val invite = requireNotNull(store.invitation(inviteId)) { "Unknown Circle invitation" }
        require(invite.targetNodeId == port.localNodeId && invite.state == CircleInvitationState.PENDING) {
            "Invitation is not active"
        }
        require(port.now() < invite.expiresAt) { "Invitation expired" }
        val payload = port.createLocalCirclePacket(
            PacketKind.CIRCLE_INVITE_DECLINE, Audience.DirectNode(invite.ownerNodeId),
            port.now() + PROPAGATION_WINDOW_MS,
        ) { CircleInviteDeclineBody(inviteId, invite.circleId) }
        store.upsertInvitation(invite.copy(state = CircleInvitationState.DECLINED, updatedAt = port.now()))
        port.promoteCircleProcessed(payload.packetId.toString())
    }

    suspend fun createMessage(circleId: String, text: String): CircleMessageEntity {
        val store = requireNotNull(circles) { "Circle persistence is unavailable" }
        val circle = requireActiveCircle(store, circleId)
        val clean = cleanText(text)
        val version = circle.currentMembershipVersion
        check(store.members(circleId, version).any { it.nodeId == port.localNodeId })
        val payload = port.createLocalCirclePacket(
            PacketKind.CIRCLE_TEXT, Audience.Circle(circleId), port.now() + PROPAGATION_WINDOW_MS,
        ) { CircleTextBody(circleId, version, clean) }
        val message = payload.messageEntity(clean, outgoing = true, hopCount = 0)
        check(store.insertMessage(message)) { "Circle message ID collision" }
        port.promoteCircleProcessed(payload.packetId.toString())
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
        check(store.insertStatus(event))
        port.promoteCircleProcessed(payload.packetId.toString())
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
        val possible = store.members(message.circleId, message.membershipVersion).count { it.nodeId != message.originNodeId }
        val delivered = store.receipts(messageId).map { it.recipientNodeId }.distinct().size
        return CircleDeliveryProgress(delivered, possible)
    }

    suspend fun leave(circleId: String): String {
        val store = requireNotNull(circles) { "Circle persistence is unavailable" }
        store.circle(circleId)?.leaveRequestPacketId?.let { return it }
        val circle = requireActiveCircle(store, circleId)
        require(circle.localState == CircleLocalState.ACTIVE) { "Circle owner cannot leave" }
        val packetId = deterministicUuid("circle-leave:$circleId:${port.localNodeId}:${circle.currentMembershipVersion}")
        val payload = port.createLocalCirclePacket(
            PacketKind.CIRCLE_LEAVE_REQUEST, Audience.DirectNode(circle.ownerNodeId), Long.MAX_VALUE, packetId,
        ) { CircleLeaveRequestBody(circleId) }
        store.upsertCircle(circle.copy(
            localState = CircleLocalState.LEAVE_PENDING,
            leaveRequestPacketId = payload.packetId.toString(), updatedAt = port.now(),
        ))
        port.promoteCircleProcessed(payload.packetId.toString())
        return payload.packetId.toString()
    }

    suspend fun rename(circleId: String, name: String) {
        val store = requireNotNull(circles)
        val circle = requireOwnerCircle(store, circleId)
        publishOwnerSnapshot(circle, cleanName(name), store.members(circleId, circle.currentMembershipVersion), false)
    }

    suspend fun removeMember(circleId: String, nodeId: String) {
        val store = requireNotNull(circles)
        val circle = requireOwnerCircle(store, circleId)
        require(nodeId != port.localNodeId) { "Owner cannot be removed" }
        val members = store.members(circleId, circle.currentMembershipVersion)
        require(members.any { it.nodeId == nodeId }) { "Node is not an active member" }
        publishOwnerSnapshot(circle, circle.name, members.filterNot { it.nodeId == nodeId }, false)
    }

    suspend fun dissolve(circleId: String) {
        val store = requireNotNull(circles)
        val circle = requireOwnerCircle(store, circleId)
        publishOwnerSnapshot(circle, circle.name, store.members(circleId, circle.currentMembershipVersion), true)
    }

    suspend fun project(payload: PayloadV2, hopCount: Int): IngestResult = when (payload.kind) {
        PacketKind.CIRCLE_INVITE -> projectInvite(payload)
        PacketKind.CIRCLE_INVITE_ACCEPT -> projectAccept(payload)
        PacketKind.CIRCLE_INVITE_DECLINE -> projectDecline(payload)
        PacketKind.CIRCLE_MEMBERSHIP_SNAPSHOT -> projectSnapshot(payload)
        PacketKind.CIRCLE_TEXT -> projectText(payload, hopCount)
        PacketKind.CIRCLE_STATUS -> projectStatus(payload)
        PacketKind.CIRCLE_LEAVE_REQUEST -> projectLeave(payload)
        PacketKind.DELIVERY_RECEIPT -> projectReceipt(payload)
        else -> IngestResult.StoredOnly(payload.packetId.toString())
    }

    suspend fun recoverReceipt(payload: PayloadV2) {
        if (payload.kind == PacketKind.CIRCLE_TEXT) generateReceiptOnce(payload)
    }

    private suspend fun projectInvite(payload: PayloadV2): IngestResult {
        val store = circles ?: return stored(payload)
        if ((payload.audience as Audience.DirectNode).nodeId != port.localNodeId) return stored(payload)
        val body = payload.body as CircleInviteBody
        if (!validUuid(body.inviteId) || !validUuid(body.circleId) || body.ownerNodeId != payload.originNodeId ||
            body.expiresAt != payload.expiresAt || body.membershipVersion <= 0 ||
            !validMembers(body.activeMemberPreview, body.ownerNodeId)
        ) return suppress(payload)
        val invitation = CircleInvitationEntity(
            body.inviteId, payload.packetId.toString(), body.circleId, body.circleName, body.ownerNodeId,
            port.localNodeId, body.membershipVersion, body.expiresAt,
            if (port.now() >= body.expiresAt) CircleInvitationState.EXPIRED else CircleInvitationState.PENDING,
            null, encodeCircleMembers(body.activeMemberPreview), payload.createdAt, port.now(),
        )
        store.upsertInvitation(invitation)
        val existing = store.circle(body.circleId)
        if (existing == null) store.upsertCircle(CircleEntity(
            body.circleId, body.circleName, body.ownerNodeId, body.membershipVersion,
            CircleLocalState.INVITED, null, payload.createdAt, port.now(),
        ))
        return port.promoteCircleProcessed(payload.packetId.toString())
    }

    private suspend fun projectAccept(payload: PayloadV2): IngestResult {
        val store = circles ?: return stored(payload)
        if ((payload.audience as Audience.DirectNode).nodeId != port.localNodeId) return stored(payload)
        val body = payload.body as CircleInviteAcceptBody
        val invite = store.invitation(body.inviteId) ?: return suppress(payload)
        if (invite.ownerNodeId != port.localNodeId || invite.targetNodeId != payload.originNodeId ||
            invite.circleId != body.circleId || payload.createdAt >= invite.expiresAt ||
            invite.state !in setOf(CircleInvitationState.PENDING, CircleInvitationState.ACCEPTED)
        ) return suppress(payload)
        if (invite.state == CircleInvitationState.ACCEPTED) {
            packets.resolve(payload.packetId.toString())
            return port.promoteCircleProcessed(payload.packetId.toString())
        }
        val circle = requireOwnerCircle(store, body.circleId)
        val members = store.members(body.circleId, circle.currentMembershipVersion)
        if (members.any { it.nodeId == payload.originNodeId }) {
            store.upsertInvitation(invite.copy(state = CircleInvitationState.ACCEPTED, updatedAt = port.now()))
            packets.resolve(payload.packetId.toString())
            return port.promoteCircleProcessed(payload.packetId.toString())
        }
        if (members.size >= MAX_CIRCLE_MEMBERS) return suppress(payload)
        publishOwnerSnapshot(circle, circle.name, members + CircleMemberEntity(
            body.circleId, circle.currentMembershipVersion, payload.originNodeId, CircleMemberRole.MEMBER,
        ), false)
        store.upsertInvitation(invite.copy(state = CircleInvitationState.ACCEPTED, updatedAt = port.now()))
        packets.resolve(payload.packetId.toString())
        return port.promoteCircleProcessed(payload.packetId.toString())
    }

    private suspend fun projectDecline(payload: PayloadV2): IngestResult {
        val store = circles ?: return stored(payload)
        if ((payload.audience as Audience.DirectNode).nodeId != port.localNodeId) return stored(payload)
        val body = payload.body as CircleInviteDeclineBody
        val invite = store.invitation(body.inviteId) ?: return suppress(payload)
        if (invite.ownerNodeId != port.localNodeId || invite.targetNodeId != payload.originNodeId ||
            invite.circleId != body.circleId || invite.state != CircleInvitationState.PENDING ||
            payload.createdAt >= invite.expiresAt
        ) {
            return suppress(payload)
        }
        store.upsertInvitation(invite.copy(state = CircleInvitationState.DECLINED, updatedAt = port.now()))
        return port.promoteCircleProcessed(payload.packetId.toString())
    }

    private suspend fun projectSnapshot(payload: PayloadV2): IngestResult {
        val store = circles ?: return stored(payload)
        val body = payload.body as CircleMembershipSnapshotBody
        if (!validUuid(body.circleId) || body.membershipVersion <= 0 || body.ownerNodeId != payload.originNodeId ||
            !validMembers(body.members, body.ownerNodeId)
        ) return suppress(payload)
        val currentSnapshot = store.latestSnapshot(body.circleId)
        if (currentSnapshot != null && currentSnapshot.ownerNodeId != payload.originNodeId) return suppress(payload)
        if (currentSnapshot != null && body.membershipVersion <= currentSnapshot.membershipVersion) return stored(payload)
        val existingCircle = store.circle(body.circleId)
        if (existingCircle != null && existingCircle.ownerNodeId != payload.originNodeId) return suppress(payload)
        val localInSnapshot = body.members.any { it.nodeId == port.localNodeId }
        if (existingCircle == null && !localInSnapshot) return stored(payload)
        val memberEntities = body.members.toEntities(body.circleId, body.membershipVersion)
        if (!store.insertSnapshot(payload.snapshotEntity(), memberEntities)) return stored(payload)
        packets.supersede("circle-snapshot:${body.circleId}", payload.packetId.toString())
        val localState = when {
            body.dissolved -> CircleLocalState.ARCHIVED_DISSOLVED
            existingCircle?.localState in setOf(CircleLocalState.ARCHIVED_REMOVED, CircleLocalState.ARCHIVED_DISSOLVED) -> existingCircle!!.localState
            body.ownerNodeId == port.localNodeId -> CircleLocalState.OWNER_ACTIVE
            existingCircle?.localState == CircleLocalState.LEAVE_PENDING && localInSnapshot -> CircleLocalState.LEAVE_PENDING
            localInSnapshot -> CircleLocalState.ACTIVE
            existingCircle != null -> CircleLocalState.ARCHIVED_REMOVED
            else -> return stored(payload)
        }
        store.upsertCircle(CircleEntity(
            body.circleId, body.circleName, body.ownerNodeId, body.membershipVersion, localState,
            existingCircle?.leaveRequestPacketId, existingCircle?.createdAt ?: payload.createdAt, port.now(),
        ))
        if (localInSnapshot) {
            store.invitationsForCircle(body.circleId).filter {
                it.targetNodeId == port.localNodeId && it.acceptancePacketId != null &&
                    it.state == CircleInvitationState.ACCEPTANCE_PENDING
            }.forEach {
                packets.resolve(requireNotNull(it.acceptancePacketId))
                store.upsertInvitation(it.copy(state = CircleInvitationState.ACCEPTED, updatedAt = port.now()))
            }
        } else if (existingCircle?.localState == CircleLocalState.LEAVE_PENDING) {
            existingCircle.leaveRequestPacketId?.let { packets.resolve(it) }
        }
        val result = port.promoteCircleProcessed(payload.packetId.toString())
        replayPending(body.circleId)
        return result
    }

    private suspend fun projectText(payload: PayloadV2, hopCount: Int): IngestResult {
        val store = circles ?: return stored(payload)
        val body = payload.body as CircleTextBody
        val snapshot = store.snapshot(body.circleId, body.membershipVersion)
        if (snapshot == null) {
            store.addPending(PendingCirclePacketEntity(payload.packetId.toString(), body.circleId, body.membershipVersion, payload.kind))
            return stored(payload)
        }
        val circle = store.circle(body.circleId) ?: return suppress(payload)
        if (circle.localState in setOf(CircleLocalState.LEAVE_PENDING, CircleLocalState.ARCHIVED_REMOVED, CircleLocalState.ARCHIVED_DISSOLVED)) {
            return suppress(payload)
        }
        val members = store.members(body.circleId, body.membershipVersion)
        if (members.none { it.nodeId == payload.originNodeId } || members.none { it.nodeId == port.localNodeId }) return suppress(payload)
        store.insertMessage(payload.messageEntity(body.text, payload.originNodeId == port.localNodeId, hopCount))
        val result = port.promoteCircleProcessed(payload.packetId.toString())
        if (payload.originNodeId != port.localNodeId) generateReceiptOnce(payload)
        return result
    }

    private suspend fun projectStatus(payload: PayloadV2): IngestResult {
        val store = circles ?: return stored(payload)
        val body = payload.body as CircleStatusBody
        if (body.subjectNodeId != payload.originNodeId) return suppress(payload)
        if (store.snapshot(body.circleId, body.membershipVersion) == null) {
            store.addPending(PendingCirclePacketEntity(payload.packetId.toString(), body.circleId, body.membershipVersion, payload.kind))
            return stored(payload)
        }
        val circle = store.circle(body.circleId) ?: return suppress(payload)
        if (circle.localState in setOf(CircleLocalState.LEAVE_PENDING, CircleLocalState.ARCHIVED_REMOVED, CircleLocalState.ARCHIVED_DISSOLVED)) return suppress(payload)
        val members = store.members(body.circleId, body.membershipVersion)
        if (members.none { it.nodeId == payload.originNodeId } || members.none { it.nodeId == port.localNodeId }) return suppress(payload)
        if (store.latestStatus(body.circleId, payload.originNodeId)?.originSequence?.let {
                payload.originSequence > it
            } != false
        ) {
            store.insertStatus(payload.statusEntity(body.status, body.note))
        }
        return port.promoteCircleProcessed(payload.packetId.toString())
    }

    private suspend fun projectLeave(payload: PayloadV2): IngestResult {
        val store = circles ?: return stored(payload)
        if ((payload.audience as Audience.DirectNode).nodeId != port.localNodeId) return stored(payload)
        val body = payload.body as CircleLeaveRequestBody
        val circle = store.circle(body.circleId) ?: return suppress(payload)
        if (circle.ownerNodeId != port.localNodeId || circle.localState != CircleLocalState.OWNER_ACTIVE) return suppress(payload)
        val members = store.members(body.circleId, circle.currentMembershipVersion)
        if (members.none { it.nodeId == payload.originNodeId }) return suppress(payload)
        publishOwnerSnapshot(circle, circle.name, members.filterNot { it.nodeId == payload.originNodeId }, false)
        packets.resolve(payload.packetId.toString())
        return port.promoteCircleProcessed(payload.packetId.toString())
    }

    private suspend fun projectReceipt(payload: PayloadV2): IngestResult {
        val body = payload.body as DeliveryReceiptBody
        if (body.circleId == null) return stored(payload)
        val store = circles ?: return stored(payload)
        if ((payload.audience as Audience.DirectNode).nodeId != port.localNodeId) return suppress(payload)
        val original = store.message(body.messageId) ?: return suppress(payload)
        if (!original.outgoing || original.originNodeId != port.localNodeId || original.circleId != body.circleId ||
            original.membershipVersion != body.membershipVersion
        ) return suppress(payload)
        val members = store.members(body.circleId, requireNotNull(body.membershipVersion))
        if (payload.originNodeId == port.localNodeId || members.none { it.nodeId == payload.originNodeId }) return suppress(payload)
        store.insertReceipt(CircleMessageReceiptEntity(body.messageId, payload.originNodeId, payload.packetId.toString(), port.now()))
        return port.promoteCircleProcessed(payload.packetId.toString())
    }

    private suspend fun generateReceiptOnce(original: PayloadV2) {
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

    private suspend fun replayPending(circleId: String) {
        val store = circles ?: return
        store.pending(circleId).sortedBy { it.packetId }.forEach { pending ->
            val raw = packets.find(pending.packetId)
            val payload = raw?.let {
                runCatching { ProtocolCodec.decodePayload(ProtocolCodec.decodeEnvelope(it.rawEnvelope).packet.payloadBytes) }.getOrNull()
            }
            if (payload != null) when (payload.kind) {
                PacketKind.CIRCLE_TEXT -> projectText(payload, raw.hopCount)
                PacketKind.CIRCLE_STATUS -> projectStatus(payload)
                else -> Unit
            }
            if (store.snapshot(pending.circleId, pending.membershipVersion) != null) {
                store.removePending(pending.packetId)
            }
        }
    }

    private suspend fun publishOwnerSnapshot(
        circle: CircleEntity,
        name: String,
        sourceMembers: List<CircleMemberEntity>,
        dissolved: Boolean,
    ): CircleSnapshotEntity {
        val store = requireNotNull(circles)
        val version = circle.currentMembershipVersion + 1
        val members = sourceMembers.map {
            CircleSnapshotMember(it.nodeId, if (it.nodeId == circle.ownerNodeId) CircleMemberRole.OWNER else CircleMemberRole.MEMBER)
        }
        check(validMembers(members, circle.ownerNodeId))
        val payload = port.createLocalCirclePacket(
            PacketKind.CIRCLE_MEMBERSHIP_SNAPSHOT, Audience.Circle(circle.circleId), Long.MAX_VALUE,
        ) { CircleMembershipSnapshotBody(circle.circleId, version, name, circle.ownerNodeId, members, dissolved) }
        val snapshot = payload.snapshotEntity()
        check(store.insertSnapshot(snapshot, members.toEntities(circle.circleId, version)))
        packets.supersede("circle-snapshot:${circle.circleId}", payload.packetId.toString())
        store.upsertCircle(circle.copy(
            name = name, currentMembershipVersion = version,
            localState = if (dissolved) CircleLocalState.ARCHIVED_DISSOLVED else circle.localState,
            updatedAt = port.now(),
        ))
        port.promoteCircleProcessed(payload.packetId.toString())
        return snapshot
    }

    private fun validMembers(members: List<CircleSnapshotMember>, ownerNodeId: String): Boolean =
        members.size in 1..MAX_CIRCLE_MEMBERS && members.map { it.nodeId }.distinct().size == members.size &&
            members.count { it.role == CircleMemberRole.OWNER } == 1 &&
            members.singleOrNull { it.role == CircleMemberRole.OWNER }?.nodeId == ownerNodeId

    private fun List<CircleSnapshotMember>.toEntities(circleId: String, version: Long) = map {
        CircleMemberEntity(circleId, version, it.nodeId, it.role)
    }
    private fun PayloadV2.snapshotEntity(): CircleSnapshotEntity {
        val body = body as CircleMembershipSnapshotBody
        return CircleSnapshotEntity(body.circleId, body.membershipVersion, body.circleName, body.ownerNodeId, body.dissolved, packetId.toString(), originSequence, createdAt)
    }
    private fun PayloadV2.messageEntity(text: String, outgoing: Boolean, hopCount: Int) = CircleMessageEntity(
        packetId.toString(), (body as CircleTextBody).circleId, body.membershipVersion,
        originNodeId, originDisplayName, originSequence, createdAt, text, outgoing, hopCount,
    )
    private fun PayloadV2.statusEntity(status: SafetyStatus, note: String?) = CircleStatusEventEntity(
        packetId.toString(), (body as CircleStatusBody).circleId, body.membershipVersion,
        originNodeId, status, note, originSequence, createdAt,
    )
    private suspend fun requireActiveCircle(store: CircleRepository, circleId: String): CircleEntity {
        val circle = requireNotNull(store.circle(circleId)) { "Unknown Circle" }
        require(circle.localState in setOf(CircleLocalState.OWNER_ACTIVE, CircleLocalState.ACTIVE)) { "Circle is read-only" }
        return circle
    }
    private suspend fun requireOwnerCircle(store: CircleRepository, circleId: String): CircleEntity {
        val circle = requireNotNull(store.circle(circleId)) { "Unknown Circle" }
        require(circle.ownerNodeId == port.localNodeId && circle.localState == CircleLocalState.OWNER_ACTIVE) { "Only the Circle owner may do this" }
        return circle
    }
    private fun cleanName(value: String) = value.trim().also {
        require(it.isNotEmpty()) { "Circle name cannot be empty" }
        require(it.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES) { "Circle name is too long" }
    }
    private fun cleanText(value: String) = value.trim().also {
        require(it.isNotEmpty()) { "Message cannot be empty" }
        require(it.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES) { "Message is longer than 500 UTF-8 bytes" }
    }
    private fun deterministicUuid(value: String): UUID = UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8))
    private fun validUuid(value: String) = runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)
    private fun stored(payload: PayloadV2) = IngestResult.StoredOnly(payload.packetId.toString())
    private suspend fun suppress(payload: PayloadV2) = port.suppressCircle(payload.packetId.toString())

}
