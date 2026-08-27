package com.resqnet.app.circles

import com.resqnet.app.data.ContactRepository
import com.resqnet.app.data.ContactState
import com.resqnet.app.data.PacketRepository
import com.resqnet.app.mesh.IngestResult
import com.resqnet.app.protocol.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal class CircleLifecycleHandler(
    private val port: CirclePort,
    private val packets: PacketRepository,
    private val contacts: ContactRepository?,
    private val circles: CircleRepository?,
    private val content: CircleContentHandler,
) {
    private val mutations = Mutex()

    suspend fun createCircle(name: String): CircleEntity = mutations.withLock {
        val store = requireNotNull(circles) { "Circle persistence is unavailable" }
        val clean = cleanName(name)
        val circleId = UUID.randomUUID().toString()
        val members = listOf(CircleSnapshotMember(port.localNodeId, CircleMemberRole.OWNER))
        val payload = port.createLocalCirclePacket(
            PacketKind.CIRCLE_MEMBERSHIP_SNAPSHOT, Audience.Circle(circleId), Long.MAX_VALUE,
        ) { CircleMembershipSnapshotBody(circleId, 1, clean, port.localNodeId, members) }
        val result = store.applySnapshot(payload.snapshotApplication(members, port.localNodeId, port.now(), 0))
        check(result == CircleStoreResult.PROJECTED) { "Could not atomically create Circle" }
        requireNotNull(store.circle(circleId))
    }

    suspend fun invite(circleId: String, targetNodeId: String): CircleInvitationEntity = mutations.withLock {
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
        CircleInvitationEntity(
            inviteId, payload.packetId.toString(), circleId, circle.name, port.localNodeId,
            targetNodeId, circle.currentMembershipVersion, expiresAt, CircleInvitationState.PENDING,
            null, encodeCircleMembers(preview), payload.createdAt, port.now(),
        ).also {
            store.upsertInvitation(it)
            port.promoteCircleProcessed(payload.packetId.toString())
        }
    }

    suspend fun acceptInvite(inviteId: String): String = mutations.withLock {
        val store = requireNotNull(circles) { "Circle persistence is unavailable" }
        val invite = requireNotNull(store.invitation(inviteId)) { "Unknown Circle invitation" }
        require(invite.targetNodeId == port.localNodeId &&
            invite.state in setOf(CircleInvitationState.PENDING, CircleInvitationState.ACCEPTANCE_PENDING)
        ) {
            "Invitation is not active"
        }
        if (port.now() >= invite.expiresAt) {
            store.upsertInvitation(invite.copy(state = CircleInvitationState.EXPIRED, updatedAt = port.now()))
            throw IllegalArgumentException("Invitation expired")
        }
        val prepared = store.prepareAcceptance(inviteId, port.localNodeId, port.now())
        if (packets.find(prepared.packetId) == null) {
            port.createLocalCirclePacket(
                PacketKind.CIRCLE_INVITE_ACCEPT,
                Audience.DirectNode(prepared.ownerNodeId),
                Long.MAX_VALUE,
                UUID.fromString(prepared.packetId),
            ) { CircleInviteAcceptBody(prepared.inviteId, prepared.circleId) }
        }
        port.promoteCircleProcessed(prepared.packetId)
        if (store.invitation(inviteId)?.state != CircleInvitationState.ACCEPTANCE_PENDING) {
            packets.resolve(prepared.packetId)
        }
        prepared.packetId
    }

    suspend fun declineInvite(inviteId: String) = mutations.withLock {
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
        Unit
    }

    suspend fun leave(circleId: String): String = mutations.withLock {
        val store = requireNotNull(circles) { "Circle persistence is unavailable" }
        val prepared = store.prepareLeave(circleId, port.localNodeId, port.now())
        if (packets.find(prepared.packetId) == null) {
            port.createLocalCirclePacket(
                PacketKind.CIRCLE_LEAVE_REQUEST,
                Audience.DirectNode(prepared.ownerNodeId),
                Long.MAX_VALUE,
                UUID.fromString(prepared.packetId),
            ) { CircleLeaveRequestBody(circleId, prepared.membershipVersion) }
        }
        port.promoteCircleProcessed(prepared.packetId)
        val current = store.circle(circleId)
        if (current?.localState != CircleLocalState.LEAVE_PENDING ||
            current.leaveRequestPacketId != prepared.packetId
        ) packets.resolve(prepared.packetId)
        prepared.packetId
    }

    suspend fun rename(circleId: String, name: String) = mutations.withLock {
        val store = requireNotNull(circles)
        val circle = requireOwnerCircle(store, circleId)
        check(publishOwnerSnapshot(circle, cleanName(name), store.members(circleId, circle.currentMembershipVersion), false) == CircleStoreResult.PROJECTED)
    }

    suspend fun removeMember(circleId: String, nodeId: String) = mutations.withLock {
        val store = requireNotNull(circles)
        val circle = requireOwnerCircle(store, circleId)
        require(nodeId != port.localNodeId) { "Owner cannot be removed" }
        val members = store.members(circleId, circle.currentMembershipVersion)
        require(members.any { it.nodeId == nodeId }) { "Node is not an active member" }
        check(publishOwnerSnapshot(circle, circle.name, members.filterNot { it.nodeId == nodeId }, false) == CircleStoreResult.PROJECTED)
    }

    suspend fun dissolve(circleId: String) = mutations.withLock {
        val store = requireNotNull(circles)
        val circle = requireOwnerCircle(store, circleId)
        check(publishOwnerSnapshot(circle, circle.name, store.members(circleId, circle.currentMembershipVersion), true) == CircleStoreResult.PROJECTED)
    }

    suspend fun project(payload: PayloadV2): IngestResult = when (payload.kind) {
            PacketKind.CIRCLE_INVITE -> projectInvite(payload)
            PacketKind.CIRCLE_INVITE_ACCEPT -> projectAccept(payload)
            PacketKind.CIRCLE_INVITE_DECLINE -> projectDecline(payload)
            PacketKind.CIRCLE_MEMBERSHIP_SNAPSHOT -> projectSnapshot(payload)
            PacketKind.CIRCLE_LEAVE_REQUEST -> projectLeave(payload)
            else -> IngestResult.StoredOnly(payload.packetId.toString())
    }

    suspend fun recoverSnapshot(payload: PayloadV2) {
        if (payload.kind == PacketKind.CIRCLE_MEMBERSHIP_SNAPSHOT) {
            projectSnapshot(payload)
        }
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
        if (store.circle(body.circleId) == null) store.upsertCircle(CircleEntity(
            body.circleId, body.circleName, body.ownerNodeId, body.membershipVersion,
            CircleLocalState.INVITED, null, null, payload.createdAt, port.now(),
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
        val circle = store.circle(body.circleId) ?: return suppress(payload)
        if (circle.ownerNodeId != port.localNodeId || circle.localState != CircleLocalState.OWNER_ACTIVE) return suppress(payload)
        val members = store.members(body.circleId, circle.currentMembershipVersion)
        if (members.any { it.nodeId == payload.originNodeId }) {
            store.upsertInvitation(invite.copy(state = CircleInvitationState.ACCEPTED, updatedAt = port.now()))
            packets.resolve(payload.packetId.toString())
            return port.promoteCircleProcessed(payload.packetId.toString())
        }
        if (members.size >= MAX_CIRCLE_MEMBERS) return suppress(payload)
        val result = publishOwnerSnapshot(
            circle,
            circle.name,
            members + CircleMemberEntity(
                body.circleId, circle.currentMembershipVersion, payload.originNodeId, CircleMemberRole.MEMBER,
            ),
            dissolved = false,
            acceptedInviteId = invite.inviteId,
            resolvedControlPacketId = payload.packetId.toString(),
        )
        return result.asIngestResult(payload.packetId.toString())
    }

    private suspend fun projectDecline(payload: PayloadV2): IngestResult {
        val store = circles ?: return stored(payload)
        if ((payload.audience as Audience.DirectNode).nodeId != port.localNodeId) return stored(payload)
        val body = payload.body as CircleInviteDeclineBody
        val invite = store.invitation(body.inviteId) ?: return suppress(payload)
        if (invite.ownerNodeId != port.localNodeId || invite.targetNodeId != payload.originNodeId ||
            invite.circleId != body.circleId || invite.state != CircleInvitationState.PENDING ||
            payload.createdAt >= invite.expiresAt
        ) return suppress(payload)
        store.upsertInvitation(invite.copy(state = CircleInvitationState.DECLINED, updatedAt = port.now()))
        return port.promoteCircleProcessed(payload.packetId.toString())
    }

    private suspend fun projectSnapshot(payload: PayloadV2): IngestResult {
        val store = circles ?: return stored(payload)
        val body = payload.body as CircleMembershipSnapshotBody
        if (!validUuid(body.circleId) || body.membershipVersion <= 0 || body.ownerNodeId != payload.originNodeId ||
            !validMembers(body.members, body.ownerNodeId) ||
            (body.membershipVersion == 1L &&
                (body.members.size != 1 || body.members.single().nodeId != body.ownerNodeId))
        ) return suppress(payload)
        val result = store.applySnapshot(payload.snapshotApplication(body.members, port.localNodeId, port.now()))
        if (result != CircleStoreResult.SUPPRESSED &&
            store.snapshot(body.circleId, body.membershipVersion)?.packetId == payload.packetId.toString()
        ) content.replayPending(body.circleId)
        return result.asIngestResult(payload.packetId.toString())
    }

    private suspend fun projectLeave(payload: PayloadV2): IngestResult {
        val store = circles ?: return stored(payload)
        if ((payload.audience as Audience.DirectNode).nodeId != port.localNodeId) return stored(payload)
        val body = payload.body as CircleLeaveRequestBody
        if (body.membershipVersion <= 0) return suppress(payload)
        val circle = store.circle(body.circleId) ?: return suppress(payload)
        if (circle.ownerNodeId != port.localNodeId || circle.localState != CircleLocalState.OWNER_ACTIVE) return suppress(payload)
        val members = store.members(body.circleId, circle.currentMembershipVersion)
        if (members.none { it.nodeId == payload.originNodeId }) return suppress(payload)
        val result = publishOwnerSnapshot(
            circle,
            circle.name,
            members.filterNot { it.nodeId == payload.originNodeId },
            dissolved = false,
            resolvedControlPacketId = payload.packetId.toString(),
            continuousMembership = ContinuousMembershipRequirement(payload.originNodeId, body.membershipVersion),
        )
        if (result != CircleStoreResult.PROJECTED) packets.resolve(payload.packetId.toString())
        return result.asIngestResult(payload.packetId.toString())
    }

    private suspend fun publishOwnerSnapshot(
        circle: CircleEntity,
        name: String,
        sourceMembers: List<CircleMemberEntity>,
        dissolved: Boolean,
        acceptedInviteId: String? = null,
        resolvedControlPacketId: String? = null,
        continuousMembership: ContinuousMembershipRequirement? = null,
    ): CircleStoreResult {
        val store = requireNotNull(circles)
        val version = circle.currentMembershipVersion + 1
        val members = sourceMembers.map {
            CircleSnapshotMember(
                it.nodeId,
                if (it.nodeId == circle.ownerNodeId) CircleMemberRole.OWNER else CircleMemberRole.MEMBER,
            )
        }
        check(validMembers(members, circle.ownerNodeId))
        val payload = port.createLocalCirclePacket(
            PacketKind.CIRCLE_MEMBERSHIP_SNAPSHOT, Audience.Circle(circle.circleId), Long.MAX_VALUE,
        ) { CircleMembershipSnapshotBody(circle.circleId, version, name, circle.ownerNodeId, members, dissolved) }
        val result = store.applySnapshot(CircleSnapshotApplication(
            snapshot = payload.snapshotEntity(),
            members = members.toEntities(circle.circleId, version),
            localNodeId = port.localNodeId,
            now = port.now(),
            expectedPreviousVersion = circle.currentMembershipVersion,
            acceptedInviteId = acceptedInviteId,
            resolvedControlPacketId = resolvedControlPacketId,
            continuousMembership = continuousMembership,
        ))
        if (result != CircleStoreResult.PROJECTED) packets.resolve(payload.packetId.toString())
        return result
    }

    private suspend fun requireOwnerCircle(store: CircleRepository, circleId: String): CircleEntity {
        val circle = requireNotNull(store.circle(circleId)) { "Unknown Circle" }
        require(circle.ownerNodeId == port.localNodeId && circle.localState == CircleLocalState.OWNER_ACTIVE) {
            "Only the Circle owner may do this"
        }
        return circle
    }

    private fun cleanName(value: String) = value.trim().also {
        require(it.isNotEmpty()) { "Circle name cannot be empty" }
        require(it.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES) { "Circle name is too long" }
    }

    private fun validMembers(members: List<CircleSnapshotMember>, ownerNodeId: String): Boolean =
        members.size in 1..MAX_CIRCLE_MEMBERS && members.map { it.nodeId }.distinct().size == members.size &&
            members.count { it.role == CircleMemberRole.OWNER } == 1 &&
            members.singleOrNull { it.role == CircleMemberRole.OWNER }?.nodeId == ownerNodeId

    private fun PayloadV2.snapshotApplication(
        members: List<CircleSnapshotMember>,
        localNodeId: String,
        now: Long,
        expectedPreviousVersion: Long? = null,
    ) = CircleSnapshotApplication(
        snapshotEntity(),
        members.toEntities((body as CircleMembershipSnapshotBody).circleId, body.membershipVersion),
        localNodeId,
        now,
        expectedPreviousVersion,
    )

    private fun PayloadV2.snapshotEntity(): CircleSnapshotEntity {
        val body = body as CircleMembershipSnapshotBody
        return CircleSnapshotEntity(
            body.circleId, body.membershipVersion, body.circleName, body.ownerNodeId, body.dissolved,
            packetId.toString(), originSequence, createdAt,
        )
    }

    private fun List<CircleSnapshotMember>.toEntities(circleId: String, version: Long) = map {
        CircleMemberEntity(circleId, version, it.nodeId, it.role)
    }

    private fun validUuid(value: String) =
        runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)

    private fun stored(payload: PayloadV2) = IngestResult.StoredOnly(payload.packetId.toString())
    private suspend fun suppress(payload: PayloadV2) = port.suppressCircle(payload.packetId.toString())
}
