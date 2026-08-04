package com.resqnet.app.protocol

import java.util.UUID

const val PROTOCOL_VERSION = 1
const val CHANNEL_ID = "local-emergency"
const val MAX_MESSAGE_BYTES = 500
const val DEFAULT_TTL = 6
const val PROPAGATION_WINDOW_MS = 24L * 60 * 60 * 1000
const val HISTORY_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
const val MAX_RETAINED_MESSAGES = 2_000

data class NodeProfile(
    val nodeId: String,
    val displayName: String,
    val publicKey: ByteArray,
    val keyFingerprint: String,
    val protocolVersion: Int = PROTOCOL_VERSION,
)

data class ChatPayload(
    val messageId: UUID,
    val channelId: String = CHANNEL_ID,
    val originNodeId: String,
    val originDisplayName: String = "Unknown",
    val originSequence: Long,
    val createdAt: Long,
    val expiresAt: Long,
    val text: String,
    val protocolVersion: Int = PROTOCOL_VERSION,
)

data class SignedChatPacket(
    val payloadBytes: ByteArray,
    val signature: ByteArray,
    val originPublicKey: ByteArray,
)

data class RelayEnvelope(
    val packet: SignedChatPacket,
    val ttlRemaining: Int = DEFAULT_TTL,
    val hopCount: Int = 0,
    val hopTrace: List<String> = emptyList(),
)

sealed interface MeshFrame {
    data class Hello(val profile: NodeProfile) : MeshFrame
    data class Inventory(val page: Int, val lastPage: Boolean, val messageIds: List<String>) : MeshFrame
    data class Request(val messageIds: List<String>) : MeshFrame
    data class Packet(val envelope: RelayEnvelope) : MeshFrame
    data class Ack(val messageId: String) : MeshFrame
}

enum class MessageStatus { QUEUED, RELAYED, RECEIVED }
