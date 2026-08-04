package com.resqnet.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "messages", indices = [Index("createdAt"), Index("originNodeId")])
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val channelId: String,
    val originNodeId: String,
    val originName: String,
    val originSequence: Long,
    val createdAt: Long,
    val expiresAt: Long,
    val text: String,
    val packetBytes: ByteArray,
    val ttlRemaining: Int,
    val hopCount: Int,
    val hopTrace: String,
    val outgoing: Boolean,
    val relayed: Boolean = false,
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

@Entity(tableName = "local_state")
data class LocalStateEntity(@PrimaryKey val key: String, val longValue: Long)
