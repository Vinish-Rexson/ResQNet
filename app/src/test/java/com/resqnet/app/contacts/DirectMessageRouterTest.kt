package com.resqnet.app.contacts

import com.resqnet.app.data.*
import com.resqnet.app.mesh.IngestResult
import com.resqnet.app.mesh.MessageRouter
import com.resqnet.app.protocol.*
import com.resqnet.app.security.IdentitySigner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID

class DirectMessageRouterTest {
    @Test fun recipientProjectsOnceAndReturnsOneSignedReceiptThroughAVisibilityBlindRelay() = runTest {
        val now = 10_000L
        val alice = TestNode("Alice", now)
        val relay = TestNode("Relay", now)
        val bob = TestNode("Bob", now)
        alice.trust(bob); bob.trust(alice)

        val queued = alice.directMessages.send(bob.signer.nodeId, "Are you safe?")
        assertEquals(DeliveryState.QUEUED, queued.deliveryState)
        val outbound = alice.envelope(queued.messageId)

        assertTrue(relay.router.ingest(outbound, alice.signer.nodeId) is IngestResult.StoredOnly)
        assertEquals(0, relay.conversations.values.size)
        val relayed = relay.router.requestedPackets(listOf(queued.messageId)).single()
        assertTrue(bob.router.ingest(relayed, relay.signer.nodeId) is IngestResult.Projected)
        assertEquals(listOf("Are you safe?"), bob.conversations.values.values.map { it.text })
        assertEquals(
            listOf("Are you safe?"),
            bob.conversations.observeConversation(queued.conversationId).first().map { it.text },
        )
        assertEquals(1, bob.packetStore.values.values.count { it.kind == PacketKind.DELIVERY_RECEIPT })

        assertTrue(bob.router.ingest(relayed, relay.signer.nodeId) is IngestResult.Duplicate)
        assertEquals(1, bob.conversations.values.size)
        assertEquals(1, bob.packetStore.values.values.count { it.kind == PacketKind.DELIVERY_RECEIPT })

        alice.router.acknowledged(queued.messageId, relay.signer.nodeId)
        assertEquals(DeliveryState.RELAYED, alice.conversations.values.getValue(queued.messageId).deliveryState)
        val receipt = bob.only(PacketKind.DELIVERY_RECEIPT)
        val receiptPayload = ProtocolCodec.decodePayload(receipt.packet.payloadBytes)
        assertTrue(bob.signer.verify(receipt.packet.payloadBytes, receipt.packet.signature, receipt.packet.originPublicKey))
        assertEquals(queued.messageId, (receiptPayload.body as DeliveryReceiptBody).messageId)
        assertEquals(alice.signer.nodeId, (receiptPayload.audience as Audience.DirectNode).nodeId)

        assertTrue(alice.router.ingest(receipt, bob.signer.nodeId) is IngestResult.Projected)
        assertEquals(DeliveryState.DELIVERED, alice.conversations.values.getValue(queued.messageId).deliveryState)
        assertEquals(0, alice.packetStore.values.values.count {
            it.kind == PacketKind.DELIVERY_RECEIPT && it.originNodeId == alice.signer.nodeId
        })
    }

    @Test fun forgedWrongTargetAndDuplicateReceiptsCannotFalselyDeliverOrLoop() = runTest {
        val now = 20_000L
        val alice = TestNode("Alice", now)
        val bob = TestNode("Bob", now)
        val mallory = TestNode("Mallory", now)
        alice.trust(bob)
        val direct = alice.router.createDirectMessage(bob.signer.nodeId, "Check in")

        val forged = signedEnvelope(receiptPayload(mallory, alice.signer.nodeId, direct.messageId, now), mallory.signer)
        assertTrue(alice.router.ingest(forged, mallory.signer.nodeId) is IngestResult.StoredOnly)
        assertEquals(DeliveryState.QUEUED, alice.conversations.values.getValue(direct.messageId).deliveryState)

        val wrongTarget = signedEnvelope(receiptPayload(bob, "some-other-node", direct.messageId, now), bob.signer)
        assertTrue(alice.router.ingest(wrongTarget, bob.signer.nodeId) is IngestResult.StoredOnly)
        assertEquals(DeliveryState.QUEUED, alice.conversations.values.getValue(direct.messageId).deliveryState)

        val valid = signedEnvelope(receiptPayload(bob, alice.signer.nodeId, direct.messageId, now), bob.signer)
        assertTrue(alice.router.ingest(valid, bob.signer.nodeId) is IngestResult.Projected)
        assertTrue(alice.router.ingest(valid, bob.signer.nodeId) is IngestResult.Duplicate)
        val secondValid = signedEnvelope(receiptPayload(bob, alice.signer.nodeId, direct.messageId, now), bob.signer)
        assertTrue(alice.router.ingest(secondValid, bob.signer.nodeId) is IngestResult.Projected)
        assertEquals(DeliveryState.DELIVERED, alice.conversations.values.getValue(direct.messageId).deliveryState)
        assertEquals(1, alice.receipts.values.size)
        assertEquals(0, alice.packetStore.values.values.count {
            it.kind == PacketKind.DELIVERY_RECEIPT && it.originNodeId == alice.signer.nodeId
        })
    }

    @Test fun untrustedRemovedAndBlockedDirectPacketsStayRawWithoutReceiptWhileBlockedPublicTextProjects() = runTest {
        val now = 30_000L
        val alice = TestNode("Alice", now)
        val untrusted = TestNode("Untrusted", now)
        val removed = TestNode("Removed", now)
        val blocked = TestNode("Blocked", now)

        alice.trust(untrusted)
        val untrustedMessage = alice.router.createDirectMessage(untrusted.signer.nodeId, "Untrusted")

        alice.trust(removed); removed.trust(alice)
        val removedMessage = alice.router.createDirectMessage(removed.signer.nodeId, "Removed")
        removed.router.removeContact(alice.signer.nodeId)

        alice.trust(blocked); blocked.trust(alice)
        val blockedMessage = alice.router.createDirectMessage(blocked.signer.nodeId, "Blocked")
        blocked.router.blockContact(alice.signer.nodeId)

        listOf(
            untrusted to untrustedMessage,
            removed to removedMessage,
            blocked to blockedMessage,
        ).forEach { (recipient, message) ->
            assertTrue(recipient.router.ingest(alice.envelope(message.messageId), alice.signer.nodeId) is IngestResult.StoredOnly)
            assertEquals(1, recipient.packetStore.values.values.count { it.packetId == message.messageId })
            assertEquals(0, recipient.conversations.values.size)
            assertEquals(0, recipient.packetStore.values.values.count { it.kind == PacketKind.DELIVERY_RECEIPT })
        }

        untrusted.trust(alice)
        assertTrue(
            untrusted.router.ingest(alice.envelope(untrustedMessage.messageId), alice.signer.nodeId) is IngestResult.Duplicate,
        )
        assertEquals(0, untrusted.conversations.values.size)

        val public = alice.router.createMessage("Public warning")
        assertTrue(blocked.router.ingest(alice.envelope(public.messageId), alice.signer.nodeId) is IngestResult.Projected)
        assertEquals(listOf("Public warning"), blocked.conversations.values.values.map { it.text })

        alice.router.blockContact(untrusted.signer.nodeId)
        val blockedSend = runCatching {
            alice.router.createDirectMessage(untrusted.signer.nodeId, "Must fail")
        }.exceptionOrNull()
        assertTrue(blockedSend is IllegalArgumentException)
        val tooLong = runCatching {
            alice.router.unblockContact(untrusted.signer.nodeId)
            alice.router.createDirectMessage(untrusted.signer.nodeId, "x".repeat(MAX_TEXT_BYTES + 1))
        }.exceptionOrNull()
        assertTrue(tooLong is IllegalArgumentException)
    }

    @Test fun duplicateDirectRecoversReceiptCreationInterruptedAfterProjection() = runTest {
        val now = 40_000L
        val alice = TestNode("Alice", now)
        val bob = TestNode("Bob", now)
        alice.trust(bob); bob.trust(alice)
        val direct = alice.router.createDirectMessage(bob.signer.nodeId, "Recovery")
        val envelope = alice.envelope(direct.messageId)
        bob.receipts.cancelNextFind = true

        val interrupted = runCatching { bob.router.ingest(envelope, alice.signer.nodeId) }.exceptionOrNull()
        assertTrue(interrupted is CancellationException)
        assertEquals(1, bob.conversations.values.size)
        assertEquals(0, bob.packetStore.values.values.count { it.kind == PacketKind.DELIVERY_RECEIPT })

        assertTrue(bob.router.ingest(envelope, alice.signer.nodeId) is IngestResult.Duplicate)
        assertEquals(1, bob.conversations.values.size)
        assertEquals(1, bob.packetStore.values.values.count { it.kind == PacketKind.DELIVERY_RECEIPT })
    }

    @Test fun receiptClaimRecoversWithoutCreatingTwoPacketsWhenInterrupted() = runTest {
        val now = 50_000L
        val alice = TestNode("Alice", now)
        val bob = TestNode("Bob", now)
        alice.trust(bob); bob.trust(alice)
        val direct = alice.router.createDirectMessage(bob.signer.nodeId, "Claim recovery")
        val envelope = alice.envelope(direct.messageId)
        bob.receipts.cancelAfterNextInsert = true

        val interrupted = runCatching { bob.router.ingest(envelope, alice.signer.nodeId) }.exceptionOrNull()
        assertTrue(interrupted is CancellationException)
        assertEquals(1, bob.receipts.values.size)
        assertEquals(0, bob.packetStore.values.values.count { it.kind == PacketKind.DELIVERY_RECEIPT })

        assertTrue(bob.router.ingest(envelope, alice.signer.nodeId) is IngestResult.Duplicate)
        assertEquals(1, bob.receipts.values.size)
        assertEquals(1, bob.packetStore.values.values.count { it.kind == PacketKind.DELIVERY_RECEIPT })
    }

    @Test fun cancelledLocalDirectPersistenceExposesNoRelayablePacketWithoutSenderProjection() = runTest {
        val now = 60_000L
        val alice = TestNode("Alice", now)
        val bob = TestNode("Bob", now)
        alice.trust(bob)
        alice.localProjections.cancelNextPersist = true

        val interrupted = runCatching {
            alice.router.createDirectMessage(bob.signer.nodeId, "Atomic local write")
        }.exceptionOrNull()
        assertTrue(interrupted is CancellationException)
        assertEquals(0, alice.packetStore.values.size)
        assertEquals(0, alice.conversations.values.size)
        assertEquals(emptyList<String>(), alice.router.inventory())

        val retry = alice.router.createDirectMessage(bob.signer.nodeId, "Atomic local write")
        assertEquals(DeliveryState.QUEUED, retry.deliveryState)
        assertEquals(1, alice.packetStore.values.size)
        assertEquals(1, alice.conversations.values.size)
        assertEquals(listOf(retry.messageId), alice.router.inventory())
    }

    private fun receiptPayload(origin: TestNode, targetNodeId: String, messageId: String, now: Long) = PayloadV2(
        UUID.randomUUID(), PacketKind.DELIVERY_RECEIPT, Audience.DirectNode(targetNodeId),
        origin.signer.nodeId, origin.name, 1, now, now + PROPAGATION_WINDOW_MS,
        RelayPolicy.EPHEMERAL, DeliveryReceiptBody(messageId),
    )

    private fun signedEnvelope(payload: PayloadV2, signer: JvmSigner): RelayEnvelope {
        val bytes = ProtocolCodec.encodePayload(payload)
        return RelayEnvelope(SignedPacket(bytes, signer.sign(bytes), signer.publicKey))
    }

    private class TestNode(val name: String, private val now: Long) {
        val signer = JvmSigner()
        val packetStore = MemoryPackets()
        val conversations = MemoryConversations()
        val contacts = MemoryContacts()
        val receipts = MemoryReceipts()
        val localProjections = MemoryLocalProjections(packetStore, conversations)
        private val peers = MemoryPeers()
        val router = MessageRouter(
            packetStore, conversations, peers, signer, { name }, { now }, contacts, receipts,
            localProjections = localProjections,
        )
        val directMessages = DirectMessageService(conversations, router)
        suspend fun trust(other: TestNode) = contacts.upsert(ContactEntity(
            other.signer.nodeId, other.name, other.signer.publicKey, other.signer.fingerprint,
            ContactState.TRUSTED, null, null, null, null, now,
        ))
        fun envelope(packetId: String) = ProtocolCodec.decodeEnvelope(packetStore.values.getValue(packetId).rawEnvelope)
        fun only(kind: PacketKind) = packetStore.values.values.single { it.kind == kind }
            .let { ProtocolCodec.decodeEnvelope(it.rawEnvelope) }
    }

    private class MemoryReceipts : ReceiptRepository {
        val values = linkedMapOf<Pair<String, String>, MessageReceiptEntity>()
        var cancelNextFind = false
        var cancelAfterNextInsert = false
        override suspend fun find(messageId: String, recipientNodeId: String): MessageReceiptEntity? {
            if (cancelNextFind) {
                cancelNextFind = false
                throw CancellationException("receipt lookup interrupted")
            }
            return values[messageId to recipientNodeId]
        }
        override suspend fun insert(receipt: MessageReceiptEntity): Boolean {
            val inserted = values.putIfAbsent(receipt.messageId to receipt.recipientNodeId, receipt) == null
            if (cancelAfterNextInsert) {
                cancelAfterNextInsert = false
                throw CancellationException("receipt claim interrupted")
            }
            return inserted
        }
    }

    private class MemoryLocalProjections(
        private val packets: MemoryPackets,
        private val conversations: MemoryConversations,
    ) : LocalProjectionRepository {
        var cancelNextPersist = false
        override suspend fun persist(
            packet: PacketEntity,
            message: ConversationMessageEntity,
        ): Boolean {
            if (cancelNextPersist) {
                cancelNextPersist = false
                throw CancellationException("local projection interrupted")
            }
            if (!packets.insert(packet)) return false
            if (!conversations.insert(message)) return false
            return packets.markProjected(packet.packetId)
        }
    }

    private class MemoryContacts : ContactRepository {
        val values = linkedMapOf<String, ContactEntity>()
        private val flow = MutableStateFlow<List<ContactEntity>>(emptyList())
        override fun observeContacts(): Flow<List<ContactEntity>> = flow
        override suspend fun find(nodeId: String) = values[nodeId]
        override suspend fun upsert(contact: ContactEntity) { values[contact.nodeId] = contact; flow.value = values.values.toList() }
        override suspend fun delete(nodeId: String) { values.remove(nodeId); flow.value = values.values.toList() }
        override suspend fun deleteExpiredPending(now: Long) = Unit
    }

    private class MemoryPackets : PacketRepository {
        val values = linkedMapOf<String, PacketEntity>()
        private var sequence = 0L
        override suspend fun insert(packet: PacketEntity) = values.putIfAbsent(packet.packetId, packet) == null
        override suspend fun find(packetId: String) = values[packetId]
        override suspend fun inventoryIds() = values.values.filter { it.relayEligible }.map { it.packetId }
        override suspend fun findAll(packetIds: List<String>) = packetIds.mapNotNull(values::get)
        override suspend fun nextSequence() = ++sequence
        override suspend fun markProjected(packetId: String): Boolean {
            val packet = values[packetId] ?: return false
            values[packetId] = packet.copy(projectionState = ProjectionState.PROJECTED)
            return true
        }
        override suspend fun markSuppressed(packetId: String): Boolean {
            val packet = values[packetId] ?: return false
            values[packetId] = packet.copy(projectionState = ProjectionState.SUPPRESSED)
            return true
        }
        override suspend fun markRelayed(packetId: String, peerId: String) = Unit
        override suspend fun cleanup() = Unit
    }

    private class MemoryConversations : ConversationRepository {
        val values = linkedMapOf<String, ConversationMessageEntity>()
        private val flow = MutableStateFlow<List<ConversationMessageEntity>>(emptyList())
        override fun observeMessages(): Flow<List<ConversationMessageEntity>> = flow
        override suspend fun insert(message: ConversationMessageEntity): Boolean {
            if (values.putIfAbsent(message.messageId, message) != null) return false
            flow.value = values.values.toList(); return true
        }
        override suspend fun find(messageId: String) = values[messageId]
        override suspend fun markRelayed(messageId: String) {
            values[messageId]?.let { values[messageId] = it.copy(relayed = true) }
        }
        override suspend fun markDelivered(messageId: String) {
            values[messageId]?.let { values[messageId] = it.copy(delivered = true) }
        }
        override suspend fun cleanup() = Unit
    }

    private class MemoryPeers : PeerRepository {
        override suspend fun upsert(profile: NodeProfile) = Unit
        override suspend fun find(nodeId: String): PeerEntity? = null
        override fun observePeers(): Flow<List<PeerEntity>> = kotlinx.coroutines.flow.flowOf(emptyList())
    }

    private class JvmSigner : IdentitySigner {
        private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        override val publicKey = pair.public.encoded
        override val nodeId = MessageDigest.getInstance("SHA-256").digest(publicKey)
            .joinToString("") { "%02x".format(it) }.take(32)
        override val fingerprint = nodeId.take(16).chunked(4).joinToString("-")
        override fun sign(payload: ByteArray) = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private); update(payload); sign()
        }
        override fun verify(payload: ByteArray, signature: ByteArray, publicKey: ByteArray) = runCatching {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey))
            Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(payload); verify(signature) }
        }.getOrDefault(false)
    }
}
