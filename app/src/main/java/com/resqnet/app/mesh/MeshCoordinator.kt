package com.resqnet.app.mesh

import com.resqnet.app.protocol.MeshFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MeshCoordinator(
    private val transport: MeshTransport,
    private val router: MessageRouter,
    private val scope: CoroutineScope,
) {
    private var eventJob: Job? = null
    private val connected = mutableSetOf<String>()

    suspend fun start() {
        if (eventJob != null) return
        eventJob = scope.launch { transport.events.collect(::handle) }
        router.cleanup(); transport.start()
    }

    suspend fun stop() { transport.stop(); eventJob?.cancel(); eventJob = null; connected.clear(); MeshRuntime.peers(0) }

    suspend fun syncNow() { connected.toList().forEach { sendInventory(it) } }

    private suspend fun handle(event: TransportEvent) {
        when (event) {
            is TransportEvent.PeerFound -> MeshRuntime.event("Peer discovered ${event.peerId.take(8)}")
            is TransportEvent.PeerConnected -> {
                connected += event.peerId; MeshRuntime.peers(connected.size); MeshRuntime.event("Connected ${event.peerId.take(8)}")
                transport.send(event.peerId, MeshFrame.Hello(router.localProfile()))
            }
            is TransportEvent.PeerDisconnected -> { connected -= event.peerId; MeshRuntime.peers(connected.size); MeshRuntime.event("Disconnected ${event.peerId.take(8)}") }
            is TransportEvent.Error -> MeshRuntime.event("Error: ${event.summary}")
            is TransportEvent.FrameReceived -> handleFrame(event.peerId, event.frame)
        }
    }

    private suspend fun handleFrame(peerId: String, frame: MeshFrame) {
        when (frame) {
            is MeshFrame.Hello -> {
                if (!router.onHello(frame.profile)) { MeshRuntime.event("Rejected peer identity"); return }
                sendInventory(peerId)
            }
            is MeshFrame.Inventory -> {
                val missing = router.missingIds(frame.messageIds)
                missing.chunked(100).forEach { transport.send(peerId, MeshFrame.Request(it)) }
            }
            is MeshFrame.Request -> router.requestedPackets(frame.messageIds).forEach { transport.send(peerId, MeshFrame.Packet(it)) }
            is MeshFrame.Packet -> when (val result = router.ingest(frame.envelope, peerId)) {
                is IngestResult.Projected -> { MeshRuntime.event("Received ${result.packetId.take(8)} via ${peerId.take(8)}"); transport.send(peerId, MeshFrame.Ack(result.packetId)) }
                is IngestResult.StoredOnly -> { MeshRuntime.event("Stored ${result.packetId.take(8)} for relay"); transport.send(peerId, MeshFrame.Ack(result.packetId)) }
                is IngestResult.Duplicate -> transport.send(peerId, MeshFrame.Ack(result.packetId))
                is IngestResult.Rejected -> MeshRuntime.event("Rejected packet: ${result.reason}")
            }
            is MeshFrame.Ack -> { router.acknowledged(frame.messageId, peerId); MeshRuntime.event("Relayed ${frame.messageId.take(8)}") }
        }
    }

    private suspend fun sendInventory(peerId: String) {
        val pages = router.inventory().chunked(100).ifEmpty { listOf(emptyList()) }
        pages.forEachIndexed { index, ids -> transport.send(peerId, MeshFrame.Inventory(index, index == pages.lastIndex, ids)) }
    }
}
