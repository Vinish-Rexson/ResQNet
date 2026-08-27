package com.resqnet.app.circles

import com.resqnet.app.mesh.IngestResult
import com.resqnet.app.protocol.Audience
import com.resqnet.app.protocol.PacketBody
import com.resqnet.app.protocol.PacketKind
import com.resqnet.app.protocol.PayloadV2
import java.util.UUID

internal interface CirclePort {
    val localNodeId: String
    fun localDisplayName(): String
    fun now(): Long
    suspend fun createLocalCirclePacket(
        kind: PacketKind,
        audience: Audience,
        expiresAt: Long,
        packetId: UUID = UUID.randomUUID(),
        body: () -> PacketBody,
    ): PayloadV2
    suspend fun promoteCircleProcessed(packetId: String): IngestResult
    suspend fun suppressCircle(packetId: String): IngestResult
}

internal fun CircleStoreResult.asIngestResult(packetId: String): IngestResult = when (this) {
    CircleStoreResult.PROJECTED -> IngestResult.Projected(packetId)
    CircleStoreResult.STORED_ONLY, CircleStoreResult.SUPPRESSED -> IngestResult.StoredOnly(packetId)
}
