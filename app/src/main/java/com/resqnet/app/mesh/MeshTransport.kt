package com.resqnet.app.mesh

import com.resqnet.app.protocol.MeshFrame
import com.resqnet.app.mesh.barp.RelayMode
import com.resqnet.app.mesh.barp.ScanPowerMode
import kotlinx.coroutines.flow.Flow

data class PeerConnection(val peerId: String, val displayName: String?, val connected: Boolean)

sealed interface TransportEvent {
    data class PeerFound(val peerId: String) : TransportEvent
    data class PeerConnected(val peerId: String) : TransportEvent
    data class PeerDisconnected(val peerId: String) : TransportEvent
    data class FrameReceived(val peerId: String, val frame: MeshFrame) : TransportEvent
    data class Error(val summary: String) : TransportEvent
}

interface MeshTransport {
    val events: Flow<TransportEvent>
    suspend fun start()
    suspend fun stop()
    suspend fun send(peerId: String, frame: MeshFrame): Boolean
    suspend fun applyBarpMode(mode: RelayMode): BarpScanChange
}

sealed interface BarpScanChange {
    data object Unchanged : BarpScanChange
    data object Restarted : BarpScanChange
    data class Deferred(val delayMs: Long) : BarpScanChange
}

interface MeshSession {
    val peerId: String
    suspend fun send(frame: MeshFrame): Boolean
    suspend fun close()
}
