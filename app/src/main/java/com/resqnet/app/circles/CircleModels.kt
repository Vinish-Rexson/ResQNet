package com.resqnet.app.circles

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import com.resqnet.app.protocol.CircleMemberRole
import com.resqnet.app.protocol.CircleSnapshotMember
import com.resqnet.app.protocol.PacketKind
import com.resqnet.app.protocol.SafetyStatus

enum class CircleLocalState {
    OWNER_ACTIVE,
    ACTIVE,
    INVITED,
    ACCEPTANCE_PENDING,
    LEAVE_PENDING,
    ARCHIVED_REMOVED,
    ARCHIVED_DISSOLVED,
}

enum class CircleInvitationState { PENDING, ACCEPTANCE_PENDING, DECLINED, ACCEPTED, EXPIRED }

@Entity(tableName = "circles")
data class CircleEntity(
    @androidx.room.PrimaryKey val circleId: String,
    val name: String,
    val ownerNodeId: String,
    val currentMembershipVersion: Long,
    val localState: CircleLocalState,
    val leaveRequestPacketId: String?,
    val leaveRequestMembershipVersion: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "circle_invitations", indices = [Index("circleId"), Index("targetNodeId")])
data class CircleInvitationEntity(
    @androidx.room.PrimaryKey val inviteId: String,
    val packetId: String,
    val circleId: String,
    val circleName: String,
    val ownerNodeId: String,
    val targetNodeId: String,
    val membershipVersion: Long,
    val expiresAt: Long,
    val state: CircleInvitationState,
    val acceptancePacketId: String?,
    val activeMemberPreviewEncoded: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    @get:Ignore
    val activeMemberPreview: List<CircleSnapshotMember>
        get() = activeMemberPreviewEncoded.split(',').filter { it.isNotBlank() }.map { item ->
            val (role, nodeId) = item.split(':', limit = 2)
            CircleSnapshotMember(nodeId, CircleMemberRole.valueOf(role))
        }
}

fun encodeCircleMembers(members: List<CircleSnapshotMember>): String =
    members.joinToString(",") { "${it.role.name}:${it.nodeId}" }

@Entity(tableName = "circle_snapshots", primaryKeys = ["circleId", "membershipVersion"], indices = [Index("packetId")])
data class CircleSnapshotEntity(
    val circleId: String,
    val membershipVersion: Long,
    val circleName: String,
    val ownerNodeId: String,
    val dissolved: Boolean,
    val packetId: String,
    val originSequence: Long,
    val createdAt: Long,
)

@Entity(tableName = "circle_members", primaryKeys = ["circleId", "membershipVersion", "nodeId"], indices = [Index("nodeId")])
data class CircleMemberEntity(
    val circleId: String,
    val membershipVersion: Long,
    val nodeId: String,
    val role: CircleMemberRole,
)

@Entity(tableName = "circle_messages", indices = [Index("circleId"), Index("createdAt")])
data class CircleMessageEntity(
    @androidx.room.PrimaryKey val messageId: String,
    val circleId: String,
    val membershipVersion: Long,
    val originNodeId: String,
    val originName: String,
    val originSequence: Long,
    val createdAt: Long,
    val text: String,
    val outgoing: Boolean,
    val hopCount: Int,
)

@Entity(tableName = "pending_circle_packets", indices = [Index("circleId")])
data class PendingCirclePacketEntity(
    @androidx.room.PrimaryKey val packetId: String,
    val circleId: String,
    val membershipVersion: Long,
    val kind: PacketKind,
)

@Entity(tableName = "circle_message_receipts", primaryKeys = ["messageId", "recipientNodeId"], indices = [Index("receiptPacketId")])
data class CircleMessageReceiptEntity(
    val messageId: String,
    val recipientNodeId: String,
    val receiptPacketId: String,
    val receivedAt: Long,
)

@Entity(tableName = "circle_status_events", indices = [Index(value = ["circleId", "memberNodeId", "originSequence"])])
data class CircleStatusEventEntity(
    @androidx.room.PrimaryKey val packetId: String,
    val circleId: String,
    val membershipVersion: Long,
    val memberNodeId: String,
    val status: SafetyStatus,
    val note: String?,
    val originSequence: Long,
    val createdAt: Long,
)

data class CircleDeliveryProgress(val delivered: Int, val possible: Int)

data class EffectiveCircleStatus(
    val effectiveStatus: SafetyStatus,
    val lastReportedStatus: SafetyStatus,
    val lastNote: String?,
    val lastReportedAt: Long,
    val staleNeedHelpProminent: Boolean,
)
