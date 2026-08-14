package com.resqnet.app.data

import com.resqnet.app.protocol.MAX_RETAINED_MESSAGES
import com.resqnet.app.protocol.NodeProfile
import kotlinx.coroutines.flow.Flow

interface PacketRepository {
    suspend fun insert(packet: PacketEntity): Boolean
    suspend fun find(packetId: String): PacketEntity?
    suspend fun inventoryIds(): List<String>
    suspend fun findAll(packetIds: List<String>): List<PacketEntity>
    suspend fun nextSequence(): Long
    suspend fun markRelayed(packetId: String, peerId: String)
    suspend fun cleanup()
}

interface ConversationRepository {
    fun observeMessages(): Flow<List<ConversationMessageEntity>>
    suspend fun insert(message: ConversationMessageEntity): Boolean
    suspend fun markRelayed(messageId: String)
    suspend fun cleanup()
}

interface PeerRepository {
    suspend fun upsert(profile: NodeProfile)
    suspend fun find(nodeId: String): PeerEntity?
}

class RoomPacketRepository(
    private val dao: MeshDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : PacketRepository {
    override suspend fun insert(packet: PacketEntity) = dao.insertPacket(packet) != -1L
    override suspend fun find(packetId: String) = dao.packet(packetId)
    override suspend fun inventoryIds() = dao.inventoryPacketIds(clock())
    override suspend fun findAll(packetIds: List<String>) = if (packetIds.isEmpty()) emptyList() else dao.packets(packetIds)
    override suspend fun nextSequence() = dao.nextSequence()
    override suspend fun markRelayed(packetId: String, peerId: String) {
        val now = clock()
        dao.upsertDelivery(PeerDeliveryEntity(packetId, peerId, true, 0, now, now))
    }
    override suspend fun cleanup() = dao.deleteExpiredEphemeralPackets(clock())
}

class RoomConversationRepository(private val dao: MeshDao) : ConversationRepository {
    override fun observeMessages() = dao.observeMessages()
    override suspend fun insert(message: ConversationMessageEntity): Boolean {
        val inserted = dao.insertConversationMessage(message) != -1L
        if (inserted) dao.trimConversationMessages(MAX_RETAINED_MESSAGES)
        return inserted
    }
    override suspend fun markRelayed(messageId: String) = dao.markConversationRelayed(messageId)
    override suspend fun cleanup() = dao.trimConversationMessages(MAX_RETAINED_MESSAGES)
}

class RoomPeerRepository(
    private val dao: MeshDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : PeerRepository {
    override suspend fun upsert(profile: NodeProfile) = dao.upsertPeer(
        PeerEntity(
            profile.nodeId, profile.displayName, profile.publicKey, profile.keyFingerprint,
            profile.transportVersion, clock(),
        ),
    )
    override suspend fun find(nodeId: String) = dao.peer(nodeId)
}
