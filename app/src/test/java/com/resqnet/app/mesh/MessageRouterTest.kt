package com.resqnet.app.mesh

import com.resqnet.app.data.*
import com.resqnet.app.protocol.*
import com.resqnet.app.security.IdentitySigner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID

class MessageRouterTest {
    @Test fun validDirectPacketPersistsOnNonRecipientRelayWithoutVisibleProjection() = runTest {
        val now = 1_000L
        val senderSigner = JvmSigner()
        val relaySigner = JvmSigner()
        val packetStore = MemoryPackets()
        val conversations = MemoryConversations()
        val relay = MessageRouter(packetStore, conversations, MemoryPeers(), relaySigner, { "Relay" }, { now })
        val payload = PayloadV2(
            UUID.randomUUID(), PacketKind.DIRECT_TEXT, Audience.DirectNode("different-recipient"),
            senderSigner.nodeId, "Alice", 1, now, now + PROPAGATION_WINDOW_MS,
            RelayPolicy.EPHEMERAL, DirectTextBody("conversation-1", "Are you safe?"),
        )
        val payloadBytes = ProtocolCodec.encodePayload(payload)
        val envelope = RelayEnvelope(
            SignedPacket(payloadBytes, senderSigner.sign(payloadBytes), senderSigner.publicKey),
            hopTrace = listOf(senderSigner.nodeId),
        )

        val result = relay.ingest(envelope, senderSigner.nodeId)

        assertTrue(result is IngestResult.StoredOnly)
        assertTrue(result.hopAckEligible)
        assertEquals(1, packetStore.values.size)
        assertEquals(PacketKind.DIRECT_TEXT, packetStore.values.values.single().kind)
        assertEquals(0, conversations.values.size)
    }

    @Test fun relayingAcrossTwoRoutersChangesOnlyEnvelopeMetadata() = runTest {
        val now = 1_000L
        val aPackets = MemoryPackets(); val bPackets = MemoryPackets(); val cPackets = MemoryPackets()
        val a = MessageRouter(aPackets, MemoryConversations(), MemoryPeers(), JvmSigner(), { "Alice" }, { now })
        val b = MessageRouter(bPackets, MemoryConversations(), MemoryPeers(), JvmSigner(), { "Bob" }, { now })
        val c = MessageRouter(cPackets, MemoryConversations(), MemoryPeers(), JvmSigner(), { "Carol" }, { now })

        val created = a.createMessage("Bridge is open")
        val original = ProtocolCodec.decodeEnvelope(aPackets.values.getValue(created.messageId).rawEnvelope)
        assertTrue(b.ingest(original, original.packet.originPublicKey.contentHashCode().toString()) is IngestResult.Projected)
        val afterB = b.requestedPackets(listOf(created.messageId)).single()
        assertTrue(c.ingest(afterB, "relay-b") is IngestResult.Projected)
        val afterC = ProtocolCodec.decodeEnvelope(cPackets.values.getValue(created.messageId).rawEnvelope)

        listOf(afterB, afterC).forEach { relayed ->
            assertArrayEquals(original.packet.payloadBytes, relayed.packet.payloadBytes)
            assertArrayEquals(original.packet.signature, relayed.packet.signature)
            assertArrayEquals(original.packet.originPublicKey, relayed.packet.originPublicKey)
        }
        assertEquals(DEFAULT_TTL - 1, afterB.ttlRemaining)
        assertEquals(1, afterB.hopCount)
        assertEquals(DEFAULT_TTL - 2, afterC.ttlRemaining)
        assertEquals(2, afterC.hopCount)
        assertTrue(afterC.hopTrace.size > original.hopTrace.size)
    }

    @Test fun publicTextProjectsThenDeduplicatesWhileTamperingIsRejected() = runTest {
        val now = 1_000L
        val senderPackets = MemoryPackets(); val receiverPackets = MemoryPackets()
        val sender = MessageRouter(senderPackets, MemoryConversations(), MemoryPeers(), JvmSigner(), { "Alice" }, { now })
        val receiverConversations = MemoryConversations()
        val receiver = MessageRouter(receiverPackets, receiverConversations, MemoryPeers(), JvmSigner(), { "Bob" }, { now })
        val created = sender.createMessage("Public warning")
        val envelope = ProtocolCodec.decodeEnvelope(senderPackets.values.getValue(created.messageId).rawEnvelope)

        assertTrue(receiver.ingest(envelope, "sender") is IngestResult.Projected)
        assertEquals("Public warning", receiverConversations.values.values.single().text)
        assertTrue(receiver.ingest(envelope, "sender") is IngestResult.Duplicate)

        val freshReceiver = MessageRouter(MemoryPackets(), MemoryConversations(), MemoryPeers(), JvmSigner(), { "Carol" }, { now })
        val tamperedSignature = envelope.packet.signature.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val tampered = envelope.copy(packet = envelope.packet.copy(signature = tamperedSignature))
        assertTrue(freshReceiver.ingest(tampered, "sender") is IngestResult.Rejected)
    }

    @Test fun expiredPacketsAndV1HelloAreRejected() = runTest {
        var now = 1_000L
        val senderPackets = MemoryPackets()
        val senderSigner = JvmSigner()
        val sender = MessageRouter(senderPackets, MemoryConversations(), MemoryPeers(), senderSigner, { "Alice" }, { now })
        val receiver = MessageRouter(MemoryPackets(), MemoryConversations(), MemoryPeers(), JvmSigner(), { "Bob" }, { now })
        val created = sender.createMessage("Time limited")
        val envelope = ProtocolCodec.decodeEnvelope(senderPackets.values.getValue(created.messageId).rawEnvelope)
        now += PROPAGATION_WINDOW_MS + 1

        assertTrue(receiver.ingest(envelope, "sender") is IngestResult.Rejected)
        assertTrue(!receiver.onHello(NodeProfile(
            senderSigner.nodeId, "Alice", senderSigner.publicKey, senderSigner.fingerprint, transportVersion = 1,
        )))
    }

    @Test fun impossibleTimingIsRejectedBeforeRawPersistence() = runTest {
        val now = 1_000L
        val senderSigner = JvmSigner()
        val receiverPackets = MemoryPackets()
        val receiver = MessageRouter(receiverPackets, MemoryConversations(), MemoryPeers(), JvmSigner(), { "Relay" }, { now })
        val payload = PayloadV2(
            UUID.randomUUID(), PacketKind.PUBLIC_TEXT, Audience.PublicChannel,
            senderSigner.nodeId, "Alice", 1, createdAt = 2_000L, expiresAt = 1_500L,
            RelayPolicy.EPHEMERAL, PublicTextBody("Impossible timeline"),
        )
        val bytes = ProtocolCodec.encodePayload(payload)
        val envelope = RelayEnvelope(SignedPacket(bytes, senderSigner.sign(bytes), senderSigner.publicKey))

        assertTrue(receiver.ingest(envelope, "sender") is IngestResult.Rejected)
        assertEquals(0, receiverPackets.values.size)
    }

    @Test fun ephemeralPacketLongerThanTwentyFourHoursIsRejectedBeforePersistence() = runTest {
        val now = 1_000L
        val senderSigner = JvmSigner()
        val receiverPackets = MemoryPackets()
        val receiver = MessageRouter(receiverPackets, MemoryConversations(), MemoryPeers(), JvmSigner(), { "Relay" }, { now })
        val payload = PayloadV2(
            UUID.randomUUID(), PacketKind.PUBLIC_TEXT, Audience.PublicChannel,
            senderSigner.nodeId, "Alice", 1, createdAt = now,
            expiresAt = now + PROPAGATION_WINDOW_MS + 1,
            RelayPolicy.EPHEMERAL, PublicTextBody("Unbounded relay"),
        )
        val bytes = ProtocolCodec.encodePayload(payload)
        val envelope = RelayEnvelope(SignedPacket(bytes, senderSigner.sign(bytes), senderSigner.publicKey))

        assertTrue(receiver.ingest(envelope, "sender") is IngestResult.Rejected)
        assertEquals(0, receiverPackets.values.size)
    }

    @Test fun samePacketIdWithDifferentSignedBytesIsRejectedWithoutAck() = runTest {
        val now = 1_000L
        val senderSigner = JvmSigner()
        val packetId = UUID.randomUUID()
        val packets = MemoryPackets()
        val conversations = MemoryConversations()
        val receiver = MessageRouter(packets, conversations, MemoryPeers(), JvmSigner(), { "Relay" }, { now })
        val original = signedEnvelope(
            PayloadV2(
                packetId, PacketKind.PUBLIC_TEXT, Audience.PublicChannel, senderSigner.nodeId, "Alice", 1,
                now, now + PROPAGATION_WINDOW_MS, RelayPolicy.EPHEMERAL, PublicTextBody("Original"),
            ),
            senderSigner,
        )
        assertTrue(receiver.ingest(original, "sender") is IngestResult.Projected)
        val storedBefore = packets.values.getValue(packetId.toString()).rawEnvelope.clone()
        val collision = signedEnvelope(
            PayloadV2(
                packetId, PacketKind.PUBLIC_TEXT, Audience.PublicChannel, senderSigner.nodeId, "Alice", 2,
                now, now + PROPAGATION_WINDOW_MS, RelayPolicy.EPHEMERAL, PublicTextBody("Collision"),
            ),
            senderSigner,
        )

        val result = receiver.ingest(collision, "sender")

        assertTrue(result is IngestResult.Rejected)
        assertTrue(!result.hopAckEligible)
        assertArrayEquals(storedBefore, packets.values.getValue(packetId.toString()).rawEnvelope)
        assertEquals(listOf("Original"), conversations.values.values.map { it.text })
    }

    @Test fun failedVisibleInsertRemainsStoredOnlyAndRetriesOnRedelivery() = runTest {
        val now = 1_000L
        val senderSigner = JvmSigner()
        val packets = MemoryPackets()
        val conversations = MemoryConversations().apply { failNextInsert = true }
        val receiver = MessageRouter(packets, conversations, MemoryPeers(), JvmSigner(), { "Relay" }, { now })
        val payload = PayloadV2(
            UUID.randomUUID(), PacketKind.PUBLIC_TEXT, Audience.PublicChannel,
            senderSigner.nodeId, "Alice", 1, now, now + PROPAGATION_WINDOW_MS,
            RelayPolicy.EPHEMERAL, PublicTextBody("Retry projection"),
        )
        val envelope = signedEnvelope(payload, senderSigner)

        val first = receiver.ingest(envelope, "sender")
        assertTrue(first is IngestResult.StoredOnly)
        assertEquals(ProjectionState.STORED_ONLY, packets.values.getValue(payload.packetId.toString()).projectionState)
        assertEquals(0, conversations.values.size)

        val retry = receiver.ingest(envelope, "sender")
        assertTrue(retry is IngestResult.Projected)
        assertEquals(ProjectionState.PROJECTED, packets.values.getValue(payload.packetId.toString()).projectionState)
        assertEquals(listOf("Retry projection"), conversations.values.values.map { it.text })
    }

    @Test fun cancellationAfterVisibleInsertRetriesDespiteMutableProjectionMetadata() = runTest {
        val now = 1_000L
        val senderSigner = JvmSigner()
        val packets = MemoryPackets().apply { cancelNextProjectionPromotion = true }
        val conversations = MemoryConversations()
        val receiver = MessageRouter(packets, conversations, MemoryPeers(), JvmSigner(), { "Relay" }, { now })
        val payload = PayloadV2(
            UUID.randomUUID(), PacketKind.PUBLIC_TEXT, Audience.PublicChannel,
            senderSigner.nodeId, "Alice", 1, now, now + PROPAGATION_WINDOW_MS,
            RelayPolicy.EPHEMERAL, PublicTextBody("Crash window"),
        )
        val envelope = signedEnvelope(payload, senderSigner)

        val failure = runCatching { receiver.ingest(envelope, "sender") }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(ProjectionState.STORED_ONLY, packets.values.getValue(payload.packetId.toString()).projectionState)
        val visible = conversations.values.getValue(payload.packetId.toString())
        conversations.values[payload.packetId.toString()] = visible.copy(originName = "Updated peer", relayed = true)

        val retry = receiver.ingest(envelope, "sender")
        assertTrue(retry is IngestResult.Projected)
        assertEquals(ProjectionState.PROJECTED, packets.values.getValue(payload.packetId.toString()).projectionState)
        assertEquals(1, conversations.values.size)
    }

    @Test fun cancelledLocalProjectionRecoversAsOutgoingWhenPacketReturns() = runTest {
        val now = 1_000L
        val localSigner = JvmSigner()
        val packets = MemoryPackets().apply { cancelNextProjectionPromotion = true }
        val conversations = MemoryConversations()
        val router = MessageRouter(packets, conversations, MemoryPeers(), localSigner, { "Alice" }, { now })

        val failure = runCatching { router.createMessage("Local crash window") }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        val raw = packets.values.values.single()
        assertEquals(ProjectionState.STORED_ONLY, raw.projectionState)
        assertTrue(conversations.values.values.single().outgoing)

        val returned = ProtocolCodec.decodeEnvelope(raw.rawEnvelope)
        val recovery = router.ingest(returned, "relay")

        assertTrue(recovery is IngestResult.Projected)
        assertEquals(ProjectionState.PROJECTED, packets.values.getValue(raw.packetId).projectionState)
        assertEquals(1, conversations.values.size)
        assertTrue(conversations.values.values.single().outgoing)
    }

    private fun signedEnvelope(payload: PayloadV2, signer: JvmSigner): RelayEnvelope {
        val bytes = ProtocolCodec.encodePayload(payload)
        return RelayEnvelope(SignedPacket(bytes, signer.sign(bytes), signer.publicKey))
    }

    private class MemoryPackets : PacketRepository {
        val values = linkedMapOf<String, PacketEntity>()
        var cancelNextProjectionPromotion = false
        private var sequence = 0L
        override suspend fun insert(packet: PacketEntity): Boolean = values.putIfAbsent(packet.packetId, packet) == null
        override suspend fun find(packetId: String) = values[packetId]
        override suspend fun inventoryIds() = values.values.filter { it.relayEligible }.map { it.packetId }
        override suspend fun findAll(packetIds: List<String>) = packetIds.mapNotNull(values::get)
        override suspend fun nextSequence() = ++sequence
        override suspend fun markProjected(packetId: String): Boolean {
            if (cancelNextProjectionPromotion) {
                cancelNextProjectionPromotion = false
                throw CancellationException("projection promotion cancelled")
            }
            val packet = values[packetId] ?: return false
            values[packetId] = packet.copy(projectionState = ProjectionState.PROJECTED)
            return true
        }
        override suspend fun markRelayed(packetId: String, peerId: String) = Unit
        override suspend fun cleanup() = Unit
    }

    private class MemoryConversations : ConversationRepository {
        val values = linkedMapOf<String, ConversationMessageEntity>()
        var failNextInsert = false
        private val flow = MutableStateFlow<List<ConversationMessageEntity>>(emptyList())
        override fun observeMessages(): Flow<List<ConversationMessageEntity>> = flow
        override suspend fun insert(message: ConversationMessageEntity): Boolean {
            if (failNextInsert) { failNextInsert = false; return false }
            if (values.putIfAbsent(message.messageId, message) != null) return false
            flow.value = values.values.toList()
            return true
        }
        override suspend fun find(messageId: String) = values[messageId]
        override suspend fun markRelayed(messageId: String) = Unit
        override suspend fun cleanup() = Unit
    }

    private class MemoryPeers : PeerRepository {
        private val peers = mutableMapOf<String, PeerEntity>()
        override suspend fun upsert(profile: NodeProfile) {
            peers[profile.nodeId] = PeerEntity(
                profile.nodeId, profile.displayName, profile.publicKey, profile.keyFingerprint,
                profile.transportVersion, 0,
            )
        }
        override suspend fun find(nodeId: String) = peers[nodeId]
        override fun observePeers(): Flow<List<PeerEntity>> = kotlinx.coroutines.flow.flowOf(peers.values.toList())
    }

    private class JvmSigner : IdentitySigner {
        private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        override val publicKey = pair.public.encoded
        override val nodeId = MessageDigest.getInstance("SHA-256").digest(publicKey)
            .joinToString("") { "%02x".format(it) }.take(32)
        override val fingerprint = nodeId.take(16)
        override fun sign(payload: ByteArray) = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private); update(payload); sign()
        }
        override fun verify(payload: ByteArray, signature: ByteArray, publicKey: ByteArray) = runCatching {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey))
            Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(payload); verify(signature) }
        }.getOrDefault(false)
    }
}
