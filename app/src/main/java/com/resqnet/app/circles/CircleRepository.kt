package com.resqnet.app.circles

import kotlinx.coroutines.flow.Flow

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
    suspend fun members(circleId: String, membershipVersion: Long): List<CircleMemberEntity>
    suspend fun insertMessage(message: CircleMessageEntity): Boolean
    suspend fun message(messageId: String): CircleMessageEntity?
    suspend fun messages(circleId: String): List<CircleMessageEntity>
    suspend fun addPending(packet: PendingCirclePacketEntity)
    suspend fun pending(circleId: String): List<PendingCirclePacketEntity>
    suspend fun removePending(packetId: String)
    suspend fun insertReceipt(receipt: CircleMessageReceiptEntity): Boolean
    suspend fun receipt(messageId: String, recipientNodeId: String): CircleMessageReceiptEntity?
    suspend fun receipts(messageId: String): List<CircleMessageReceiptEntity>
    suspend fun insertStatus(event: CircleStatusEventEntity): Boolean
    suspend fun latestStatus(circleId: String, memberNodeId: String): CircleStatusEventEntity?
    suspend fun statusHistory(circleId: String, memberNodeId: String): List<CircleStatusEventEntity>
}
