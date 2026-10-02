package com.resqnet.app.mesh

import com.resqnet.app.protocol.MeshFrame
import com.resqnet.app.mesh.barp.BarpController
import com.resqnet.app.mesh.barp.RelayMode
import com.resqnet.app.mesh.barp.BarpState
import com.resqnet.app.mesh.barp.isBarpCriticalTraffic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MeshCoordinator(
    private val transport: MeshTransport,
    private val router: MessageRouter,
    private val scope: CoroutineScope,
    private val barp: BarpController,
) {
    private var eventJob: Job? = null
    private var syncJob: Job? = null
    private var barpJob: Job? = null
    private var deferredScanChange: Job? = null
    private var lastBarpMode: RelayMode? = null
    private val connected = mutableSetOf<String>()

    suspend fun start() {
        if (eventJob != null) return
        eventJob = scope.launch { transport.events.collect(::handle) }
        barpJob = scope.launch { barp.state.collect { applyBarpState(it) } }
        syncJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(barp.state.value.syncIntervalMs)
                syncNow()
            }
        }
        router.cleanup(); transport.start()
    }

    suspend fun stop() {
        transport.stop(); eventJob?.cancel(); eventJob = null; syncJob?.cancel(); syncJob = null
        barpJob?.cancel(); barpJob = null; deferredScanChange?.cancel(); deferredScanChange = null
        connected.clear(); MeshRuntime.peers(0)
    }

    suspend fun syncNow() {
        MeshRuntime.synced()
        MeshRuntime.event("BARP sync #${MeshRuntime.state.value.syncCount} (${barp.state.value.mode.name.lowercase()})")
        connected.toList().forEach { sendInventory(it) }
    }

    private suspend fun applyBarpState(state: BarpState, forceScanApply: Boolean = false) {
        MeshRuntime.barp(state.mode.name.lowercase().replaceFirstChar { it.uppercase() }, state.battery.levelPercent, state.battery.powerSaveMode)
        if (state.mode != lastBarpMode) {
            MeshRuntime.event(
                "BARP mode ${state.mode.name.lowercase()} " +
                    "(battery ${state.battery.levelPercent?.let { "$it%" } ?: "unknown"}, " +
                    "power saver ${if (state.battery.powerSaveMode) "on" else "off"})",
            )
            lastBarpMode = state.mode
        } else if (!forceScanApply) return
        when (val result = transport.applyBarpMode(state.mode)) {
            BarpScanChange.Unchanged -> Unit
            BarpScanChange.Restarted -> MeshRuntime.event("BARP restarted BLE scan for ${state.mode.name.lowercase()} mode")
            is BarpScanChange.Deferred -> {
                MeshRuntime.event("BARP scan change deferred ${result.delayMs / 1_000}s to respect 30s dwell")
                deferredScanChange?.cancel()
                deferredScanChange = scope.launch {
                    kotlinx.coroutines.delay(result.delayMs)
                    if (barp.state.value.mode == state.mode) applyBarpState(state, forceScanApply = true)
                }
            }
        }
    }

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
                MeshRuntime.event("Received inventory page ${frame.page} from ${peerId.take(8)}")
                val missing = router.missingIds(frame.messageIds)
                missing.chunked(100).forEach { transport.send(peerId, MeshFrame.Request(it)) }
            }
            is MeshFrame.Request -> {
                MeshRuntime.event("Received request for ${frame.messageIds.size} items from ${peerId.take(8)}")
                router.requestedPackets(frame.messageIds)
                    .sortedByDescending { packet ->
                        runCatching { com.resqnet.app.protocol.ProtocolCodec.decodePayload(packet.packet.payloadBytes).isBarpCriticalTraffic() }
                            .getOrDefault(false)
                    }
                    .forEach { transport.send(peerId, MeshFrame.Packet(it)) }
            }
            is MeshFrame.Packet -> {
                MeshRuntime.event("Received packet from ${peerId.take(8)}")
                when (val result = router.ingest(frame.envelope, peerId)) {
                    is IngestResult.Projected -> { MeshRuntime.event("Received ${result.packetId.take(8)} via ${peerId.take(8)}"); transport.send(peerId, MeshFrame.Ack(result.packetId)) }
                    is IngestResult.StoredOnly -> { MeshRuntime.event("Stored ${result.packetId.take(8)} for relay"); transport.send(peerId, MeshFrame.Ack(result.packetId)) }
                    is IngestResult.Duplicate -> transport.send(peerId, MeshFrame.Ack(result.packetId))
                    is IngestResult.Rejected -> MeshRuntime.event("Rejected packet: ${result.reason}")
                }
            }
            is MeshFrame.Ack -> { router.acknowledged(frame.messageId, peerId); MeshRuntime.event("Relayed ${frame.messageId.take(8)}") }
        }
    }

    private suspend fun sendInventory(peerId: String) {
        val pages = router.inventory().chunked(100).ifEmpty { listOf(emptyList()) }
        pages.forEachIndexed { index, ids -> transport.send(peerId, MeshFrame.Inventory(index, index == pages.lastIndex, ids)) }
    }
}
