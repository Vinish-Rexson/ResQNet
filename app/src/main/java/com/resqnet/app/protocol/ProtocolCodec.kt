package com.resqnet.app.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

object ProtocolCodec {
    private const val MAX_FIELD_BYTES = 64 * 1024
    private const val MAX_IDS_PER_FRAME = 250
    private const val MAX_HOPS = 32

    fun encodePayload(payload: ChatPayload): ByteArray = output { out ->
        out.writeInt(0x52515031) // RQP1
        out.writeInt(payload.protocolVersion)
        out.writeLong(payload.messageId.mostSignificantBits)
        out.writeLong(payload.messageId.leastSignificantBits)
        out.writeString(payload.channelId)
        out.writeString(payload.originNodeId)
        out.writeString(payload.originDisplayName)
        out.writeLong(payload.originSequence)
        out.writeLong(payload.createdAt)
        out.writeLong(payload.expiresAt)
        val textBytes = payload.text.toByteArray(StandardCharsets.UTF_8)
        require(textBytes.size <= MAX_MESSAGE_BYTES) { "Message exceeds $MAX_MESSAGE_BYTES UTF-8 bytes" }
        out.writeBytesWithLength(textBytes)
    }

    fun decodePayload(bytes: ByteArray): ChatPayload = input(bytes) { source ->
        require(source.readInt() == 0x52515031) { "Invalid payload magic" }
        val version = source.readInt()
        val id = UUID(source.readLong(), source.readLong())
        val channel = source.readString()
        val node = source.readString()
        val name = source.readString()
        val sequence = source.readLong()
        val created = source.readLong()
        val expires = source.readLong()
        val textBytes = source.readBytesWithLength(MAX_MESSAGE_BYTES)
        require(source.available() == 0) { "Trailing payload bytes" }
        ChatPayload(id, channel, node, name, sequence, created, expires,
            textBytes.toString(StandardCharsets.UTF_8), version)
    }

    fun encodePacket(packet: SignedChatPacket): ByteArray = output { out ->
        out.writeInt(0x52515331) // RQS1
        out.writeBytesWithLength(packet.payloadBytes)
        out.writeBytesWithLength(packet.signature)
        out.writeBytesWithLength(packet.originPublicKey)
    }

    fun decodePacket(bytes: ByteArray): SignedChatPacket = input(bytes) { source ->
        require(source.readInt() == 0x52515331) { "Invalid packet magic" }
        SignedChatPacket(
            source.readBytesWithLength(MAX_FIELD_BYTES),
            source.readBytesWithLength(1_024),
            source.readBytesWithLength(4_096),
        ).also { require(source.available() == 0) { "Trailing packet bytes" } }
    }

    fun encodeEnvelope(envelope: RelayEnvelope): ByteArray = output { out ->
        out.writeInt(0x52514531) // RQE1
        out.writeBytesWithLength(encodePacket(envelope.packet))
        out.writeInt(envelope.ttlRemaining)
        out.writeInt(envelope.hopCount)
        require(envelope.hopTrace.size <= MAX_HOPS)
        out.writeInt(envelope.hopTrace.size)
        envelope.hopTrace.forEach { out.writeString(it) }
    }

    fun decodeEnvelope(bytes: ByteArray): RelayEnvelope = input(bytes) { source ->
        require(source.readInt() == 0x52514531) { "Invalid envelope magic" }
        val packet = decodePacket(source.readBytesWithLength(MAX_FIELD_BYTES))
        val ttl = source.readInt()
        val hops = source.readInt()
        val count = source.readInt()
        require(ttl in 0..DEFAULT_TTL && hops in 0..MAX_HOPS && count in 0..MAX_HOPS)
        RelayEnvelope(packet, ttl, hops, List(count) { source.readString() })
            .also { require(source.available() == 0) { "Trailing envelope bytes" } }
    }

    fun encodeFrame(frame: MeshFrame): ByteArray = output { out ->
        out.writeInt(0x52514631) // RQF1
        when (frame) {
            is MeshFrame.Hello -> {
                out.writeByte(1); out.writeString(frame.profile.nodeId); out.writeString(frame.profile.displayName)
                out.writeBytesWithLength(frame.profile.publicKey); out.writeString(frame.profile.keyFingerprint)
                out.writeInt(frame.profile.protocolVersion)
            }
            is MeshFrame.Inventory -> {
                out.writeByte(2); out.writeInt(frame.page); out.writeBoolean(frame.lastPage)
                out.writeStringList(frame.messageIds)
            }
            is MeshFrame.Request -> { out.writeByte(3); out.writeStringList(frame.messageIds) }
            is MeshFrame.Packet -> { out.writeByte(4); out.writeBytesWithLength(encodeEnvelope(frame.envelope)) }
            is MeshFrame.Ack -> { out.writeByte(5); out.writeString(frame.messageId) }
        }
    }

    fun decodeFrame(bytes: ByteArray): MeshFrame = input(bytes) { source ->
        require(source.readInt() == 0x52514631) { "Invalid frame magic" }
        val frame = when (source.readUnsignedByte()) {
            1 -> MeshFrame.Hello(NodeProfile(source.readString(), source.readString(),
                source.readBytesWithLength(4_096), source.readString(), source.readInt()))
            2 -> MeshFrame.Inventory(source.readInt(), source.readBoolean(), source.readStringList())
            3 -> MeshFrame.Request(source.readStringList())
            4 -> MeshFrame.Packet(decodeEnvelope(source.readBytesWithLength(MAX_FIELD_BYTES)))
            5 -> MeshFrame.Ack(source.readString())
            else -> error("Unsupported frame type")
        }
        require(source.available() == 0) { "Trailing frame bytes" }
        frame
    }

    private fun output(block: (DataOutputStream) -> Unit): ByteArray =
        ByteArrayOutputStream().use { bytes -> DataOutputStream(bytes).use(block); bytes.toByteArray() }

    private fun <T> input(bytes: ByteArray, block: (DataInputStream) -> T): T =
        DataInputStream(ByteArrayInputStream(bytes)).use(block)

    private fun DataOutputStream.writeString(value: String) = writeBytesWithLength(value.toByteArray(StandardCharsets.UTF_8))
    private fun DataOutputStream.writeBytesWithLength(value: ByteArray) { require(value.size <= MAX_FIELD_BYTES); writeInt(value.size); write(value) }
    private fun DataInputStream.readString(): String = readBytesWithLength(MAX_FIELD_BYTES).toString(StandardCharsets.UTF_8)
    private fun DataInputStream.readBytesWithLength(max: Int): ByteArray { val size = readInt(); require(size in 0..max); return ByteArray(size).also(::readFully) }
    private fun DataOutputStream.writeStringList(values: List<String>) { require(values.size <= MAX_IDS_PER_FRAME); writeInt(values.size); values.forEach { writeString(it) } }
    private fun DataInputStream.readStringList(): List<String> { val count = readInt(); require(count in 0..MAX_IDS_PER_FRAME); return List(count) { readString() } }
}
