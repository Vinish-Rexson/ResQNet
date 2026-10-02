package com.resqnet.app.circles

import kotlinx.coroutines.flow.Flow
import com.resqnet.app.circles.CircleMessageEntity
import java.nio.charset.StandardCharsets
import java.util.UUID

enum class CircleStoreResult { PROJECTED, STORED_ONLY, SUPPRESSED }

data class CircleSnapshotApplication(
    val snapshot: CircleSnapshotEntity,
    val members: List<CircleMemberEntity>,
    val localNodeId: String,
    val now: Long,
    val expectedPreviousVersion: Long? = null,
    val acceptedInviteId: String? = null,
    val resolvedControlPacketId: String? = null,
    val continuousMembership: ContinuousMembershipRequirement? = null,
)

data class ContinuousMembershipRequirement(val nodeId: String, val sinceVersion: Long)

data class PreparedCircleLeave(
    val circleId: String,
    val ownerNodeId: String,
    val membershipVersion: Long,
    val packetId: String,
)

data class PreparedCircleAcceptance(
    val inviteId: String,
    val circleId: String,
    val ownerNodeId: String,
    val packetId: String,
)

internal fun circleLeavePacketId(circleId: String, nodeId: String, membershipVersion: Long): String =
    UUID.nameUUIDFromBytes(
        "circle-leave:$circleId:$nodeId:$membershipVersion".toByteArray(StandardCharsets.UTF_8),
    ).toString()

internal fun circleAcceptancePacketId(inviteId: String, nodeId: String): String =
    UUID.nameUUIDFromBytes(
        "circle-accept:$inviteId:$nodeId".toByteArray(StandardCharsets.UTF_8),
    ).toString()

interface CircleRepository {
    fun observeCircles(): Flow<List<CircleEntity>>
    suspend fun circle(circleId: String): CircleEntity?
    suspend fun upsertCircle(circle: CircleEntity)
    suspend fun invitation(inviteId: String): CircleInvitationEntity?
    suspend fun invitationsForCircle(circleId: String): List<CircleInvitationEntity>
    suspend fun upsertInvitation(invitation: CircleInvitationEntity)
    suspend fun latestSnapshot(circleId: String): CircleSnapshotEntity?
    suspend fun snapshot(circleId: String, membershipVersion: Long): CircleSnapshotEntity?
    suspend fun insertSnapshot(snapshot: CircleSnapshotEntity, members: List<CircleMemberEntity>): Boolean
    suspend fun applySnapshot(application: CircleSnapshotApplication): CircleStoreResult
    suspend fun members(circleId: String, membershipVersion: Long): List<CircleMemberEntity>
    suspend fun projectMessage(message: CircleMessageEntity, localNodeId: String): CircleStoreResult
    suspend fun message(messageId: String): CircleMessageEntity?
    suspend fun messages(circleId: String): List<CircleMessageEntity>
    fun observeMessages(circleId: String): Flow<List<CircleMessageEntity>>
    suspend fun addPending(packet: PendingCirclePacketEntity)
    suspend fun pending(circleId: String): List<PendingCirclePacketEntity>
    suspend fun removePending(packetId: String)
    suspend fun insertReceipt(receipt: CircleMessageReceiptEntity): Boolean
    suspend fun receipt(messageId: String, recipientNodeId: String): CircleMessageReceiptEntity?
    suspend fun receipts(messageId: String): List<CircleMessageReceiptEntity>
    suspend fun projectStatus(event: CircleStatusEventEntity, localNodeId: String): CircleStoreResult
    suspend fun latestStatus(circleId: String, memberNodeId: String): CircleStatusEventEntity?
    suspend fun statusHistory(circleId: String, memberNodeId: String): List<CircleStatusEventEntity>
    fun observeStatuses(circleId: String): Flow<List<CircleStatusEventEntity>>
    fun observeLatestStatusPerMemberAllCircles(): Flow<List<CircleStatusEventEntity>>
    suspend fun prepareAcceptance(inviteId: String, localNodeId: String, now: Long): PreparedCircleAcceptance
    suspend fun prepareLeave(circleId: String, localNodeId: String, now: Long): PreparedCircleLeave
}
