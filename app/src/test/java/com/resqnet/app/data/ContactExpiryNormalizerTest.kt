package com.resqnet.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactExpiryNormalizerTest {
    @Test fun concurrentReplacementSurvivesConditionalExpiryDelete() = runTest {
        val stale = pendingOutgoing("old-request", expiresAt = 100L)
        val replacement = pendingOutgoing("new-request", expiresAt = 1_000L)
        val store = RacingExpiryStore(stale, replacement)

        PendingContactExpiry(store).expire(now = 200L)

        assertEquals(replacement, store.current)
    }

    @Test fun concurrentCrossedReplacementSurvivesConditionalExpiryUpdate() = runTest {
        val stale = ContactEntity(
            nodeId = "node-b", displayName = "Bob", publicKey = byteArrayOf(1), fingerprint = "bbbb",
            state = ContactState.PENDING_INCOMING,
            outgoingRequestId = "expired-outgoing", outgoingRequestExpiresAt = 100L,
            incomingRequestId = "old-incoming", incomingRequestExpiresAt = 1_000L,
            updatedAt = 10L,
        )
        val replacement = stale.copy(
            incomingRequestId = "new-incoming",
            incomingRequestExpiresAt = 2_000L,
            updatedAt = 20L,
        )
        val store = RacingExpiryStore(stale, replacement)

        PendingContactExpiry(store).expire(now = 200L)

        assertEquals(replacement, store.current)
    }

    private fun pendingOutgoing(requestId: String, expiresAt: Long) = ContactEntity(
        nodeId = "node-b", displayName = "Bob", publicKey = byteArrayOf(1), fingerprint = "bbbb",
        state = ContactState.PENDING_OUTGOING,
        outgoingRequestId = requestId, outgoingRequestExpiresAt = expiresAt,
        incomingRequestId = null, incomingRequestExpiresAt = null,
        updatedAt = expiresAt,
    )

    private class RacingExpiryStore(
        private val staleSnapshot: ContactEntity,
        replacement: ContactEntity,
    ) : ContactExpiryStore {
        var current: ContactEntity? = replacement

        override suspend fun pendingContacts(): List<ContactEntity> = listOf(staleSnapshot)

        override suspend fun deleteIfPendingSnapshotMatches(expected: ContactEntity): Boolean {
            if (current?.samePendingSnapshot(expected) != true) return false
            current = null
            return true
        }

        override suspend fun replaceIfPendingSnapshotMatches(
            expected: ContactEntity,
            replacement: ContactEntity,
        ): Boolean {
            if (current?.samePendingSnapshot(expected) != true) return false
            current = replacement
            return true
        }
    }
}
