package com.resqnet.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.resqnet.app.protocol.AudienceType
import com.resqnet.app.protocol.PacketKind
import com.resqnet.app.protocol.RelayPolicy

enum class ProjectionState { STORED_ONLY, PROJECTED }

@Entity(
    tableName = "packets",
    indices = [Index("createdAt"), Index("originNodeId"), Index("relayEligible"), Index("supersessionKey")],
)
data class PacketEntity(
    @PrimaryKey val packetId: String,
    val kind: PacketKind,
    val audienceType: AudienceType,
    val audienceId: String?,
    val originNodeId: String,
    val originName: String,
    val originSequence: Long,
    val createdAt: Long,
    val expiresAt: Long,
    val relayPolicy: RelayPolicy,
    val supersessionKey: String?,
    val rawEnvelope: ByteArray,
    val ttlRemaining: Int,
    val hopCount: Int,
    val hopTrace: String,
    val relayEligible: Boolean,
    val projectionState: ProjectionState,
    val receivedAt: Long,
)

@Entity(
    tableName = "conversation_messages",
    indices = [Index("createdAt"), Index("originNodeId"), Index("conversationId")],
)
data class ConversationMessageEntity(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val kind: PacketKind,
    val audienceType: AudienceType,
    val audienceId: String?,
    val originNodeId: String,
    val originName: String,
    val originSequence: Long,
    val createdAt: Long,
    val expiresAt: Long,
    val text: String,
    val outgoing: Boolean,
    val relayed: Boolean = false,
    val hopCount: Int,
)

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val nodeId: String,
    val displayName: String,
    val publicKey: ByteArray,
    val fingerprint: String,
    val protocolVersion: Int,
    val lastSeenAt: Long,
)

@Entity(tableName = "deliveries", primaryKeys = ["messageId", "peerId"], indices = [Index("peerId")])
data class PeerDeliveryEntity(
    val messageId: String,
    val peerId: String,
    val acknowledged: Boolean,
    val retryCount: Int,
    val firstAttemptAt: Long,
    val lastAttemptAt: Long,
)

@Entity(
    tableName = "message_receipts",
    primaryKeys = ["messageId", "recipientNodeId"],
    indices = [Index("receiptPacketId")],
)
data class MessageReceiptEntity(
    val messageId: String,
    val recipientNodeId: String,
    val receiptPacketId: String,
    val receivedAt: Long,
)

@Entity(tableName = "local_state")
data class LocalStateEntity(@PrimaryKey val key: String, val longValue: Long)
