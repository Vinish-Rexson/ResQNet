package com.resqnet.app.mesh

import com.resqnet.app.data.*
import com.resqnet.app.protocol.*
import com.resqnet.app.security.IdentitySigner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.security.*
import java.security.spec.X509EncodedKeySpec

class MessageRouterTest {
    @Test fun acceptsSignedPacketThenDeduplicatesIt() = runTest {
        val clock = { 1_000L }
        val aRepo = MemoryMessages(clock); val bRepo = MemoryMessages(clock)
        val a = MessageRouter(aRepo, MemoryPeers(), JvmSigner(), { "Alice" }, clock)
        val b = MessageRouter(bRepo, MemoryPeers(), JvmSigner(), { "Bob" }, clock)
        val created = a.createMessage("hello through the mesh")
        val envelope = ProtocolCodec.decodeEnvelope(created.packetBytes)
        assertTrue(b.ingest(envelope, "peer-a") is IngestResult.Accepted)
        assertTrue(b.ingest(envelope, "peer-a") is IngestResult.Duplicate)
        assertEquals(1, bRepo.values.size)
        assertEquals(1, bRepo.values.values.single().hopCount)
    }

    @Test fun rejectsTamperingAndExpiredPackets() = runTest {
        var now = 1_000L
        val a = MessageRouter(MemoryMessages { now }, MemoryPeers(), JvmSigner(), { "Alice" }, { now })
        val b = MessageRouter(MemoryMessages { now }, MemoryPeers(), JvmSigner(), { "Bob" }, { now })
        val envelope = ProtocolCodec.decodeEnvelope(a.createMessage("authentic").packetBytes)
        val altered = envelope.copy(packet = envelope.packet.copy(payloadBytes = envelope.packet.payloadBytes.clone().also { it[it.lastIndex] = 1 }))
        assertTrue(b.ingest(altered, "a") is IngestResult.Rejected)
        now += PROPAGATION_WINDOW_MS + 1
        assertTrue(b.ingest(envelope, "a") is IngestResult.Rejected)
    }

    private class JvmSigner : IdentitySigner {
        private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        override val publicKey = pair.public.encoded
        override val nodeId = MessageDigest.getInstance("SHA-256").digest(publicKey).joinToString("") { "%02x".format(it) }.take(32)
        override val fingerprint = nodeId.take(16)
        override fun sign(payload: ByteArray) = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(payload); sign() }
        override fun verify(payload: ByteArray, signature: ByteArray, publicKey: ByteArray) = runCatching {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey))
            Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(payload); verify(signature) }
        }.getOrDefault(false)
    }

    private class MemoryMessages(private val clock: () -> Long) : MessageRepository {
        val values = linkedMapOf<String, MessageEntity>(); private val flow = MutableStateFlow<List<MessageEntity>>(emptyList()); private var seq = 0L
        override fun observeMessages(): Flow<List<MessageEntity>> = flow
        override suspend fun insert(message: MessageEntity): Boolean { if (values.containsKey(message.messageId)) return false; values[message.messageId] = message; flow.value = values.values.toList(); return true }
        override suspend fun find(messageId: String) = values[messageId]
        override suspend fun recentIds() = values.keys.toList()
        override suspend fun findAll(ids: List<String>) = ids.mapNotNull(values::get)
        override suspend fun nextSequence() = ++seq
        override suspend fun markRelayed(messageId: String, peerId: String) { values[messageId]?.let { values[messageId] = it.copy(relayed = true) } }
        override suspend fun cleanup() { values.entries.removeIf { it.value.createdAt < clock() - HISTORY_WINDOW_MS } }
    }
    private class MemoryPeers : PeerRepository {
        private val peers = mutableMapOf<String, PeerEntity>()
        override suspend fun upsert(profile: NodeProfile) { peers[profile.nodeId] = PeerEntity(profile.nodeId, profile.displayName, profile.publicKey, profile.keyFingerprint, profile.protocolVersion, 0) }
        override suspend fun find(nodeId: String) = peers[nodeId]
    }
}
