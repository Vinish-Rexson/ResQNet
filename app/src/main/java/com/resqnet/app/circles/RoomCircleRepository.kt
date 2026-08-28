package com.resqnet.app.circles

import androidx.room.withTransaction
import com.resqnet.app.data.MeshDao
import com.resqnet.app.data.ResQNetDatabase

class RoomCircleRepository(
    private val database: ResQNetDatabase,
    private val dao: MeshDao,
) : CircleRepository {
    override fun observeCircles() = dao.observeCircles()
    override suspend fun circle(circleId: String) = dao.circle(circleId)
    override suspend fun upsertCircle(circle: CircleEntity) = dao.upsertCircle(circle)
    override suspend fun invitation(inviteId: String) = dao.circleInvitation(inviteId)
    override suspend fun invitationsForCircle(circleId: String) = dao.circleInvitations(circleId)
    override suspend fun upsertInvitation(invitation: CircleInvitationEntity) = dao.upsertCircleInvitation(invitation)
    override suspend fun latestSnapshot(circleId: String) = dao.latestCircleSnapshot(circleId)
    override suspend fun snapshot(circleId: String, membershipVersion: Long) = dao.circleSnapshot(circleId, membershipVersion)
    override suspend fun insertSnapshot(snapshot: CircleSnapshotEntity, members: List<CircleMemberEntity>) =
        database.withTransaction {
            if (dao.insertCircleSnapshot(snapshot) == -1L) return@withTransaction false
            dao.insertCircleMembers(members)
            true
        }
    override suspend fun applySnapshot(application: CircleSnapshotApplication): CircleStoreResult =
        database.withTransaction {
            val snapshot = application.snapshot
            val existingCircle = dao.circle(snapshot.circleId)
            val currentSnapshot = dao.latestCircleSnapshot(snapshot.circleId)
            if (existingCircle?.ownerNodeId?.let { it != snapshot.ownerNodeId } == true) {
                dao.markPacketSuppressed(snapshot.packetId)
                return@withTransaction CircleStoreResult.SUPPRESSED
            }
            if (existingCircle?.localState == CircleLocalState.ARCHIVED_DISSOLVED) {
                if (currentSnapshot?.packetId == snapshot.packetId && currentSnapshot.dissolved) {
                    dao.supersedePackets("circle-snapshot:${snapshot.circleId}", snapshot.packetId)
                    dao.markPacketProjected(snapshot.packetId)
                    return@withTransaction CircleStoreResult.PROJECTED
                }
                dao.resolvePacket(snapshot.packetId)
                return@withTransaction CircleStoreResult.STORED_ONLY
            }

            val localInSnapshot = application.members.any { it.nodeId == application.localNodeId }
            val createsOwnerCircle = existingCircle == null && snapshot.membershipVersion == 1L &&
                snapshot.ownerNodeId == application.localNodeId && application.members.size == 1 && localInSnapshot
            if (existingCircle == null && !createsOwnerCircle) return@withTransaction CircleStoreResult.STORED_ONLY

            val currentVersion = currentSnapshot?.membershipVersion ?: 0L
            val existingAtVersion = dao.circleSnapshot(snapshot.circleId, snapshot.membershipVersion)
            val retryingAppliedSnapshot = currentSnapshot?.packetId == snapshot.packetId &&
                currentVersion == snapshot.membershipVersion
            if (application.expectedPreviousVersion?.let { it != currentVersion } == true) {
                dao.resolvePacket(snapshot.packetId)
                return@withTransaction CircleStoreResult.STORED_ONLY
            }
            val continuity = application.continuousMembership
            if (continuity != null && (
                    continuity.sinceVersion > currentVersion ||
                        !dao.isCircleMember(snapshot.circleId, continuity.sinceVersion, continuity.nodeId) ||
                        !dao.isCircleMember(snapshot.circleId, currentVersion, continuity.nodeId) ||
                        dao.circleSnapshotsWithoutMember(
                            snapshot.circleId, continuity.nodeId, continuity.sinceVersion, currentVersion,
                        ) != 0
                    )
            ) {
                dao.resolvePacket(snapshot.packetId)
                return@withTransaction CircleStoreResult.STORED_ONLY
            }
            if (!retryingAppliedSnapshot && snapshot.membershipVersion <= currentVersion) {
                if (existingAtVersion == null && dao.insertCircleSnapshot(snapshot) != -1L) {
                    dao.insertCircleMembers(application.members)
                }
                dao.resolvePacket(snapshot.packetId)
                return@withTransaction CircleStoreResult.STORED_ONLY
            }
            if (!retryingAppliedSnapshot && existingAtVersion != null) {
                dao.resolvePacket(snapshot.packetId)
                return@withTransaction CircleStoreResult.STORED_ONLY
            }
            if (!retryingAppliedSnapshot) {
                check(dao.insertCircleSnapshot(snapshot) != -1L) { "Concurrent Circle snapshot collision" }
                dao.insertCircleMembers(application.members)
            }

            val hasPendingAcceptance = existingCircle?.localState == CircleLocalState.ACCEPTANCE_PENDING &&
                dao.circleInvitations(snapshot.circleId).any {
                    it.targetNodeId == application.localNodeId &&
                        it.state == CircleInvitationState.ACCEPTANCE_PENDING
                }
            val localState = when {
                snapshot.dissolved -> CircleLocalState.ARCHIVED_DISSOLVED
                existingCircle?.localState == CircleLocalState.ARCHIVED_REMOVED -> CircleLocalState.ARCHIVED_REMOVED
                snapshot.ownerNodeId == application.localNodeId -> CircleLocalState.OWNER_ACTIVE
                existingCircle?.localState == CircleLocalState.LEAVE_PENDING && localInSnapshot -> CircleLocalState.LEAVE_PENDING
                existingCircle?.localState == CircleLocalState.INVITED -> CircleLocalState.INVITED
                existingCircle?.localState == CircleLocalState.ACCEPTANCE_PENDING && !hasPendingAcceptance ->
                    CircleLocalState.ACCEPTANCE_PENDING
                localInSnapshot -> CircleLocalState.ACTIVE
                existingCircle?.localState in setOf(
                    CircleLocalState.INVITED, CircleLocalState.ACCEPTANCE_PENDING,
                ) -> requireNotNull(existingCircle).localState
                existingCircle != null -> CircleLocalState.ARCHIVED_REMOVED
                else -> return@withTransaction CircleStoreResult.STORED_ONLY
            }
            val circle = CircleEntity(
                snapshot.circleId,
                snapshot.circleName,
                snapshot.ownerNodeId,
                snapshot.membershipVersion,
                localState,
                existingCircle?.leaveRequestPacketId?.takeIf { localState == CircleLocalState.LEAVE_PENDING },
                existingCircle?.leaveRequestMembershipVersion?.takeIf { localState == CircleLocalState.LEAVE_PENDING },
                existingCircle?.createdAt ?: snapshot.createdAt,
                application.now,
            )
            dao.upsertCircle(circle)

            if (localInSnapshot || snapshot.dissolved) {
                dao.circleInvitations(snapshot.circleId).filter {
                    it.targetNodeId == application.localNodeId &&
                        it.state == CircleInvitationState.ACCEPTANCE_PENDING
                }.forEach { invitation ->
                    invitation.acceptancePacketId?.let { dao.resolvePacket(it) }
                    dao.upsertCircleInvitation(invitation.copy(
                        state = if (snapshot.dissolved) CircleInvitationState.EXPIRED else CircleInvitationState.ACCEPTED,
                        updatedAt = application.now,
                    ))
                }
            }
            application.acceptedInviteId?.let { inviteId ->
                dao.circleInvitation(inviteId)?.let { invitation ->
                    dao.upsertCircleInvitation(invitation.copy(
                        state = CircleInvitationState.ACCEPTED,
                        updatedAt = application.now,
                    ))
                }
            }
            if (!localInSnapshot || snapshot.dissolved) {
                existingCircle?.leaveRequestPacketId?.let { dao.resolvePacket(it) }
            }
            application.resolvedControlPacketId?.let { dao.resolvePacket(it) }
            dao.supersedePackets("circle-snapshot:${snapshot.circleId}", snapshot.packetId)
            dao.markPacketProjected(snapshot.packetId)
            CircleStoreResult.PROJECTED
        }
    override suspend fun members(circleId: String, membershipVersion: Long) = dao.circleMembers(circleId, membershipVersion)
    override suspend fun projectMessage(message: CircleMessageEntity, localNodeId: String): CircleStoreResult =
        database.withTransaction {
            if (dao.circleSnapshot(message.circleId, message.membershipVersion) == null) {
                dao.insertPendingCirclePacket(message.pending())
                return@withTransaction CircleStoreResult.STORED_ONLY
            }
            val circle = dao.circle(message.circleId)
            val authorized = circle?.localState in setOf(CircleLocalState.OWNER_ACTIVE, CircleLocalState.ACTIVE) &&
                dao.isCircleMember(message.circleId, message.membershipVersion, message.originNodeId) &&
                dao.isCircleMember(message.circleId, message.membershipVersion, localNodeId)
            if (!authorized) {
                dao.deletePendingCirclePacket(message.messageId)
                dao.markPacketSuppressed(message.messageId)
                return@withTransaction CircleStoreResult.SUPPRESSED
            }
            dao.insertCircleMessage(message)
            dao.deletePendingCirclePacket(message.messageId)
            dao.markPacketProjected(message.messageId)
            CircleStoreResult.PROJECTED
        }
    override suspend fun message(messageId: String) = dao.circleMessage(messageId)
    override suspend fun messages(circleId: String) = dao.circleMessages(circleId)
    override fun observeMessages(circleId: String) = dao.observeCircleMessages(circleId)
    override suspend fun addPending(packet: PendingCirclePacketEntity) { dao.insertPendingCirclePacket(packet) }
    override suspend fun pending(circleId: String) = dao.pendingCirclePackets(circleId)
    override suspend fun removePending(packetId: String) = dao.deletePendingCirclePacket(packetId)
    override suspend fun insertReceipt(receipt: CircleMessageReceiptEntity) = dao.insertCircleReceipt(receipt) != -1L
    override suspend fun receipt(messageId: String, recipientNodeId: String) = dao.circleReceipt(messageId, recipientNodeId)
    override suspend fun receipts(messageId: String) = dao.circleReceipts(messageId)
    override suspend fun projectStatus(event: CircleStatusEventEntity, localNodeId: String): CircleStoreResult =
        database.withTransaction {
            if (dao.circleSnapshot(event.circleId, event.membershipVersion) == null) {
                dao.insertPendingCirclePacket(event.pending())
                return@withTransaction CircleStoreResult.STORED_ONLY
            }
            val circle = dao.circle(event.circleId)
            val authorized = circle?.localState in setOf(CircleLocalState.OWNER_ACTIVE, CircleLocalState.ACTIVE) &&
                dao.isCircleMember(event.circleId, event.membershipVersion, event.memberNodeId) &&
                dao.isCircleMember(event.circleId, event.membershipVersion, localNodeId)
            if (!authorized) {
                dao.deletePendingCirclePacket(event.packetId)
                dao.markPacketSuppressed(event.packetId)
                return@withTransaction CircleStoreResult.SUPPRESSED
            }
            val latest = dao.latestCircleStatus(event.circleId, event.memberNodeId)
            if (latest == null || event.originSequence > latest.originSequence) dao.insertCircleStatus(event)
            dao.deletePendingCirclePacket(event.packetId)
            dao.markPacketProjected(event.packetId)
            CircleStoreResult.PROJECTED
        }
    override suspend fun latestStatus(circleId: String, memberNodeId: String) = dao.latestCircleStatus(circleId, memberNodeId)
    override suspend fun statusHistory(circleId: String, memberNodeId: String) = dao.circleStatusHistory(circleId, memberNodeId)
    override fun observeStatuses(circleId: String) = dao.observeCircleStatuses(circleId)
    override suspend fun prepareAcceptance(
        inviteId: String,
        localNodeId: String,
        now: Long,
    ): PreparedCircleAcceptance = database.withTransaction {
        val invitation = requireNotNull(dao.circleInvitation(inviteId)) { "Unknown Circle invitation" }
        require(invitation.targetNodeId == localNodeId &&
            invitation.state in setOf(CircleInvitationState.PENDING, CircleInvitationState.ACCEPTANCE_PENDING)
        ) { "Invitation is not active" }
        require(now < invitation.expiresAt) { "Invitation expired" }
        val packetId = invitation.acceptancePacketId ?: circleAcceptancePacketId(inviteId, localNodeId)
        if (invitation.state != CircleInvitationState.ACCEPTANCE_PENDING || invitation.acceptancePacketId == null) {
            dao.upsertCircleInvitation(invitation.copy(
                state = CircleInvitationState.ACCEPTANCE_PENDING,
                acceptancePacketId = packetId,
                updatedAt = now,
            ))
            dao.circle(invitation.circleId)?.let { circle ->
                dao.upsertCircle(circle.copy(localState = CircleLocalState.ACCEPTANCE_PENDING, updatedAt = now))
            }
        }
        PreparedCircleAcceptance(inviteId, invitation.circleId, invitation.ownerNodeId, packetId)
    }

    override suspend fun prepareLeave(circleId: String, localNodeId: String, now: Long): PreparedCircleLeave =
        database.withTransaction {
            val circle = requireNotNull(dao.circle(circleId)) { "Unknown Circle" }
            if (circle.localState == CircleLocalState.LEAVE_PENDING) {
                return@withTransaction PreparedCircleLeave(
                    circle.circleId,
                    circle.ownerNodeId,
                    requireNotNull(circle.leaveRequestMembershipVersion),
                    requireNotNull(circle.leaveRequestPacketId),
                )
            }
            require(circle.localState == CircleLocalState.ACTIVE && circle.ownerNodeId != localNodeId) {
                "Circle is read-only"
            }
            val version = circle.currentMembershipVersion
            check(dao.isCircleMember(circleId, version, localNodeId))
            val packetId = circleLeavePacketId(circleId, localNodeId, version)
            dao.upsertCircle(circle.copy(
                localState = CircleLocalState.LEAVE_PENDING,
                leaveRequestPacketId = packetId,
                leaveRequestMembershipVersion = version,
                updatedAt = now,
            ))
            PreparedCircleLeave(circleId, circle.ownerNodeId, version, packetId)
        }

    private fun CircleMessageEntity.pending() = PendingCirclePacketEntity(
        messageId, circleId, membershipVersion, com.resqnet.app.protocol.PacketKind.CIRCLE_TEXT,
    )

    private fun CircleStatusEventEntity.pending() = PendingCirclePacketEntity(
        packetId, circleId, membershipVersion, com.resqnet.app.protocol.PacketKind.CIRCLE_STATUS,
    )
}
