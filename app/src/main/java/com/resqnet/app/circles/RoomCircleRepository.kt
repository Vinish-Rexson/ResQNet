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
    override suspend fun members(circleId: String, membershipVersion: Long) = dao.circleMembers(circleId, membershipVersion)
    override suspend fun insertMessage(message: CircleMessageEntity) = dao.insertCircleMessage(message) != -1L
    override suspend fun message(messageId: String) = dao.circleMessage(messageId)
    override suspend fun messages(circleId: String) = dao.circleMessages(circleId)
    override suspend fun addPending(packet: PendingCirclePacketEntity) { dao.insertPendingCirclePacket(packet) }
    override suspend fun pending(circleId: String) = dao.pendingCirclePackets(circleId)
    override suspend fun removePending(packetId: String) = dao.deletePendingCirclePacket(packetId)
    override suspend fun insertReceipt(receipt: CircleMessageReceiptEntity) = dao.insertCircleReceipt(receipt) != -1L
    override suspend fun receipt(messageId: String, recipientNodeId: String) = dao.circleReceipt(messageId, recipientNodeId)
    override suspend fun receipts(messageId: String) = dao.circleReceipts(messageId)
    override suspend fun insertStatus(event: CircleStatusEventEntity) = dao.insertCircleStatus(event) != -1L
    override suspend fun latestStatus(circleId: String, memberNodeId: String) = dao.latestCircleStatus(circleId, memberNodeId)
    override suspend fun statusHistory(circleId: String, memberNodeId: String) = dao.circleStatusHistory(circleId, memberNodeId)
}
