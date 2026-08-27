package com.resqnet.app.contacts

import com.resqnet.app.data.*
import com.resqnet.app.mesh.IngestResult
import com.resqnet.app.mesh.MessageRouter
import com.resqnet.app.protocol.*
import com.resqnet.app.security.IdentitySigner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

class ContactRouterTest {
    @Test fun requestRequiresDiscoveredPeerAndConfirmedFingerprintThenAcceptTrustsBothSides() = runTest {
        val now = 1_000L
        val alice = TestNode("Alice") { now }
        val bob = TestNode("Bob") { now }
        assertTrue(alice.router.onHello(bob.router.localProfile()))
        assertTrue(bob.router.onHello(alice.router.localProfile()))

        val mismatch = runCatching {
            alice.router.requestContact(bob.signer.nodeId, "wrong fingerprint")
        }.exceptionOrNull()
        assertTrue(mismatch is IllegalArgumentException)
        assertEquals(null, alice.contacts.find(bob.signer.nodeId))

        val outgoing = alice.contactService.request(bob.signer.nodeId, bob.signer.fingerprint)
        assertEquals(ContactState.PENDING_OUTGOING, outgoing.state)
        val requestEnvelope = alice.envelope(outgoing.outgoingRequestId!!)
        val requestPayload = ProtocolCodec.decodePayload(requestEnvelope.packet.payloadBytes)
        assertEquals(requestPayload.packetId.toString(), (requestPayload.body as ContactRequestBody).requestId)

        assertTrue(bob.router.ingest(requestEnvelope, alice.signer.nodeId) is IngestResult.Projected)
        assertEquals(ContactState.PENDING_INCOMING, bob.contacts.find(alice.signer.nodeId)?.state)
        assertEquals(alice.signer.fingerprint, bob.contacts.find(alice.signer.nodeId)?.fingerprint)

        val accepted = bob.contactService.accept(alice.signer.nodeId)
        assertEquals(ContactState.TRUSTED, accepted.state)
        val acceptEnvelope = bob.packetStore.values.values
            .single { it.kind == PacketKind.CONTACT_ACCEPT }
            .let { ProtocolCodec.decodeEnvelope(it.rawEnvelope) }
        assertEquals(
            requestPayload.packetId.toString(),
            (ProtocolCodec.decodePayload(acceptEnvelope.packet.payloadBytes).body as ContactAcceptBody).requestId,
        )

        assertTrue(alice.router.ingest(acceptEnvelope, bob.signer.nodeId) is IngestResult.Projected)
        assertEquals(ContactState.TRUSTED, alice.contacts.find(bob.signer.nodeId)?.state)
    }

    @Test fun crossedRequestsMergeAndDuplicateControlPacketsAreIdempotent() = runTest {
        val now = 2_000L
        val alice = TestNode("Alice") { now }
        val bob = TestNode("Bob") { now }
        alice.discover(bob); bob.discover(alice)

        val aliceOutgoing = alice.router.requestContact(bob.signer.nodeId, bob.signer.fingerprint)
        val bobOutgoing = bob.router.requestContact(alice.signer.nodeId, alice.signer.fingerprint)
        assertTrue(bob.router.ingest(alice.envelope(aliceOutgoing.outgoingRequestId!!), alice.signer.nodeId) is IngestResult.Projected)
        assertTrue(alice.router.ingest(bob.envelope(bobOutgoing.outgoingRequestId!!), bob.signer.nodeId) is IngestResult.Projected)

        val merged = alice.contacts.find(bob.signer.nodeId)!!
        assertEquals(ContactState.PENDING_INCOMING, merged.state)
        assertEquals(aliceOutgoing.outgoingRequestId, merged.outgoingRequestId)
        assertEquals(bobOutgoing.outgoingRequestId, merged.incomingRequestId)

        alice.router.acceptContact(bob.signer.nodeId)
        val accept = alice.only(PacketKind.CONTACT_ACCEPT)
        assertTrue(bob.router.ingest(accept, alice.signer.nodeId) is IngestResult.Projected)
        assertEquals(ContactState.TRUSTED, alice.contacts.find(bob.signer.nodeId)?.state)
        assertEquals(ContactState.TRUSTED, bob.contacts.find(alice.signer.nodeId)?.state)
        assertTrue(bob.router.ingest(accept, alice.signer.nodeId) is IngestResult.Duplicate)
        assertEquals(1, bob.packetStore.values.values.count { it.kind == PacketKind.CONTACT_ACCEPT })
    }

    @Test fun crossedRequestConfirmationUsesIncomingRequestsOwnExpiry() = runTest {
        var now = 5_000L
        val alice = TestNode("Alice") { now }
        val bob = TestNode("Bob") { now }
        alice.discover(bob); bob.discover(alice)
        val aliceRequest = alice.router.requestContact(bob.signer.nodeId, bob.signer.fingerprint)

        now += PROPAGATION_WINDOW_MS - 60_000L
        bob.discover(alice)
        val bobRequest = bob.router.requestContact(alice.signer.nodeId, alice.signer.fingerprint)
        alice.router.ingest(bob.envelope(bobRequest.outgoingRequestId!!), bob.signer.nodeId)
        val merged = alice.contacts.find(bob.signer.nodeId)!!
        assertEquals(aliceRequest.outgoingRequestExpiresAt, merged.outgoingRequestExpiresAt)
        assertEquals(bobRequest.outgoingRequestExpiresAt, merged.incomingRequestExpiresAt)

        now += 120_000L
        val accepted = alice.router.acceptContact(bob.signer.nodeId)
        assertEquals(ContactState.TRUSTED, accepted.state)
    }

    @Test fun crossedDeclinePreservesTheOtherRequestSoAValidAcceptConvergesBothSides() = runTest {
        val now = 5_500L
        val alice = TestNode("Alice") { now }
        val bob = TestNode("Bob") { now }
        alice.discover(bob); bob.discover(alice)
        val aliceRequest = alice.router.requestContact(bob.signer.nodeId, bob.signer.fingerprint)
        val bobRequest = bob.router.requestContact(alice.signer.nodeId, alice.signer.fingerprint)
        alice.router.ingest(bob.envelope(bobRequest.outgoingRequestId!!), bob.signer.nodeId)
        bob.router.ingest(alice.envelope(aliceRequest.outgoingRequestId!!), alice.signer.nodeId)

        alice.router.declineContact(bob.signer.nodeId)
        assertEquals(ContactState.PENDING_OUTGOING, alice.contacts.find(bob.signer.nodeId)?.state)
        val decline = alice.only(PacketKind.CONTACT_DECLINE)
        bob.router.ingest(decline, alice.signer.nodeId)
        assertEquals(ContactState.PENDING_INCOMING, bob.contacts.find(alice.signer.nodeId)?.state)

        bob.router.acceptContact(alice.signer.nodeId)
        alice.router.ingest(bob.only(PacketKind.CONTACT_ACCEPT), bob.signer.nodeId)
        assertEquals(ContactState.TRUSTED, alice.contacts.find(bob.signer.nodeId)?.state)
        assertEquals(ContactState.TRUSTED, bob.contacts.find(alice.signer.nodeId)?.state)
    }

    @Test fun newRequestReplacesExpiredIncomingConfirmation() = runTest {
        var now = 6_000L
        val alice = TestNode("Alice") { now }
        val bob = TestNode("Bob") { now }
        alice.discover(bob); bob.discover(alice)
        val first = alice.router.requestContact(bob.signer.nodeId, bob.signer.fingerprint)
        bob.router.ingest(alice.envelope(first.outgoingRequestId!!), alice.signer.nodeId)

        now += PROPAGATION_WINDOW_MS + 1
        alice.discover(bob)
        val second = alice.router.requestContact(bob.signer.nodeId, bob.signer.fingerprint)
        bob.router.ingest(alice.envelope(second.outgoingRequestId!!), alice.signer.nodeId)

        val pending = bob.contacts.find(alice.signer.nodeId)!!
        assertEquals(second.outgoingRequestId, pending.incomingRequestId)
        assertEquals(ContactState.TRUSTED, bob.router.acceptContact(alice.signer.nodeId).state)
    }

    @Test fun declineExpiresAfterTwentyFourHoursAndRemoveAllowsAnotherRequest() = runTest {
        var now = 3_000L
        val alice = TestNode("Alice") { now }
        val bob = TestNode("Bob") { now }
        alice.discover(bob); bob.discover(alice)

        val first = alice.router.requestContact(bob.signer.nodeId, bob.signer.fingerprint)
        bob.router.ingest(alice.envelope(first.outgoingRequestId!!), alice.signer.nodeId)
        bob.router.declineContact(alice.signer.nodeId)
        assertEquals(null, bob.contacts.find(alice.signer.nodeId))
        assertTrue(alice.router.ingest(bob.only(PacketKind.CONTACT_DECLINE), bob.signer.nodeId) is IngestResult.Projected)
        assertEquals(null, alice.contacts.find(bob.signer.nodeId))

        val second = alice.router.requestContact(bob.signer.nodeId, bob.signer.fingerprint)
        alice.router.removeContact(bob.signer.nodeId)
        assertEquals(null, alice.contacts.find(bob.signer.nodeId))
        val third = alice.router.requestContact(bob.signer.nodeId, bob.signer.fingerprint)
        assertTrue(third.outgoingRequestId != second.outgoingRequestId)

        now += PROPAGATION_WINDOW_MS + 1
        assertTrue(bob.router.ingest(alice.envelope(third.outgoingRequestId!!), alice.signer.nodeId) is IngestResult.Rejected)
        alice.router.cleanup()
        assertEquals(null, alice.contacts.find(bob.signer.nodeId))
    }

    @Test fun blockSuppressesRequestsAndUnblockReturnsToNoTrust() = runTest {
        val now = 4_000L
        val alice = TestNode("Alice") { now }
        val bob = TestNode("Bob") { now }
        alice.discover(bob); bob.discover(alice)
        bob.router.blockContact(alice.signer.nodeId)
        assertEquals(ContactState.BLOCKED, bob.contacts.find(alice.signer.nodeId)?.state)

        val request = alice.router.requestContact(bob.signer.nodeId, bob.signer.fingerprint)
        val result = bob.router.ingest(alice.envelope(request.outgoingRequestId!!), alice.signer.nodeId)
        assertTrue(result is IngestResult.StoredOnly)
        assertEquals(ContactState.BLOCKED, bob.contacts.find(alice.signer.nodeId)?.state)

        bob.router.unblockContact(alice.signer.nodeId)
        assertEquals(null, bob.contacts.find(alice.signer.nodeId))
        assertTrue(bob.router.ingest(alice.envelope(request.outgoingRequestId!!), alice.signer.nodeId) is IngestResult.Duplicate)
        assertEquals(null, bob.contacts.find(alice.signer.nodeId))
    }

    @Test fun contactRequestRequiresPeerSeenInsideDiscoveryFreshnessWindow() = runTest {
        var now = 7_000L
        val alice = TestNode("Alice") { now }
        val bob = TestNode("Bob") { now }
        alice.discover(bob)

        now += DISCOVERY_FRESHNESS_WINDOW_MS + 1
        val stale = runCatching {
            alice.router.requestContact(bob.signer.nodeId, bob.signer.fingerprint)
        }.exceptionOrNull()
        assertTrue(stale is IllegalArgumentException)
        assertEquals(null, alice.contacts.find(bob.signer.nodeId))

        alice.discover(bob)
        assertEquals(
            ContactState.PENDING_OUTGOING,
            alice.router.requestContact(bob.signer.nodeId, bob.signer.fingerprint).state,
        )
    }

    private class TestNode(name: String, now: () -> Long) {
        val signer = JvmSigner()
        val packetStore = MemoryPackets()
        val contacts = MemoryContacts()
        private val peers = MemoryPeers(now)
        val router = MessageRouter(
            packetStore, MemoryConversations(), peers, signer, { name }, now, contacts,
        )
        val contactService = ContactService(contacts, router)
        fun envelope(packetId: String) = ProtocolCodec.decodeEnvelope(packetStore.values.getValue(packetId).rawEnvelope)
        fun only(kind: PacketKind) = packetStore.values.values.single { it.kind == kind }
            .let { ProtocolCodec.decodeEnvelope(it.rawEnvelope) }
        suspend fun discover(other: TestNode) {
            assertTrue(router.onHello(other.router.localProfile()))
        }
    }

    private class MemoryContacts : ContactRepository {
        val values = linkedMapOf<String, ContactEntity>()
        private val flow = MutableStateFlow<List<ContactEntity>>(emptyList())
        override fun observeContacts(): Flow<List<ContactEntity>> = flow
        override suspend fun find(nodeId: String) = values[nodeId]
        override suspend fun upsert(contact: ContactEntity) {
            values[contact.nodeId] = contact
            flow.value = values.values.toList()
        }
        override suspend fun delete(nodeId: String) {
            values.remove(nodeId)
            flow.value = values.values.toList()
        }
        override suspend fun deleteExpiredPending(now: Long) {
            values.keys.toList().forEach { nodeId ->
                val normalized = values[nodeId]?.withoutExpiredRequests(now)
                if (normalized == null) values.remove(nodeId) else values[nodeId] = normalized
            }
            flow.value = values.values.toList()
        }
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
        private val values = linkedMapOf<String, ConversationMessageEntity>()
        private val flow = MutableStateFlow<List<ConversationMessageEntity>>(emptyList())
        override fun observeMessages(): Flow<List<ConversationMessageEntity>> = flow
        override suspend fun insert(message: ConversationMessageEntity) = values.putIfAbsent(message.messageId, message) == null
        override suspend fun find(messageId: String) = values[messageId]
        override suspend fun markRelayed(messageId: String) = Unit
        override suspend fun cleanup() = Unit
    }

    private class MemoryPeers(private val clock: () -> Long) : PeerRepository {
        private val values = mutableMapOf<String, PeerEntity>()
        override suspend fun upsert(profile: NodeProfile) {
            values[profile.nodeId] = PeerEntity(
                profile.nodeId, profile.displayName, profile.publicKey, profile.keyFingerprint,
                profile.transportVersion, clock(),
            )
        }
        override suspend fun find(nodeId: String) = values[nodeId]
        override fun observePeers(): Flow<List<PeerEntity>> = kotlinx.coroutines.flow.flowOf(values.values.toList())
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
