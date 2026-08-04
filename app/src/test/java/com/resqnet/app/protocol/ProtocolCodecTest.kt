package com.resqnet.app.protocol

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ProtocolCodecTest {
    @Test fun payloadRoundTripPreservesEveryField() {
        val original = ChatPayload(UUID.randomUUID(), originNodeId = "node-a", originSequence = 9,
            createdAt = 100, expiresAt = 200, text = "Flood route is clear ✅")
        assertEquals(original, ProtocolCodec.decodePayload(ProtocolCodec.encodePayload(original)))
    }

    @Test fun frameRoundTripSupportsChunkSizedPacket() {
        val payload = ProtocolCodec.encodePayload(ChatPayload(UUID.randomUUID(), originNodeId = "a".repeat(32),
            originSequence = 1, createdAt = 1, expiresAt = 2, text = "x".repeat(500)))
        val packet = SignedChatPacket(payload, ByteArray(72) { 3 }, ByteArray(91) { 4 })
        val frame = MeshFrame.Packet(RelayEnvelope(packet, 4, 2, listOf("a", "b")))
        val decoded = ProtocolCodec.decodeFrame(ProtocolCodec.encodeFrame(frame)) as MeshFrame.Packet
        assertArrayEquals(packet.payloadBytes, decoded.envelope.packet.payloadBytes)
        assertEquals(listOf("a", "b"), decoded.envelope.hopTrace)
    }

    @Test fun rejectsOversizedUtf8Message() {
        val payload = ChatPayload(UUID.randomUUID(), originNodeId = "node", originSequence = 1,
            createdAt = 1, expiresAt = 2, text = "é".repeat(251))
        assertThrows(IllegalArgumentException::class.java) { ProtocolCodec.encodePayload(payload) }
    }

    @Test fun rejectsTrailingBytes() {
        val bytes = ProtocolCodec.encodeFrame(MeshFrame.Ack("id")) + byteArrayOf(1)
        assertThrows(IllegalArgumentException::class.java) { ProtocolCodec.decodeFrame(bytes) }
    }
}
