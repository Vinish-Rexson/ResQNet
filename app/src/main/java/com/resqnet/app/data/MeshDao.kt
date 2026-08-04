package com.resqnet.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MeshDao {
    @Query("SELECT * FROM messages ORDER BY createdAt ASC, originSequence ASC, messageId ASC")
    fun observeMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE messageId = :id")
    suspend fun message(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("SELECT messageId FROM messages WHERE expiresAt > :now ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentMessageIds(now: Long, limit: Int = 2_000): List<String>

    @Query("SELECT * FROM messages WHERE messageId IN (:ids)")
    suspend fun messages(ids: List<String>): List<MessageEntity>

    @Query("UPDATE messages SET relayed = 1 WHERE messageId = :id")
    suspend fun markRelayed(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeer(peer: PeerEntity)

    @Query("SELECT * FROM peers WHERE nodeId = :nodeId")
    suspend fun peer(nodeId: String): PeerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDelivery(delivery: PeerDeliveryEntity)

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

    @Query("DELETE FROM messages WHERE createdAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM messages WHERE messageId NOT IN (SELECT messageId FROM messages ORDER BY createdAt DESC LIMIT :limit)")
    suspend fun trimTo(limit: Int)
}
