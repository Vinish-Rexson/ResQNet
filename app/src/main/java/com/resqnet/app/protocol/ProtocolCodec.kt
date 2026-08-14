package com.resqnet.app.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID

object ProtocolCodec {
    private const val PAYLOAD_MAGIC = 0x52515032 // RQP2
    private const val PACKET_MAGIC = 0x52515332 // RQS2
    private const val ENVELOPE_MAGIC = 0x52514532 // RQE2
    private const val FRAME_MAGIC = 0x52514632 // RQF2
    private const val MAX_FIELD_BYTES = 64 * 1024
    private const val MAX_ID_BYTES = 128
    private const val MAX_IDS_PER_FRAME = 250

    fun encodePayload(payload: PayloadV2): ByteArray = output { out ->
        require(payload.payloadVersion == PAYLOAD_VERSION) { "Unsupported payload version ${payload.payloadVersion}" }
        require(payload.kind == payload.body.kind) { "Packet kind does not match body" }
        require(validAudience(payload.kind, payload.audience)) { "Packet kind does not match audience" }
        require(validAudienceBody(payload.audience, payload.body)) { "Audience ID does not match body" }
        require(payload.relayPolicy == payload.kind.requiredRelayPolicy) { "Packet kind does not match relay policy" }
        out.writeInt(PAYLOAD_MAGIC)
        out.writeInt(payload.payloadVersion)
        out.writeLong(payload.packetId.mostSignificantBits)
        out.writeLong(payload.packetId.leastSignificantBits)
        out.writeByte(payload.kind.wireId)
        out.writeAudience(payload.audience)
        out.writeBoundedString(payload.originNodeId, MAX_ID_BYTES)
        out.writeBoundedString(payload.originDisplayName, MAX_NAME_BYTES)
        out.writeLong(payload.originSequence)
        out.writeLong(payload.createdAt)
        out.writeLong(payload.expiresAt)
        out.writeByte(payload.relayPolicy.wireId)
        out.writeBody(payload.body)
    }

    fun decodePayload(bytes: ByteArray): PayloadV2 = input(bytes) { source ->
        require(source.readInt() == PAYLOAD_MAGIC) { "Invalid payload magic" }
        val version = source.readInt()
        require(version == PAYLOAD_VERSION) { "Unsupported payload version $version" }
        val id = UUID(source.readLong(), source.readLong())
        val kind = PacketKind.fromWireId(source.readUnsignedByte())
        val audience = source.readAudience()
        val nodeId = source.readBoundedString(MAX_ID_BYTES)
        val name = source.readBoundedString(MAX_NAME_BYTES)
        val sequence = source.readLong()
        val createdAt = source.readLong()
        val expiresAt = source.readLong()
        val relayPolicy = RelayPolicy.fromWireId(source.readUnsignedByte())
        val body = source.readBody(kind)
        require(source.available() == 0) { "Trailing payload bytes" }
        require(validAudience(kind, audience)) { "Packet kind does not match audience" }
        require(validAudienceBody(audience, body)) { "Audience ID does not match body" }
        require(relayPolicy == kind.requiredRelayPolicy) { "Packet kind does not match relay policy" }
        PayloadV2(id, kind, audience, nodeId, name, sequence, createdAt, expiresAt, relayPolicy, body, version)
    }

    fun encodePacket(packet: SignedPacket): ByteArray = output { out ->
        out.writeInt(PACKET_MAGIC)
        out.writeBytesWithLength(packet.payloadBytes)
        out.writeBytesWithLength(packet.signature)
        out.writeBytesWithLength(packet.originPublicKey)
    }

    fun decodePacket(bytes: ByteArray): SignedPacket = input(bytes) { source ->
        require(source.readInt() == PACKET_MAGIC) { "Invalid packet magic" }
        SignedPacket(
            source.readBytesWithLength(MAX_FIELD_BYTES),
            source.readBytesWithLength(1_024),
            source.readBytesWithLength(4_096),
        ).also { require(source.available() == 0) { "Trailing packet bytes" } }
    }

    fun encodeEnvelope(envelope: RelayEnvelope): ByteArray = output { out ->
        envelope.boundsViolation()?.let { throw IllegalArgumentException(it) }
        out.writeInt(ENVELOPE_MAGIC)
        out.writeBytesWithLength(encodePacket(envelope.packet))
        out.writeInt(envelope.ttlRemaining)
        out.writeInt(envelope.hopCount)
        out.writeInt(envelope.hopTrace.size)
        envelope.hopTrace.forEach { out.writeBoundedString(it, MAX_ID_BYTES) }
    }

    fun decodeEnvelope(bytes: ByteArray): RelayEnvelope = input(bytes) { source ->
        require(source.readInt() == ENVELOPE_MAGIC) { "Invalid envelope magic" }
        val packet = decodePacket(source.readBytesWithLength(MAX_FIELD_BYTES))
        val ttl = source.readInt()
        val hops = source.readInt()
        val count = source.readInt()
        require(count in 0..MAX_HOP_TRACE) { "Invalid hop trace" }
        RelayEnvelope(packet, ttl, hops, List(count) { source.readBoundedString(MAX_ID_BYTES) }).also { envelope ->
            envelope.boundsViolation()?.let { throw IllegalArgumentException(it) }
            require(source.available() == 0) { "Trailing envelope bytes" }
        }
    }

    fun encodeFrame(frame: MeshFrame): ByteArray = output { out ->
        out.writeInt(FRAME_MAGIC)
        when (frame) {
            is MeshFrame.Hello -> {
                out.writeByte(1)
                out.writeBoundedString(frame.profile.nodeId, MAX_ID_BYTES)
                out.writeBoundedString(frame.profile.displayName, MAX_NAME_BYTES)
                out.writeBytesWithLength(frame.profile.publicKey)
                out.writeBoundedString(frame.profile.keyFingerprint, MAX_ID_BYTES)
                out.writeInt(frame.profile.transportVersion)
            }
            is MeshFrame.Inventory -> {
                out.writeByte(2); out.writeInt(frame.page); out.writeBoolean(frame.lastPage)
                out.writeStringList(frame.messageIds)
            }
            is MeshFrame.Request -> { out.writeByte(3); out.writeStringList(frame.messageIds) }
            is MeshFrame.Packet -> { out.writeByte(4); out.writeBytesWithLength(encodeEnvelope(frame.envelope)) }
            is MeshFrame.Ack -> { out.writeByte(5); out.writeBoundedString(frame.messageId, MAX_ID_BYTES) }
        }
    }

    fun decodeFrame(bytes: ByteArray): MeshFrame = input(bytes) { source ->
        require(source.readInt() == FRAME_MAGIC) { "Invalid frame magic" }
        val frame = when (source.readUnsignedByte()) {
            1 -> MeshFrame.Hello(NodeProfile(
                source.readBoundedString(MAX_ID_BYTES),
                source.readBoundedString(MAX_NAME_BYTES),
                source.readBytesWithLength(4_096),
                source.readBoundedString(MAX_ID_BYTES),
                source.readInt(),
            ))
            2 -> MeshFrame.Inventory(source.readInt(), source.readBoolean(), source.readStringList())
            3 -> MeshFrame.Request(source.readStringList())
            4 -> MeshFrame.Packet(decodeEnvelope(source.readBytesWithLength(MAX_FIELD_BYTES)))
            5 -> MeshFrame.Ack(source.readBoundedString(MAX_ID_BYTES))
            else -> throw IllegalArgumentException("Unsupported frame type")
        }
        require(source.available() == 0) { "Trailing frame bytes" }
        frame
    }

    private fun DataOutputStream.writeAudience(audience: Audience) = when (audience) {
        Audience.PublicChannel -> writeByte(1)
        is Audience.DirectNode -> { writeByte(2); writeBoundedString(audience.nodeId, MAX_ID_BYTES) }
        is Audience.Circle -> { writeByte(3); writeBoundedString(audience.circleId, MAX_ID_BYTES) }
    }

    private fun validAudience(kind: PacketKind, audience: Audience): Boolean = when (kind) {
        PacketKind.PUBLIC_TEXT -> audience is Audience.PublicChannel
        PacketKind.CONTACT_REQUEST,
        PacketKind.CONTACT_ACCEPT,
        PacketKind.CONTACT_DECLINE,
        PacketKind.DIRECT_TEXT,
        PacketKind.DELIVERY_RECEIPT,
        PacketKind.CIRCLE_INVITE,
        PacketKind.CIRCLE_INVITE_ACCEPT,
        PacketKind.CIRCLE_INVITE_DECLINE,
        PacketKind.CIRCLE_LEAVE_REQUEST,
        -> audience is Audience.DirectNode
        PacketKind.CIRCLE_MEMBERSHIP_SNAPSHOT,
        PacketKind.CIRCLE_TEXT,
        PacketKind.CIRCLE_STATUS,
        -> audience is Audience.Circle
    }

    private fun validAudienceBody(audience: Audience, body: PacketBody): Boolean = when (body) {
        is CircleMembershipSnapshotBody -> audience is Audience.Circle && audience.circleId == body.circleId
        is CircleTextBody -> audience is Audience.Circle && audience.circleId == body.circleId
        is CircleStatusBody -> audience is Audience.Circle && audience.circleId == body.circleId
        else -> true
    }

    private fun DataInputStream.readAudience(): Audience = when (readUnsignedByte()) {
        1 -> Audience.PublicChannel
        2 -> Audience.DirectNode(readBoundedString(MAX_ID_BYTES))
        3 -> Audience.Circle(readBoundedString(MAX_ID_BYTES))
        else -> throw IllegalArgumentException("Unsupported audience")
    }

    private fun DataOutputStream.writeBody(body: PacketBody) = when (body) {
        is PublicTextBody -> writeBoundedString(body.text, MAX_TEXT_BYTES)
        is ContactRequestBody -> { writeBoundedString(body.requestId, MAX_ID_BYTES); writeBoundedString(body.requesterName, MAX_NAME_BYTES) }
        is ContactAcceptBody -> writeBoundedString(body.requestId, MAX_ID_BYTES)
        is ContactDeclineBody -> writeBoundedString(body.requestId, MAX_ID_BYTES)
        is DirectTextBody -> { writeBoundedString(body.conversationId, MAX_ID_BYTES); writeBoundedString(body.text, MAX_TEXT_BYTES) }
        is DeliveryReceiptBody -> {
            writeBoundedString(body.messageId, MAX_ID_BYTES)
            body.circleId?.let {
                writeBoolean(true)
                writeBoundedString(it, MAX_ID_BYTES)
                writeLong(requireNotNull(body.membershipVersion))
            }
        }
        is CircleInviteBody -> {
            writeBoundedString(body.inviteId, MAX_ID_BYTES); writeBoundedString(body.circleId, MAX_ID_BYTES)
            writeBoundedString(body.circleName, MAX_NAME_BYTES); writeBoundedString(body.ownerNodeId, MAX_ID_BYTES)
            writeLong(body.membershipVersion); writeLong(body.expiresAt)
            require(body.activeMemberPreview.size <= MAX_CIRCLE_MEMBERS) { "Too many active Circle members" }
            writeInt(body.activeMemberPreview.size)
            body.activeMemberPreview.forEach {
                writeBoundedString(it.nodeId, MAX_ID_BYTES); writeByte(it.role.wireId)
            }
        }
        is CircleInviteAcceptBody -> { writeBoundedString(body.inviteId, MAX_ID_BYTES); writeBoundedString(body.circleId, MAX_ID_BYTES) }
        is CircleInviteDeclineBody -> { writeBoundedString(body.inviteId, MAX_ID_BYTES); writeBoundedString(body.circleId, MAX_ID_BYTES) }
        is CircleMembershipSnapshotBody -> {
            writeBoundedString(body.circleId, MAX_ID_BYTES); writeLong(body.membershipVersion)
            writeBoundedString(body.circleName, MAX_NAME_BYTES); writeBoundedString(body.ownerNodeId, MAX_ID_BYTES)
            require(body.members.size <= MAX_CIRCLE_MEMBERS) { "Too many active Circle members" }
            writeInt(body.members.size)
            body.members.forEach {
                writeBoundedString(it.nodeId, MAX_ID_BYTES); writeByte(it.role.wireId)
            }
            writeBoolean(body.dissolved)
        }
        is CircleTextBody -> { writeBoundedString(body.circleId, MAX_ID_BYTES); writeLong(body.membershipVersion); writeBoundedString(body.text, MAX_TEXT_BYTES) }
        is CircleStatusBody -> {
            writeBoundedString(body.circleId, MAX_ID_BYTES); writeLong(body.membershipVersion)
            writeBoundedString(body.subjectNodeId, MAX_ID_BYTES); writeByte(body.status.wireId)
            writeBoolean(body.note != null); body.note?.let { writeBoundedString(it, MAX_NOTE_BYTES) }
        }
        is CircleLeaveRequestBody -> writeBoundedString(body.circleId, MAX_ID_BYTES)
    }

    private fun DataInputStream.readBody(kind: PacketKind): PacketBody = when (kind) {
        PacketKind.PUBLIC_TEXT -> PublicTextBody(readBoundedString(MAX_TEXT_BYTES))
        PacketKind.CONTACT_REQUEST -> ContactRequestBody(readBoundedString(MAX_ID_BYTES), readBoundedString(MAX_NAME_BYTES))
        PacketKind.CONTACT_ACCEPT -> ContactAcceptBody(readBoundedString(MAX_ID_BYTES))
        PacketKind.CONTACT_DECLINE -> ContactDeclineBody(readBoundedString(MAX_ID_BYTES))
        PacketKind.DIRECT_TEXT -> DirectTextBody(readBoundedString(MAX_ID_BYTES), readBoundedString(MAX_TEXT_BYTES))
        PacketKind.DELIVERY_RECEIPT -> {
            val messageId = readBoundedString(MAX_ID_BYTES)
            if (available() == 0) DeliveryReceiptBody(messageId)
            else {
                require(readBoolean()) { "Invalid Circle receipt metadata" }
                DeliveryReceiptBody(messageId, readBoundedString(MAX_ID_BYTES), readLong())
            }
        }
        PacketKind.CIRCLE_INVITE -> CircleInviteBody(
            readBoundedString(MAX_ID_BYTES), readBoundedString(MAX_ID_BYTES), readBoundedString(MAX_NAME_BYTES),
            readBoundedString(MAX_ID_BYTES), readLong(), readLong(),
            List(readMemberCount()) {
                CircleSnapshotMember(readBoundedString(MAX_ID_BYTES), CircleMemberRole.fromWireId(readUnsignedByte()))
            },
        )
        PacketKind.CIRCLE_INVITE_ACCEPT -> CircleInviteAcceptBody(readBoundedString(MAX_ID_BYTES), readBoundedString(MAX_ID_BYTES))
        PacketKind.CIRCLE_INVITE_DECLINE -> CircleInviteDeclineBody(readBoundedString(MAX_ID_BYTES), readBoundedString(MAX_ID_BYTES))
        PacketKind.CIRCLE_MEMBERSHIP_SNAPSHOT -> {
            val circleId = readBoundedString(MAX_ID_BYTES); val version = readLong()
            val name = readBoundedString(MAX_NAME_BYTES); val owner = readBoundedString(MAX_ID_BYTES)
            val count = readInt(); require(count in 0..MAX_CIRCLE_MEMBERS) { "Too many active Circle members" }
            CircleMembershipSnapshotBody(
                circleId, version, name, owner,
                List(count) {
                    CircleSnapshotMember(readBoundedString(MAX_ID_BYTES), CircleMemberRole.fromWireId(readUnsignedByte()))
                },
                readBoolean(),
            )
        }
        PacketKind.CIRCLE_TEXT -> CircleTextBody(readBoundedString(MAX_ID_BYTES), readLong(), readBoundedString(MAX_TEXT_BYTES))
        PacketKind.CIRCLE_STATUS -> CircleStatusBody(
            readBoundedString(MAX_ID_BYTES), readLong(), readBoundedString(MAX_ID_BYTES),
            SafetyStatus.fromWireId(readUnsignedByte()),
            if (readBoolean()) readBoundedString(MAX_NOTE_BYTES) else null,
        )
        PacketKind.CIRCLE_LEAVE_REQUEST -> CircleLeaveRequestBody(readBoundedString(MAX_ID_BYTES))
    }

    private fun DataInputStream.readMemberCount(): Int = readInt().also {
        require(it in 0..MAX_CIRCLE_MEMBERS) { "Too many active Circle members" }
    }

    private fun output(block: (DataOutputStream) -> Unit): ByteArray =
        ByteArrayOutputStream().use { bytes -> DataOutputStream(bytes).use(block); bytes.toByteArray() }

    private fun <T> input(bytes: ByteArray, block: (DataInputStream) -> T): T = try {
        DataInputStream(ByteArrayInputStream(bytes)).use(block)
    } catch (error: IllegalArgumentException) {
        throw error
    } catch (error: Exception) {
        throw IllegalArgumentException("Malformed protocol bytes", error)
    }

    private fun DataOutputStream.writeBoundedString(value: String, maxBytes: Int) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= maxBytes) { "Field exceeds $maxBytes UTF-8 bytes" }
        writeBytesWithLength(bytes)
    }

    private fun DataInputStream.readBoundedString(maxBytes: Int): String {
        val bytes = readBytesWithLength(maxBytes)
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun DataOutputStream.writeBytesWithLength(value: ByteArray) {
        require(value.size <= MAX_FIELD_BYTES); writeInt(value.size); write(value)
    }
    private fun DataInputStream.readBytesWithLength(max: Int): ByteArray {
        val size = readInt(); require(size in 0..max); return ByteArray(size).also(::readFully)
    }
    private fun DataOutputStream.writeStringList(values: List<String>) {
        require(values.size <= MAX_IDS_PER_FRAME); writeInt(values.size); values.forEach { writeBoundedString(it, MAX_ID_BYTES) }
    }
    private fun DataInputStream.readStringList(): List<String> {
        val count = readInt(); require(count in 0..MAX_IDS_PER_FRAME); return List(count) { readBoundedString(MAX_ID_BYTES) }
    }
}
