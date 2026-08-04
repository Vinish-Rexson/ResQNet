package com.resqnet.app.data

import com.resqnet.app.protocol.NodeProfile
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    fun observeMessages(): Flow<List<MessageEntity>>
    suspend fun insert(message: MessageEntity): Boolean
    suspend fun find(messageId: String): MessageEntity?
    suspend fun recentIds(): List<String>
    suspend fun findAll(ids: List<String>): List<MessageEntity>
    suspend fun nextSequence(): Long
    suspend fun markRelayed(messageId: String, peerId: String)
    suspend fun cleanup()
}

interface PeerRepository {
    suspend fun upsert(profile: NodeProfile)
    suspend fun find(nodeId: String): PeerEntity?
}

class RoomMessageRepository(private val dao: MeshDao, private val clock: () -> Long = System::currentTimeMillis) : MessageRepository {
    override fun observeMessages() = dao.observeMessages()
    override suspend fun insert(message: MessageEntity) = dao.insertMessage(message) != -1L
    override suspend fun find(messageId: String) = dao.message(messageId)
    override suspend fun recentIds() = dao.recentMessageIds(clock())
    override suspend fun findAll(ids: List<String>) = if (ids.isEmpty()) emptyList() else dao.messages(ids)
    override suspend fun nextSequence() = dao.nextSequence()
    override suspend fun markRelayed(messageId: String, peerId: String) {
        val now = clock(); dao.markRelayed(messageId)
        dao.upsertDelivery(PeerDeliveryEntity(messageId, peerId, true, 0, now, now))
    }
    override suspend fun cleanup() { dao.deleteOlderThan(clock() - com.resqnet.app.protocol.HISTORY_WINDOW_MS); dao.trimTo(com.resqnet.app.protocol.MAX_RETAINED_MESSAGES) }
}

class RoomPeerRepository(private val dao: MeshDao, private val clock: () -> Long = System::currentTimeMillis) : PeerRepository {
    override suspend fun upsert(profile: NodeProfile) = dao.upsertPeer(PeerEntity(profile.nodeId, profile.displayName,
        profile.publicKey, profile.keyFingerprint, profile.protocolVersion, clock()))
    override suspend fun find(nodeId: String) = dao.peer(nodeId)
}
