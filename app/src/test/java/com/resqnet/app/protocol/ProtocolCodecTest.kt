package com.resqnet.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ProtocolCodecTest {
    @Test fun typedPayloadsRoundTripAcrossAudienceAndBodyFamilies() {
        val cases = listOf(
            payload(PacketKind.PUBLIC_TEXT, Audience.PublicChannel, PublicTextBody("Flood route is clear ✅")),
            payload(PacketKind.DIRECT_TEXT, Audience.DirectNode("node-b"), DirectTextBody("dm-1", "Need batteries")),
            payload(PacketKind.CONTACT_REQUEST, Audience.DirectNode("node-b"), ContactRequestBody("request-1", "Alice")),
            payload(
                PacketKind.CIRCLE_MEMBERSHIP_SNAPSHOT,
                Audience.Circle("circle-1"),
                CircleMembershipSnapshotBody("circle-1", 3, "Family", "node-a", listOf("node-a", "node-b")),
            ),
            payload(
                PacketKind.CIRCLE_STATUS,
                Audience.Circle("circle-1"),
                CircleStatusBody("circle-1", 3, SafetyStatus.NEED_HELP, "Need insulin"),
            ),
        )

        cases.forEach { original ->
            val decoded = ProtocolCodec.decodePayload(ProtocolCodec.encodePayload(original))
            assertEquals(original, decoded)
            assertEquals(original.kind, decoded.body.kind)
        }
    }

    @Test fun rejectsEnvelopeWhoseTtlAndHopCountExceedTheSixHopBudget() {
        val packet = SignedPacket(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
        val invalid = RelayEnvelope(packet, ttlRemaining = DEFAULT_TTL, hopCount = 1, hopTrace = listOf("node-a"))

        assertThrows(IllegalArgumentException::class.java) { ProtocolCodec.encodeEnvelope(invalid) }
    }

    @Test fun packetAndFrameRoundTripsPreserveSignedBytes() {
        val payloadBytes = ProtocolCodec.encodePayload(
            payload(PacketKind.PUBLIC_TEXT, Audience.PublicChannel, PublicTextBody("x".repeat(500))),
        )
        val packet = SignedPacket(payloadBytes, ByteArray(72) { 3 }, ByteArray(91) { 4 })
        val frame = MeshFrame.Packet(RelayEnvelope(packet, 4, 2, listOf("node-a", "node-r")))

        val decoded = ProtocolCodec.decodeFrame(ProtocolCodec.encodeFrame(frame)) as MeshFrame.Packet

        assertArrayEquals(packet.payloadBytes, decoded.envelope.packet.payloadBytes)
        assertArrayEquals(packet.signature, decoded.envelope.packet.signature)
        assertEquals(listOf("node-a", "node-r"), decoded.envelope.hopTrace)
    }

    @Test fun rejectsRqp1WrongVersionAndUnknownKindOrAudienceTags() {
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolCodec.decodePayload(byteArrayOf(0x52, 0x51, 0x50, 0x31))
        }
        val encoded = ProtocolCodec.encodePayload(
            payload(PacketKind.PUBLIC_TEXT, Audience.PublicChannel, PublicTextBody("hello")),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolCodec.decodePayload(encoded.clone().also { it[7] = 1 })
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolCodec.decodePayload(encoded.clone().also { it[24] = 99.toByte() })
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolCodec.decodePayload(encoded.clone().also { it[25] = 99.toByte() })
        }
    }

    @Test fun rejectsOversizedUtf8TextNamesNotesAndCircleMembership() {
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolCodec.encodePayload(payload(PacketKind.PUBLIC_TEXT, Audience.PublicChannel, PublicTextBody("é".repeat(251))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolCodec.encodePayload(
                payload(PacketKind.CONTACT_REQUEST, Audience.DirectNode("node-b"), ContactRequestBody("r", "é".repeat(33))),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolCodec.encodePayload(
                payload(PacketKind.CIRCLE_STATUS, Audience.Circle("c"), CircleStatusBody("c", 1, SafetyStatus.SAFE, "é".repeat(81))),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolCodec.encodePayload(
                payload(
                    PacketKind.CIRCLE_MEMBERSHIP_SNAPSHOT,
                    Audience.Circle("c"),
                    CircleMembershipSnapshotBody("c", 1, "Family", "owner", List(21) { "member-$it" }),
                ),
            )
        }
    }

    @Test fun rejectsMalformedTrailingAndInvalidEnvelopeBounds() {
        val payloadBytes = ProtocolCodec.encodePayload(
            payload(PacketKind.PUBLIC_TEXT, Audience.PublicChannel, PublicTextBody("hello")),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolCodec.decodePayload(payloadBytes.copyOf(payloadBytes.size - 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolCodec.decodePayload(payloadBytes + byteArrayOf(1))
        }
        val packet = SignedPacket(payloadBytes, byteArrayOf(1), byteArrayOf(2))
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolCodec.encodeEnvelope(RelayEnvelope(packet, ttlRemaining = -1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolCodec.encodeEnvelope(RelayEnvelope(packet, ttlRemaining = 0, hopCount = MAX_HOPS + 1))
        }
    }

    @Test fun rejectsPacketKindPairedWithWrongAudienceType() {
        val invalid = payload(
            PacketKind.PUBLIC_TEXT,
            Audience.DirectNode("node-b"),
            PublicTextBody("misaddressed public message"),
        )

        assertThrows(IllegalArgumentException::class.java) { ProtocolCodec.encodePayload(invalid) }
    }

    @Test fun rejectsCircleBodyAddressedToDifferentCircleAudience() {
        val invalid = payload(
            PacketKind.CIRCLE_TEXT,
            Audience.Circle("circle-a"),
            CircleTextBody("circle-b", 1, "Wrong circle"),
        )

        assertThrows(IllegalArgumentException::class.java) { ProtocolCodec.encodePayload(invalid) }
    }

    @Test fun everyPacketKindAcceptsOnlyItsDefinedRelayPolicy() {
        PacketKind.entries.forEach { kind ->
            ProtocolCodec.encodePayload(payloadForKind(kind, kind.requiredRelayPolicy))
            RelayPolicy.entries.filterNot { it == kind.requiredRelayPolicy }.forEach { wrongPolicy ->
                assertThrows("$kind accepted $wrongPolicy", IllegalArgumentException::class.java) {
                    ProtocolCodec.encodePayload(payloadForKind(kind, wrongPolicy))
                }
            }
        }
    }

    private fun payloadForKind(kind: PacketKind, relayPolicy: RelayPolicy): PayloadV2 {
        val (audience, body) = when (kind) {
            PacketKind.PUBLIC_TEXT -> Audience.PublicChannel to PublicTextBody("Public")
            PacketKind.CONTACT_REQUEST -> Audience.DirectNode("node-b") to ContactRequestBody("request", "Alice")
            PacketKind.CONTACT_ACCEPT -> Audience.DirectNode("node-b") to ContactAcceptBody("request")
            PacketKind.CONTACT_DECLINE -> Audience.DirectNode("node-b") to ContactDeclineBody("request")
            PacketKind.DIRECT_TEXT -> Audience.DirectNode("node-b") to DirectTextBody("conversation", "Direct")
            PacketKind.DELIVERY_RECEIPT -> Audience.DirectNode("node-b") to DeliveryReceiptBody("message")
            PacketKind.CIRCLE_INVITE -> Audience.DirectNode("node-b") to CircleInviteBody(
                "invite", "circle", "Family", "node-a", 1, 200,
            )
            PacketKind.CIRCLE_INVITE_ACCEPT -> Audience.DirectNode("node-b") to CircleInviteAcceptBody("invite", "circle")
            PacketKind.CIRCLE_INVITE_DECLINE -> Audience.DirectNode("node-b") to CircleInviteDeclineBody("invite", "circle")
            PacketKind.CIRCLE_MEMBERSHIP_SNAPSHOT -> Audience.Circle("circle") to CircleMembershipSnapshotBody(
                "circle", 1, "Family", "node-a", listOf("node-a"),
            )
            PacketKind.CIRCLE_TEXT -> Audience.Circle("circle") to CircleTextBody("circle", 1, "Circle")
            PacketKind.CIRCLE_STATUS -> Audience.Circle("circle") to CircleStatusBody("circle", 1, SafetyStatus.SAFE, null)
            PacketKind.CIRCLE_LEAVE_REQUEST -> Audience.DirectNode("node-b") to CircleLeaveRequestBody("circle")
        }
        return payload(kind, audience, body).copy(relayPolicy = relayPolicy)
    }

    private fun payload(kind: PacketKind, audience: Audience, body: PacketBody) = PayloadV2(
        packetId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        kind = kind,
        audience = audience,
        originNodeId = "node-a",
        originDisplayName = "Alice",
        originSequence = 9,
        createdAt = 100,
        expiresAt = 200,
        relayPolicy = when (kind) {
            PacketKind.CIRCLE_MEMBERSHIP_SNAPSHOT -> RelayPolicy.DURABLE_UNTIL_SUPERSEDED
            else -> RelayPolicy.EPHEMERAL
        },
        body = body,
    ).also { assertTrue(it.originSequence > 0) }
}
