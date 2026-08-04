package com.resqnet.app.mesh

import com.resqnet.app.protocol.MeshFrame
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
}

interface MeshSession {
    val peerId: String
    suspend fun send(frame: MeshFrame): Boolean
    suspend fun close()
}
