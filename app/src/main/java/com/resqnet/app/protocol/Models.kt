package com.resqnet.app.protocol

import java.util.UUID

const val TRANSPORT_VERSION = 2
const val PAYLOAD_VERSION = 2
const val CHANNEL_ID = "local-emergency"
const val MAX_TEXT_BYTES = 500
const val MAX_MESSAGE_BYTES = MAX_TEXT_BYTES
const val MAX_NOTE_BYTES = 160
const val MAX_HAZARD_NOTE_BYTES = 1_000
const val MAX_NAME_BYTES = 64
const val MAX_CIRCLE_MEMBERS = 20
const val DEFAULT_TTL = 6
const val MAX_HOPS = 6
const val MAX_HOP_TRACE = MAX_HOPS + 1
const val PROPAGATION_WINDOW_MS = 24L * 60 * 60 * 1000
const val HISTORY_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
const val MAX_RETAINED_MESSAGES = 10_000

data class NodeProfile(
    val nodeId: String,
    val displayName: String,
    val publicKey: ByteArray,
    val keyFingerprint: String,
    val transportVersion: Int = TRANSPORT_VERSION,
)

enum class PacketKind(val wireId: Int) {
    PUBLIC_TEXT(1),
    CONTACT_REQUEST(2),
    CONTACT_ACCEPT(3),
    CONTACT_DECLINE(4),
    DIRECT_TEXT(5),
    DELIVERY_RECEIPT(6),
    CIRCLE_INVITE(7),
    CIRCLE_INVITE_ACCEPT(8),
    CIRCLE_INVITE_DECLINE(9),
    CIRCLE_MEMBERSHIP_SNAPSHOT(10),
    CIRCLE_TEXT(11),
    CIRCLE_STATUS(12),
    CIRCLE_LEAVE_REQUEST(13),
    HAZARD_REPORT(14);

    companion object {
        fun fromWireId(id: Int) = entries.firstOrNull { it.wireId == id }
            ?: throw IllegalArgumentException("Unsupported packet kind $id")
    }
}

enum class AudienceType { PUBLIC_CHANNEL, DIRECT_NODE, CIRCLE }

sealed interface Audience {
    val type: AudienceType
    val id: String?

    data object PublicChannel : Audience {
        override val type = AudienceType.PUBLIC_CHANNEL
        override val id: String? = CHANNEL_ID
    }
    data class DirectNode(val nodeId: String) : Audience {
        override val type = AudienceType.DIRECT_NODE
        override val id: String = nodeId
    }
    data class Circle(val circleId: String) : Audience {
        override val type = AudienceType.CIRCLE
        override val id: String = circleId
    }
}

enum class RelayPolicy(val wireId: Int) {
    EPHEMERAL(1),
    DURABLE_UNTIL_SUPERSEDED(2),
    DURABLE_UNTIL_RESOLVED(3);

    companion object {
        fun fromWireId(id: Int) = entries.firstOrNull { it.wireId == id }
            ?: throw IllegalArgumentException("Unsupported relay policy $id")
    }
}

val PacketKind.requiredRelayPolicy: RelayPolicy
    get() = when (this) {
        PacketKind.CIRCLE_MEMBERSHIP_SNAPSHOT -> RelayPolicy.DURABLE_UNTIL_SUPERSEDED
        PacketKind.CIRCLE_INVITE_ACCEPT,
        PacketKind.CIRCLE_LEAVE_REQUEST,
        -> RelayPolicy.DURABLE_UNTIL_RESOLVED
        PacketKind.PUBLIC_TEXT,
        PacketKind.CONTACT_REQUEST,
        PacketKind.CONTACT_ACCEPT,
        PacketKind.CONTACT_DECLINE,
        PacketKind.DIRECT_TEXT,
        PacketKind.DELIVERY_RECEIPT,
        PacketKind.CIRCLE_INVITE,
        PacketKind.CIRCLE_INVITE_DECLINE,
        PacketKind.CIRCLE_TEXT,
        PacketKind.CIRCLE_STATUS,
        PacketKind.HAZARD_REPORT,
        -> RelayPolicy.EPHEMERAL
    }

enum class SafetyStatus(val wireId: Int) {
    UNKNOWN(1), SAFE(2), NEED_HELP(3);

    companion object {
        fun fromWireId(id: Int) = entries.firstOrNull { it.wireId == id }
            ?: throw IllegalArgumentException("Unsupported safety status $id")
    }
}

sealed interface PacketBody { val kind: PacketKind }
data class PublicTextBody(val text: String) : PacketBody { override val kind = PacketKind.PUBLIC_TEXT }
data class ContactRequestBody(val requestId: String, val requesterName: String) : PacketBody { override val kind = PacketKind.CONTACT_REQUEST }
data class ContactAcceptBody(val requestId: String) : PacketBody { override val kind = PacketKind.CONTACT_ACCEPT }
data class ContactDeclineBody(val requestId: String) : PacketBody { override val kind = PacketKind.CONTACT_DECLINE }
data class DirectTextBody(val conversationId: String, val text: String) : PacketBody { override val kind = PacketKind.DIRECT_TEXT }
data class DeliveryReceiptBody(
    val messageId: String,
    val circleId: String? = null,
    val membershipVersion: Long? = null,
) : PacketBody {
    init {
        require((circleId == null) == (membershipVersion == null)) {
            "Circle receipt metadata must be complete"
        }
    }
    override val kind = PacketKind.DELIVERY_RECEIPT
}
enum class CircleMemberRole(val wireId: Int) {
    OWNER(1), MEMBER(2);

    companion object {
        fun fromWireId(id: Int) = entries.firstOrNull { it.wireId == id }
            ?: throw IllegalArgumentException("Unsupported Circle member role $id")
    }
}
data class CircleSnapshotMember(val nodeId: String, val role: CircleMemberRole)
data class CircleInviteBody(
    val inviteId: String,
    val circleId: String,
    val circleName: String,
    val ownerNodeId: String,
    val membershipVersion: Long,
    val expiresAt: Long,
    val activeMemberPreview: List<CircleSnapshotMember> = emptyList(),
) : PacketBody { override val kind = PacketKind.CIRCLE_INVITE }
data class CircleInviteAcceptBody(val inviteId: String, val circleId: String) : PacketBody { override val kind = PacketKind.CIRCLE_INVITE_ACCEPT }
data class CircleInviteDeclineBody(val inviteId: String, val circleId: String) : PacketBody { override val kind = PacketKind.CIRCLE_INVITE_DECLINE }
data class CircleMembershipSnapshotBody(
    val circleId: String,
    val membershipVersion: Long,
    val circleName: String,
    val ownerNodeId: String,
    val members: List<CircleSnapshotMember>,
    val dissolved: Boolean = false,
) : PacketBody {
    val activeMemberNodeIds: List<String> get() = members.map { it.nodeId }
    override val kind = PacketKind.CIRCLE_MEMBERSHIP_SNAPSHOT
}
data class CircleTextBody(val circleId: String, val membershipVersion: Long, val text: String) : PacketBody { override val kind = PacketKind.CIRCLE_TEXT }
data class CircleStatusBody(
    val circleId: String,
    val membershipVersion: Long,
    val subjectNodeId: String,
    val status: SafetyStatus,
    val note: String?,
) : PacketBody { override val kind = PacketKind.CIRCLE_STATUS }
data class CircleLeaveRequestBody(
    val circleId: String,
    val membershipVersion: Long,
) : PacketBody { override val kind = PacketKind.CIRCLE_LEAVE_REQUEST }

/** A public, signed report used to keep offline route avoidance data in sync over the mesh. */
enum class HazardReportType(val wireId: Int) {
    FLOOD(1), UNSAFE_AREA(2);

    companion object {
        fun fromWireId(id: Int) = entries.firstOrNull { it.wireId == id }
            ?: throw IllegalArgumentException("Unsupported hazard type $id")
    }
}

data class HazardReportBody(
    val reportId: String,
    val type: HazardReportType,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
    val note: String?,
    val updatedAt: Long,
) : PacketBody { override val kind = PacketKind.HAZARD_REPORT }

data class PayloadV2(
    val packetId: UUID,
    val kind: PacketKind,
    val audience: Audience,
    val originNodeId: String,
    val originDisplayName: String,
    val originSequence: Long,
    val createdAt: Long,
    val expiresAt: Long,
    val relayPolicy: RelayPolicy,
    val body: PacketBody,
    val payloadVersion: Int = PAYLOAD_VERSION,
)

data class SignedPacket(
    val payloadBytes: ByteArray,
    val signature: ByteArray,
    val originPublicKey: ByteArray,
)

data class RelayEnvelope(
    val packet: SignedPacket,
    val ttlRemaining: Int = DEFAULT_TTL,
    val hopCount: Int = 0,
    val hopTrace: List<String> = emptyList(),
)

fun RelayEnvelope.boundsViolation(requireRelayable: Boolean = false): String? = when {
    ttlRemaining !in 0..DEFAULT_TTL -> "Invalid TTL"
    requireRelayable && ttlRemaining == 0 -> "TTL exhausted"
    hopCount !in 0..MAX_HOPS -> "Invalid hop count"
    ttlRemaining + hopCount > DEFAULT_TTL -> "TTL/hop budget exceeded"
    hopTrace.size > MAX_HOP_TRACE -> "Hop trace too long"
    else -> null
}

sealed interface MeshFrame {
    data class Hello(val profile: NodeProfile) : MeshFrame
    data class Inventory(val page: Int, val lastPage: Boolean, val messageIds: List<String>) : MeshFrame
    data class Request(val messageIds: List<String>) : MeshFrame
    data class Packet(val envelope: RelayEnvelope) : MeshFrame
    data class Ack(val messageId: String) : MeshFrame
}

enum class MessageStatus { QUEUED, RELAYED, RECEIVED }
