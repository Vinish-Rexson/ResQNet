package com.resqnet.app.data

import androidx.room.TypeConverter
import com.resqnet.app.protocol.AudienceType
import com.resqnet.app.protocol.PacketKind
import com.resqnet.app.protocol.RelayPolicy

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
}
