package com.resqnet.app.data

import androidx.room.TypeConverter
import com.resqnet.app.protocol.AudienceType
import com.resqnet.app.protocol.PacketKind
import com.resqnet.app.protocol.RelayPolicy
import com.resqnet.app.protocol.CircleMemberRole
import com.resqnet.app.protocol.SafetyStatus
import com.resqnet.app.circles.CircleInvitationState
import com.resqnet.app.circles.CircleLocalState

class PersistenceConverters {
    @TypeConverter fun packetKindToString(value: PacketKind): String = value.name
    @TypeConverter fun stringToPacketKind(value: String): PacketKind = PacketKind.valueOf(value)
    @TypeConverter fun audienceTypeToString(value: AudienceType): String = value.name
    @TypeConverter fun stringToAudienceType(value: String): AudienceType = AudienceType.valueOf(value)
    @TypeConverter fun relayPolicyToString(value: RelayPolicy): String = value.name
    @TypeConverter fun stringToRelayPolicy(value: String): RelayPolicy = RelayPolicy.valueOf(value)
    @TypeConverter fun projectionStateToString(value: ProjectionState): String = value.name
    @TypeConverter fun stringToProjectionState(value: String): ProjectionState = ProjectionState.valueOf(value)
    @TypeConverter fun contactStateToString(value: ContactState): String = value.name
    @TypeConverter fun stringToContactState(value: String): ContactState = ContactState.valueOf(value)
    @TypeConverter fun circleStateToString(value: CircleLocalState): String = value.name
    @TypeConverter fun stringToCircleState(value: String): CircleLocalState = CircleLocalState.valueOf(value)
    @TypeConverter fun invitationStateToString(value: CircleInvitationState): String = value.name
    @TypeConverter fun stringToInvitationState(value: String): CircleInvitationState = CircleInvitationState.valueOf(value)
    @TypeConverter fun circleRoleToString(value: CircleMemberRole): String = value.name
    @TypeConverter fun stringToCircleRole(value: String): CircleMemberRole = CircleMemberRole.valueOf(value)
    @TypeConverter fun safetyStatusToString(value: SafetyStatus): String = value.name
    @TypeConverter fun stringToSafetyStatus(value: String): SafetyStatus = SafetyStatus.valueOf(value)
}
