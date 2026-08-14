package com.resqnet.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import com.resqnet.app.circles.*

@Dao
interface MeshDao {
    @Query("SELECT * FROM circles ORDER BY updatedAt DESC, circleId ASC")
    fun observeCircles(): Flow<List<CircleEntity>>

    @Query("SELECT * FROM circles WHERE circleId = :circleId")
    suspend fun circle(circleId: String): CircleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCircle(circle: CircleEntity)

    @Query("SELECT * FROM circle_invitations WHERE inviteId = :inviteId")
    suspend fun circleInvitation(inviteId: String): CircleInvitationEntity?

    @Query("SELECT * FROM circle_invitations WHERE circleId = :circleId")
    suspend fun circleInvitations(circleId: String): List<CircleInvitationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCircleInvitation(invitation: CircleInvitationEntity)

    @Query("SELECT * FROM circle_snapshots WHERE circleId = :circleId ORDER BY membershipVersion DESC LIMIT 1")
    suspend fun latestCircleSnapshot(circleId: String): CircleSnapshotEntity?

    @Query("SELECT * FROM circle_snapshots WHERE circleId = :circleId AND membershipVersion = :membershipVersion")
    suspend fun circleSnapshot(circleId: String, membershipVersion: Long): CircleSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCircleSnapshot(snapshot: CircleSnapshotEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCircleMembers(members: List<CircleMemberEntity>)

    @Query("SELECT * FROM circle_members WHERE circleId = :circleId AND membershipVersion = :membershipVersion ORDER BY role ASC, nodeId ASC")
    suspend fun circleMembers(circleId: String, membershipVersion: Long): List<CircleMemberEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCircleMessage(message: CircleMessageEntity): Long

    @Query("SELECT * FROM circle_messages WHERE messageId = :messageId")
    suspend fun circleMessage(messageId: String): CircleMessageEntity?

    @Query("SELECT * FROM circle_messages WHERE circleId = :circleId ORDER BY createdAt ASC, originSequence ASC, messageId ASC")
    suspend fun circleMessages(circleId: String): List<CircleMessageEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPendingCirclePacket(packet: PendingCirclePacketEntity): Long

    @Query("SELECT * FROM pending_circle_packets WHERE circleId = :circleId ORDER BY packetId ASC")
    suspend fun pendingCirclePackets(circleId: String): List<PendingCirclePacketEntity>

    @Query("DELETE FROM pending_circle_packets WHERE packetId = :packetId")
    suspend fun deletePendingCirclePacket(packetId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCircleReceipt(receipt: CircleMessageReceiptEntity): Long

    @Query("SELECT * FROM circle_message_receipts WHERE messageId = :messageId AND recipientNodeId = :recipientNodeId")
    suspend fun circleReceipt(messageId: String, recipientNodeId: String): CircleMessageReceiptEntity?

    @Query("SELECT * FROM circle_message_receipts WHERE messageId = :messageId")
    suspend fun circleReceipts(messageId: String): List<CircleMessageReceiptEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCircleStatus(event: CircleStatusEventEntity): Long

    @Query("SELECT * FROM circle_status_events WHERE circleId = :circleId AND memberNodeId = :memberNodeId ORDER BY originSequence DESC, packetId DESC LIMIT 1")
    suspend fun latestCircleStatus(circleId: String, memberNodeId: String): CircleStatusEventEntity?

    @Query("SELECT * FROM circle_status_events WHERE circleId = :circleId AND memberNodeId = :memberNodeId ORDER BY originSequence DESC, packetId DESC")
    suspend fun circleStatusHistory(circleId: String, memberNodeId: String): List<CircleStatusEventEntity>

    @Query("SELECT * FROM contacts ORDER BY displayName COLLATE NOCASE ASC, nodeId ASC")
    fun observeContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE nodeId = :nodeId")
    suspend fun contact(nodeId: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContact(contact: ContactEntity)

    @Query("DELETE FROM contacts WHERE nodeId = :nodeId")
    suspend fun deleteContact(nodeId: String)

    @Query("SELECT * FROM contacts WHERE state IN ('PENDING_OUTGOING', 'PENDING_INCOMING')")
    suspend fun pendingContacts(): List<ContactEntity>

    @Query(
        """
        DELETE FROM contacts
        WHERE nodeId = :nodeId
          AND state = :expectedState
          AND state IN ('PENDING_OUTGOING', 'PENDING_INCOMING')
          AND outgoingRequestId IS :expectedOutgoingRequestId
          AND outgoingRequestExpiresAt IS :expectedOutgoingRequestExpiresAt
          AND incomingRequestId IS :expectedIncomingRequestId
          AND incomingRequestExpiresAt IS :expectedIncomingRequestExpiresAt
        """,
    )
    suspend fun deleteContactIfPendingSnapshotMatches(
        nodeId: String,
        expectedState: ContactState,
        expectedOutgoingRequestId: String?,
        expectedOutgoingRequestExpiresAt: Long?,
        expectedIncomingRequestId: String?,
        expectedIncomingRequestExpiresAt: Long?,
    ): Int

    @Query(
        """
        UPDATE contacts SET
          state = :newState,
          outgoingRequestId = :newOutgoingRequestId,
          outgoingRequestExpiresAt = :newOutgoingRequestExpiresAt,
          incomingRequestId = :newIncomingRequestId,
          incomingRequestExpiresAt = :newIncomingRequestExpiresAt
        WHERE nodeId = :nodeId
          AND state = :expectedState
          AND state IN ('PENDING_OUTGOING', 'PENDING_INCOMING')
          AND outgoingRequestId IS :expectedOutgoingRequestId
          AND outgoingRequestExpiresAt IS :expectedOutgoingRequestExpiresAt
          AND incomingRequestId IS :expectedIncomingRequestId
          AND incomingRequestExpiresAt IS :expectedIncomingRequestExpiresAt
        """,
    )
    suspend fun replaceContactPendingSnapshotIfMatches(
        nodeId: String,
        expectedState: ContactState,
        expectedOutgoingRequestId: String?,
        expectedOutgoingRequestExpiresAt: Long?,
        expectedIncomingRequestId: String?,
        expectedIncomingRequestExpiresAt: Long?,
        newState: ContactState,
        newOutgoingRequestId: String?,
        newOutgoingRequestExpiresAt: Long?,
        newIncomingRequestId: String?,
        newIncomingRequestExpiresAt: Long?,
    ): Int

    @Query("SELECT * FROM conversation_messages ORDER BY createdAt ASC, originSequence ASC, messageId ASC")
    fun observeMessages(): Flow<List<ConversationMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConversationMessage(message: ConversationMessageEntity): Long

    @Query("SELECT * FROM conversation_messages WHERE messageId = :id")
    suspend fun conversationMessage(id: String): ConversationMessageEntity?

    @Query("UPDATE conversation_messages SET relayed = 1 WHERE messageId = :id")
    suspend fun markConversationRelayed(id: String)

    @Query("UPDATE conversation_messages SET delivered = 1 WHERE messageId = :id")
    suspend fun markConversationDelivered(id: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPacket(packet: PacketEntity): Long

    @Query("SELECT * FROM packets WHERE packetId = :id")
    suspend fun packet(id: String): PacketEntity?

    @Query("UPDATE packets SET projectionState = 'PROJECTED' WHERE packetId = :id AND projectionState = 'STORED_ONLY'")
    suspend fun markPacketProjected(id: String): Int

    @Query("UPDATE packets SET projectionState = 'SUPPRESSED' WHERE packetId = :id AND projectionState = 'STORED_ONLY'")
    suspend fun markPacketSuppressed(id: String): Int

    @Query("SELECT * FROM packets WHERE packetId IN (:ids)")
    suspend fun packets(ids: List<String>): List<PacketEntity>

    @Query("UPDATE packets SET relayEligible = 0 WHERE supersessionKey = :supersessionKey AND packetId != :keepPacketId")
    suspend fun supersedePackets(supersessionKey: String, keepPacketId: String)

    @Query("UPDATE packets SET relayEligible = 0 WHERE packetId = :packetId")
    suspend fun resolvePacket(packetId: String)

    @Query(
        "SELECT packetId FROM packets WHERE relayEligible = 1 " +
            "AND (relayPolicy != 'EPHEMERAL' OR expiresAt > :now) " +
            "ORDER BY createdAt DESC LIMIT :limit",
    )
    suspend fun inventoryPacketIds(now: Long, limit: Int = 2_000): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeer(peer: PeerEntity)

    @Query("SELECT * FROM peers WHERE nodeId = :nodeId")
    suspend fun peer(nodeId: String): PeerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDelivery(delivery: PeerDeliveryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReceipt(receipt: MessageReceiptEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReceipt(receipt: MessageReceiptEntity): Long

    @Query("SELECT * FROM message_receipts WHERE messageId = :messageId AND recipientNodeId = :recipientNodeId")
    suspend fun receipt(messageId: String, recipientNodeId: String): MessageReceiptEntity?

    @Query("SELECT * FROM local_state WHERE `key` = :key")
    suspend fun state(key: String): LocalStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putState(state: LocalStateEntity)

    @Transaction
    suspend fun nextSequence(): Long {
        val next = (state("origin_sequence")?.longValue ?: 0L) + 1L
        putState(LocalStateEntity("origin_sequence", next))
        return next
    }

    @Query("DELETE FROM packets WHERE relayPolicy = 'EPHEMERAL' AND expiresAt <= :now")
    suspend fun deleteExpiredEphemeralPackets(now: Long)

    @Query(
        "DELETE FROM conversation_messages WHERE messageId NOT IN " +
            "(SELECT messageId FROM conversation_messages ORDER BY createdAt DESC LIMIT :limit)",
    )
    suspend fun trimConversationMessages(limit: Int)
}
