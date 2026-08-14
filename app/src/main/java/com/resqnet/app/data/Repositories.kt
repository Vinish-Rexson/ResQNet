package com.resqnet.app.data

import androidx.room.withTransaction
import com.resqnet.app.protocol.MAX_RETAINED_MESSAGES
import com.resqnet.app.protocol.NodeProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface PacketRepository {
    suspend fun insert(packet: PacketEntity): Boolean
    suspend fun find(packetId: String): PacketEntity?
    suspend fun inventoryIds(): List<String>
    suspend fun findAll(packetIds: List<String>): List<PacketEntity>
    suspend fun nextSequence(): Long
    suspend fun markProjected(packetId: String): Boolean
    suspend fun markSuppressed(packetId: String): Boolean = false
    suspend fun markRelayed(packetId: String, peerId: String)
    suspend fun cleanup()
}

interface ConversationRepository {
    fun observeMessages(): Flow<List<ConversationMessageEntity>>
    fun observeConversation(conversationId: String): Flow<List<ConversationMessageEntity>> =
        observeMessages().map { messages -> messages.filter { it.conversationId == conversationId } }
    suspend fun insert(message: ConversationMessageEntity): Boolean
    suspend fun find(messageId: String): ConversationMessageEntity?
    suspend fun markRelayed(messageId: String)
    suspend fun markDelivered(messageId: String) = Unit
    suspend fun cleanup()
}

interface ReceiptRepository {
    suspend fun find(messageId: String, recipientNodeId: String): MessageReceiptEntity?
    suspend fun insert(receipt: MessageReceiptEntity): Boolean
}

interface LocalProjectionRepository {
    suspend fun persist(packet: PacketEntity, message: ConversationMessageEntity): Boolean
}

interface PeerRepository {
    suspend fun upsert(profile: NodeProfile)
    suspend fun find(nodeId: String): PeerEntity?
}

interface ContactRepository {
    fun observeContacts(): Flow<List<ContactEntity>>
    suspend fun find(nodeId: String): ContactEntity?
    suspend fun upsert(contact: ContactEntity)
    suspend fun delete(nodeId: String)
    suspend fun deleteExpiredPending(now: Long)
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
    override suspend fun markProjected(packetId: String) = dao.markPacketProjected(packetId) > 0
    override suspend fun markSuppressed(packetId: String) = dao.markPacketSuppressed(packetId) > 0
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
    override suspend fun find(messageId: String) = dao.conversationMessage(messageId)
    override suspend fun markRelayed(messageId: String) = dao.markConversationRelayed(messageId)
    override suspend fun markDelivered(messageId: String) = dao.markConversationDelivered(messageId)
    override suspend fun cleanup() = dao.trimConversationMessages(MAX_RETAINED_MESSAGES)
}

class RoomReceiptRepository(private val dao: MeshDao) : ReceiptRepository {
    override suspend fun find(messageId: String, recipientNodeId: String) = dao.receipt(messageId, recipientNodeId)
    override suspend fun insert(receipt: MessageReceiptEntity) = dao.insertReceipt(receipt) != -1L
}

class RoomLocalProjectionRepository(
    private val database: ResQNetDatabase,
    private val dao: MeshDao,
) : LocalProjectionRepository {
    override suspend fun persist(
        packet: PacketEntity,
        message: ConversationMessageEntity,
    ): Boolean = database.withTransaction {
        val existingPacket = dao.packet(packet.packetId)
        if (existingPacket == null) {
            if (dao.insertPacket(packet) == -1L) return@withTransaction false
        } else if (!sameLocalPacket(existingPacket, packet)) {
            return@withTransaction false
        }

        val existingMessage = dao.conversationMessage(message.messageId)
        if (existingMessage == null) {
            if (dao.insertConversationMessage(message) == -1L) return@withTransaction false
        } else if (!sameLocalMessage(existingMessage, message)) {
            return@withTransaction false
        }

        if (dao.markPacketProjected(packet.packetId) == 0 &&
            dao.packet(packet.packetId)?.projectionState != ProjectionState.PROJECTED
        ) return@withTransaction false
        dao.trimConversationMessages(MAX_RETAINED_MESSAGES)
        true
    }

    private fun sameLocalPacket(existing: PacketEntity, expected: PacketEntity): Boolean =
        existing.rawEnvelope.contentEquals(expected.rawEnvelope) &&
            existing.copy(
                rawEnvelope = expected.rawEnvelope,
                projectionState = expected.projectionState,
            ) == expected

    private fun sameLocalMessage(
        existing: ConversationMessageEntity,
        expected: ConversationMessageEntity,
    ): Boolean = existing.copy(
        originName = expected.originName,
        relayed = expected.relayed,
        delivered = expected.delivered,
    ) == expected
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

class RoomContactRepository(private val dao: MeshDao) : ContactRepository {
    override fun observeContacts() = dao.observeContacts()
    override suspend fun find(nodeId: String) = dao.contact(nodeId)
    override suspend fun upsert(contact: ContactEntity) = dao.upsertContact(contact)
    override suspend fun delete(nodeId: String) = dao.deleteContact(nodeId)
    override suspend fun deleteExpiredPending(now: Long) {
        dao.pendingContacts().forEach { contact ->
            val normalized = contact.withoutExpiredRequests(now)
            if (normalized == null) dao.deleteContact(contact.nodeId) else dao.upsertContact(normalized)
        }
    }
}
