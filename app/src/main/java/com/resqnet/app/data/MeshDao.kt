package com.resqnet.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MeshDao {
    @Query("SELECT * FROM conversation_messages ORDER BY createdAt ASC, originSequence ASC, messageId ASC")
    fun observeMessages(): Flow<List<ConversationMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConversationMessage(message: ConversationMessageEntity): Long

    @Query("UPDATE conversation_messages SET relayed = 1 WHERE messageId = :id")
    suspend fun markConversationRelayed(id: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPacket(packet: PacketEntity): Long

    @Query("SELECT * FROM packets WHERE packetId = :id")
    suspend fun packet(id: String): PacketEntity?

    @Query("SELECT * FROM packets WHERE packetId IN (:ids)")
    suspend fun packets(ids: List<String>): List<PacketEntity>

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
