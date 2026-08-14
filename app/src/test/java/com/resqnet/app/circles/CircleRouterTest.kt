package com.resqnet.app.circles

import com.resqnet.app.data.*
import com.resqnet.app.mesh.IngestResult
import com.resqnet.app.mesh.MessageRouter
import com.resqnet.app.protocol.*
import com.resqnet.app.security.IdentitySigner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID

class CircleRouterTest {
    @Test fun ownerCreatesPrivateV1InvitesTrustedContactsAndTimelyAcceptancePublishesOneV2() = runTest {
        val clock = TestClock(10_000L)
        val owner = TestNode("Owner", clock)
        val member = TestNode("Member", clock)
        owner.trust(member); member.trust(owner)

        val circle = owner.router.createCircle("Family")
        val first = owner.circles.latestSnapshot(circle.circleId)!!
        assertEquals(1L, first.membershipVersion)
        assertEquals(listOf(owner.signer.nodeId), owner.circles.members(circle.circleId, 1).map { it.nodeId })

        val invite = owner.router.inviteToCircle(circle.circleId, member.signer.nodeId)
        assertEquals(1L, owner.circles.latestSnapshot(circle.circleId)!!.membershipVersion)
        assertEquals(listOf(owner.signer.nodeId), invite.activeMemberPreview.map { it.nodeId })
        assertEquals(member.signer.nodeId, (owner.payload(invite.packetId).audience as Audience.DirectNode).nodeId)
        assertEquals(PROPAGATION_WINDOW_MS, invite.expiresAt - invite.createdAt)

        assertTrue(member.router.ingest(owner.envelope(invite.packetId), owner.signer.nodeId) is IngestResult.Projected)
        val acceptPacketId = member.router.acceptCircleInvite(invite.inviteId)
        assertEquals(acceptPacketId, member.router.acceptCircleInvite(invite.inviteId))
        assertEquals(CircleLocalState.ACCEPTANCE_PENDING, member.circles.circle(circle.circleId)!!.localState)
        val accept = member.packetStore.values.getValue(acceptPacketId)
        assertEquals(RelayPolicy.DURABLE_UNTIL_RESOLVED, accept.relayPolicy)

        clock.now = invite.expiresAt + 1
        assertTrue(owner.router.ingest(member.envelope(acceptPacketId), member.signer.nodeId) is IngestResult.Projected)
        assertFalse(owner.router.inventory().contains(acceptPacketId))
        assertEquals(2L, owner.circles.latestSnapshot(circle.circleId)!!.membershipVersion)
        assertEquals(2, owner.circles.members(circle.circleId, 2).size)
        assertEquals(1, owner.packetStore.values.values.count {
            it.kind == PacketKind.CIRCLE_MEMBERSHIP_SNAPSHOT && it.originSequence > first.originSequence
        })
        val relayableSnapshotVersions = owner.router.inventory().map(owner::payload).mapNotNull {
            (it.body as? CircleMembershipSnapshotBody)?.membershipVersion
        }
        assertEquals(listOf(2L), relayableSnapshotVersions)

        assertTrue(member.router.ingest(owner.onlySnapshot(2), owner.signer.nodeId) is IngestResult.Projected)
        assertEquals(CircleLocalState.ACTIVE, member.circles.circle(circle.circleId)!!.localState)
    }

    @Test fun inviteExpiryDeclineTrustAndTwentyMemberLimitAreEnforced() = runTest {
        val clock = TestClock(20_000L)
        val owner = TestNode("Owner", clock)
        val invitee = TestNode("Invitee", clock)
        val stranger = TestNode("Stranger", clock)
        owner.trust(invitee); invitee.trust(owner)
        val circle = owner.router.createCircle("Family")

        assertTrue(runCatching { owner.router.inviteToCircle(circle.circleId, stranger.signer.nodeId) }.exceptionOrNull() is IllegalArgumentException)
        val invite = owner.router.inviteToCircle(circle.circleId, invitee.signer.nodeId)
        invitee.router.ingest(owner.envelope(invite.packetId), owner.signer.nodeId)
        invitee.router.declineCircleInvite(invite.inviteId)
        assertTrue(owner.router.ingest(invitee.only(PacketKind.CIRCLE_INVITE_DECLINE), invitee.signer.nodeId) is IngestResult.Projected)
        assertEquals(CircleInvitationState.DECLINED, owner.circles.invitation(invite.inviteId)!!.state)

        val next = owner.router.inviteToCircle(circle.circleId, invitee.signer.nodeId)
        invitee.router.ingest(owner.envelope(next.packetId), owner.signer.nodeId)
        clock.now = next.expiresAt
        assertTrue(runCatching { invitee.router.acceptCircleInvite(next.inviteId) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(CircleInvitationState.EXPIRED, invitee.circles.invitation(next.inviteId)!!.state)

        val members = (0 until MAX_CIRCLE_MEMBERS).map { index ->
            CircleMemberEntity(circle.circleId, 5, "node-$index", if (index == 0) CircleMemberRole.OWNER else CircleMemberRole.MEMBER)
        }
        owner.circles.insertSnapshot(
            CircleSnapshotEntity(circle.circleId, 5, "Family", members.first().nodeId, false, UUID.randomUUID().toString(), 5, clock.now),
            members,
        )
        owner.circles.upsertCircle(circle.copy(currentMembershipVersion = 5))
        assertTrue(runCatching { owner.router.inviteToCircle(circle.circleId, invitee.signer.nodeId) }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun rejectsInvalidSnapshotsAndReplaysPacketBeforeSnapshotExactlyOnce() = runTest {
        val clock = TestClock(30_000L)
        val owner = TestNode("Owner", clock)
        val member = TestNode("Member", clock)
        val mallory = TestNode("Mallory", clock)
        val circleId = UUID.randomUUID().toString()
        val v1Members = listOf(
            CircleSnapshotMember(owner.signer.nodeId, CircleMemberRole.OWNER),
            CircleSnapshotMember(member.signer.nodeId, CircleMemberRole.MEMBER),
        )
        val text = owner.signed(circleText(owner, circleId, 1, "Before snapshot", clock.now))
        assertTrue(member.router.ingest(text, owner.signer.nodeId) is IngestResult.StoredOnly)
        assertEquals(0, member.circles.messages(circleId).size)

        val snapshot = owner.signed(snapshot(owner, circleId, 1, "Family", v1Members, clock.now))
        owner.router.ingest(snapshot, owner.signer.nodeId)
        assertTrue(member.router.ingest(snapshot, owner.signer.nodeId) is IngestResult.Projected)
        assertEquals(listOf("Before snapshot"), member.circles.messages(circleId).map { it.text })
        assertTrue(member.router.ingest(snapshot, owner.signer.nodeId) is IngestResult.Duplicate)
        assertEquals(1, member.circles.messages(circleId).size)

        val forged = mallory.signed(snapshot(mallory, circleId, 2, "Hacked", v1Members, clock.now))
        assertTrue(member.router.ingest(forged, mallory.signer.nodeId) is IngestResult.StoredOnly)
        assertEquals(1L, member.circles.latestSnapshot(circleId)!!.membershipVersion)

        val duplicateOwner = owner.signed(snapshot(owner, circleId, 2, "Bad", listOf(
            CircleSnapshotMember(owner.signer.nodeId, CircleMemberRole.OWNER),
            CircleSnapshotMember(owner.signer.nodeId, CircleMemberRole.MEMBER),
        ), clock.now))
        assertTrue(member.router.ingest(duplicateOwner, owner.signer.nodeId) is IngestResult.StoredOnly)
        assertEquals(1L, member.circles.latestSnapshot(circleId)!!.membershipVersion)

        val gap = owner.signed(snapshot(owner, circleId, 4, "Family", v1Members, clock.now))
        assertTrue(member.router.ingest(gap, owner.signer.nodeId) is IngestResult.Projected)
        assertEquals(4L, member.circles.latestSnapshot(circleId)!!.membershipVersion)

        val nonmemberText = mallory.signed(circleText(mallory, circleId, 4, "Invisible", clock.now))
        assertTrue(member.router.ingest(nonmemberText, mallory.signer.nodeId) is IngestResult.StoredOnly)
        assertEquals(listOf("Before snapshot"), member.circles.messages(circleId).map { it.text })

        val unissuedAccept = mallory.signed(PayloadV2(
            UUID.randomUUID(), PacketKind.CIRCLE_INVITE_ACCEPT, Audience.DirectNode(owner.signer.nodeId),
            mallory.signer.nodeId, mallory.name, 8, clock.now, Long.MAX_VALUE,
            RelayPolicy.DURABLE_UNTIL_RESOLVED,
            CircleInviteAcceptBody(UUID.randomUUID().toString(), circleId),
        ))
        assertTrue(owner.router.ingest(unissuedAccept, mallory.signer.nodeId) is IngestResult.StoredOnly)
        assertEquals(1L, owner.circles.latestSnapshot(circleId)!!.membershipVersion)
        val regression = owner.signed(snapshot(owner, circleId, 3, "Old", v1Members, clock.now))
        assertTrue(member.router.ingest(regression, owner.signer.nodeId) is IngestResult.StoredOnly)
        assertEquals(4L, member.circles.latestSnapshot(circleId)!!.membershipVersion)
    }

    @Test fun circleTextReceiptsUseReferencedVersionAndRemovalSuppressesDelayedTextDespiteContactChanges() = runTest {
        val clock = TestClock(40_000L)
        val owner = TestNode("Owner", clock)
        val bob = TestNode("Bob", clock)
        val carol = TestNode("Carol", clock)
        val relay = TestNode("Relay", clock)
        val circleId = UUID.randomUUID().toString()
        val v1 = listOf(owner, bob, carol).mapIndexed { i, node ->
            CircleSnapshotMember(node.signer.nodeId, if (i == 0) CircleMemberRole.OWNER else CircleMemberRole.MEMBER)
        }
        listOf(owner, bob, carol).forEach { it.router.ingest(owner.signed(snapshot(owner, circleId, 1, "Family", v1, clock.now)), owner.signer.nodeId) }
        val outgoing = owner.router.createCircleMessage(circleId, "Check in")
        assertTrue(relay.router.ingest(owner.envelope(outgoing.messageId), owner.signer.nodeId) is IngestResult.StoredOnly)
        assertTrue(bob.router.ingest(relay.router.requestedPackets(listOf(outgoing.messageId)).single(), relay.signer.nodeId) is IngestResult.Projected)
        assertTrue(carol.router.ingest(owner.envelope(outgoing.messageId), owner.signer.nodeId) is IngestResult.Projected)
        assertEquals(1, bob.packetStore.values.values.count { it.kind == PacketKind.DELIVERY_RECEIPT })
        bob.only(PacketKind.DELIVERY_RECEIPT).also { owner.router.ingest(it, bob.signer.nodeId) }
        carol.only(PacketKind.DELIVERY_RECEIPT).also { owner.router.ingest(it, carol.signer.nodeId) }
        assertEquals(CircleDeliveryProgress(2, 2), owner.router.circleDeliveryProgress(outgoing.messageId))

        bob.trust(owner)
        bob.router.removeContact(owner.signer.nodeId)
        val afterContactRemoval = owner.signed(circleText(owner, circleId, 1, "Still authorized", clock.now))
        assertTrue(bob.router.ingest(afterContactRemoval, owner.signer.nodeId) is IngestResult.Projected)

        val forgedReceipt = relay.signed(receipt(relay, owner.signer.nodeId, outgoing.messageId, circleId, 1, clock.now))
        assertTrue(owner.router.ingest(forgedReceipt, relay.signer.nodeId) is IngestResult.StoredOnly)
        assertEquals(CircleDeliveryProgress(2, 2), owner.router.circleDeliveryProgress(outgoing.messageId))

        val v2 = listOf(
            CircleSnapshotMember(owner.signer.nodeId, CircleMemberRole.OWNER),
            CircleSnapshotMember(carol.signer.nodeId, CircleMemberRole.MEMBER),
        )
        bob.router.ingest(owner.signed(snapshot(owner, circleId, 2, "Family", v2, clock.now)), owner.signer.nodeId)
        assertEquals(CircleLocalState.ARCHIVED_REMOVED, bob.circles.circle(circleId)!!.localState)
        val delayed = owner.signed(circleText(owner, circleId, 1, "Delayed", clock.now))
        assertTrue(bob.router.ingest(delayed, owner.signer.nodeId) is IngestResult.StoredOnly)
        assertEquals(listOf("Check in", "Still authorized"), bob.circles.messages(circleId).map { it.text })
    }

    @Test fun statusIsSelfAuthoredSequenceOrderedAndDerivesFreshnessWithoutLosingNeedHelp() = runTest {
        val clock = TestClock(50_000L)
        val owner = TestNode("Owner", clock)
        val member = TestNode("Member", clock)
        val circleId = UUID.randomUUID().toString()
        val members = listOf(
            CircleSnapshotMember(owner.signer.nodeId, CircleMemberRole.OWNER),
            CircleSnapshotMember(member.signer.nodeId, CircleMemberRole.MEMBER),
        )
        member.router.ingest(owner.signed(snapshot(owner, circleId, 1, "Family", members, clock.now)), owner.signer.nodeId)
        member.router.updateCircleStatus(circleId, SafetyStatus.SAFE, null)
        val needHelp = member.router.updateCircleStatus(circleId, SafetyStatus.NEED_HELP, "Need insulin")
        assertEquals(SafetyStatus.NEED_HELP, member.router.effectiveCircleStatus(circleId, member.signer.nodeId)!!.effectiveStatus)

        val forgedSubject = owner.signed(status(owner, circleId, 1, member.signer.nodeId, SafetyStatus.SAFE, null, 99, clock.now))
        assertTrue(member.router.ingest(forgedSubject, owner.signer.nodeId) is IngestResult.StoredOnly)
        val older = member.signed(status(member, circleId, 1, member.signer.nodeId, SafetyStatus.SAFE, null, needHelp.originSequence - 1, clock.now + 1))
        member.router.ingest(older, member.signer.nodeId)
        assertEquals(SafetyStatus.NEED_HELP, member.circles.latestStatus(circleId, member.signer.nodeId)!!.status)

        clock.now += PROPAGATION_WINDOW_MS + 1
        val stale = member.router.effectiveCircleStatus(circleId, member.signer.nodeId)!!
        assertEquals(SafetyStatus.UNKNOWN, stale.effectiveStatus)
        assertEquals(SafetyStatus.NEED_HELP, stale.lastReportedStatus)
        assertTrue(stale.staleNeedHelpProminent)
        assertEquals("Need insulin", stale.lastNote)

        assertTrue(runCatching {
            member.router.updateCircleStatus(circleId, SafetyStatus.SAFE, "é".repeat(81))
        }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun leavePendingStaysReadOnlyAcrossRenameUntilOwnerRemovalAndDissolutionArchivesMembers() = runTest {
        val clock = TestClock(60_000L)
        val owner = TestNode("Owner", clock)
        val member = TestNode("Member", clock)
        val other = TestNode("Other", clock)
        val circleId = UUID.randomUUID().toString()
        val v1 = listOf(
            CircleSnapshotMember(owner.signer.nodeId, CircleMemberRole.OWNER),
            CircleSnapshotMember(member.signer.nodeId, CircleMemberRole.MEMBER),
            CircleSnapshotMember(other.signer.nodeId, CircleMemberRole.MEMBER),
        )
        val first = owner.signed(snapshot(owner, circleId, 1, "Family", v1, clock.now))
        owner.router.ingest(first, owner.signer.nodeId)
        member.router.ingest(first, owner.signer.nodeId)
        other.router.ingest(first, owner.signer.nodeId)

        val leavePacketId = member.router.leaveCircle(circleId)
        assertEquals(leavePacketId, member.router.leaveCircle(circleId))
        assertEquals(CircleLocalState.LEAVE_PENDING, member.circles.circle(circleId)!!.localState)
        assertTrue(runCatching { member.router.createCircleMessage(circleId, "Blocked") }.exceptionOrNull() is IllegalArgumentException)

        owner.router.renameCircle(circleId, "Renamed")
        member.router.ingest(owner.onlySnapshot(2), owner.signer.nodeId)
        assertEquals(CircleLocalState.LEAVE_PENDING, member.circles.circle(circleId)!!.localState)
        member.router.ingest(owner.signed(circleText(owner, circleId, 2, "Suppressed", clock.now)), owner.signer.nodeId)
        assertEquals(emptyList<String>(), member.circles.messages(circleId).map { it.text })

        owner.router.ingest(member.envelope(leavePacketId), member.signer.nodeId)
        assertFalse(owner.router.inventory().contains(leavePacketId))
        member.router.ingest(owner.onlySnapshot(3), owner.signer.nodeId)
        assertEquals(CircleLocalState.ARCHIVED_REMOVED, member.circles.circle(circleId)!!.localState)

        owner.router.dissolveCircle(circleId)
        other.router.ingest(owner.onlySnapshot(4), owner.signer.nodeId)
        assertEquals(CircleLocalState.ARCHIVED_DISSOLVED, other.circles.circle(circleId)!!.localState)
    }

    @Test fun duplicateProjectedCircleTextRecoversDeterministicReceiptAfterClaimInterruption() = runTest {
        val clock = TestClock(70_000L)
        val owner = TestNode("Owner", clock)
        val member = TestNode("Member", clock)
        val circleId = UUID.randomUUID().toString()
        val members = listOf(
            CircleSnapshotMember(owner.signer.nodeId, CircleMemberRole.OWNER),
            CircleSnapshotMember(member.signer.nodeId, CircleMemberRole.MEMBER),
        )
        member.router.ingest(owner.signed(snapshot(owner, circleId, 1, "Family", members, clock.now)), owner.signer.nodeId)
        val text = owner.signed(circleText(owner, circleId, 1, "Recover receipt", clock.now))
        member.circles.cancelAfterNextReceiptInsert = true

        assertTrue(runCatching { member.router.ingest(text, owner.signer.nodeId) }.exceptionOrNull() is CancellationException)
        assertEquals(1, member.circles.receiptValues.size)
        assertEquals(0, member.packetStore.values.values.count { it.kind == PacketKind.DELIVERY_RECEIPT })

        assertTrue(member.router.ingest(text, owner.signer.nodeId) is IngestResult.Duplicate)
        assertEquals(1, member.circles.receiptValues.size)
        assertEquals(1, member.packetStore.values.values.count { it.kind == PacketKind.DELIVERY_RECEIPT })
    }

    private fun snapshot(origin: TestNode, circleId: String, version: Long, name: String, members: List<CircleSnapshotMember>, now: Long) = PayloadV2(
        UUID.randomUUID(), PacketKind.CIRCLE_MEMBERSHIP_SNAPSHOT, Audience.Circle(circleId), origin.signer.nodeId,
        origin.name, 1, now, Long.MAX_VALUE, RelayPolicy.DURABLE_UNTIL_SUPERSEDED,
        CircleMembershipSnapshotBody(circleId, version, name, members.first { it.role == CircleMemberRole.OWNER }.nodeId, members),
    )
    private fun circleText(origin: TestNode, circleId: String, version: Long, text: String, now: Long) = PayloadV2(
        UUID.randomUUID(), PacketKind.CIRCLE_TEXT, Audience.Circle(circleId), origin.signer.nodeId, origin.name, 2,
        now, now + PROPAGATION_WINDOW_MS, RelayPolicy.EPHEMERAL, CircleTextBody(circleId, version, text),
    )
    private fun receipt(origin: TestNode, target: String, messageId: String, circleId: String, version: Long, now: Long) = PayloadV2(
        UUID.randomUUID(), PacketKind.DELIVERY_RECEIPT, Audience.DirectNode(target), origin.signer.nodeId, origin.name, 3,
        now, now + PROPAGATION_WINDOW_MS, RelayPolicy.EPHEMERAL, DeliveryReceiptBody(messageId, circleId, version),
    )
    private fun status(origin: TestNode, circleId: String, version: Long, subject: String, value: SafetyStatus, note: String?, sequence: Long, now: Long) = PayloadV2(
        UUID.randomUUID(), PacketKind.CIRCLE_STATUS, Audience.Circle(circleId), origin.signer.nodeId, origin.name, sequence,
        now, now + PROPAGATION_WINDOW_MS, RelayPolicy.EPHEMERAL, CircleStatusBody(circleId, version, subject, value, note),
    )

    private class TestClock(var now: Long)
    private class TestNode(val name: String, private val clock: TestClock) {
        val signer = JvmSigner()
        val packetStore = MemoryPackets()
        val conversations = MemoryConversations()
        val contacts = MemoryContacts()
        val circles = MemoryCircles()
        val router = MessageRouter(packetStore, conversations, MemoryPeers(), signer, { name }, { clock.now }, contacts, MemoryReceipts(), circles = circles)
        suspend fun trust(other: TestNode) = contacts.upsert(ContactEntity(other.signer.nodeId, other.name, other.signer.publicKey, other.signer.fingerprint, ContactState.TRUSTED, null, null, null, null, clock.now))
        fun envelope(id: String) = ProtocolCodec.decodeEnvelope(packetStore.values.getValue(id).rawEnvelope)
        fun payload(id: String) = ProtocolCodec.decodePayload(envelope(id).packet.payloadBytes)
        fun signed(payload: PayloadV2): RelayEnvelope { val bytes = ProtocolCodec.encodePayload(payload); return RelayEnvelope(SignedPacket(bytes, signer.sign(bytes), signer.publicKey)) }
        fun only(kind: PacketKind) = packetStore.values.values.single { it.kind == kind }.let { ProtocolCodec.decodeEnvelope(it.rawEnvelope) }
        fun onlySnapshot(version: Long) = packetStore.values.values.single {
            it.kind == PacketKind.CIRCLE_MEMBERSHIP_SNAPSHOT && (ProtocolCodec.decodePayload(ProtocolCodec.decodeEnvelope(it.rawEnvelope).packet.payloadBytes).body as CircleMembershipSnapshotBody).membershipVersion == version
        }.let { ProtocolCodec.decodeEnvelope(it.rawEnvelope) }
    }

    private class MemoryCircles : CircleRepository {
        val circleValues = linkedMapOf<String, CircleEntity>(); val inviteValues = linkedMapOf<String, CircleInvitationEntity>()
        val snapshotValues = linkedMapOf<Pair<String, Long>, CircleSnapshotEntity>(); val memberValues = linkedMapOf<Pair<String, Long>, List<CircleMemberEntity>>()
        val messageValues = linkedMapOf<String, CircleMessageEntity>(); val pendingValues = linkedMapOf<String, PendingCirclePacketEntity>()
        val receiptValues = linkedMapOf<Pair<String, String>, CircleMessageReceiptEntity>(); val statusValues = linkedMapOf<String, CircleStatusEventEntity>()
        var cancelAfterNextReceiptInsert = false
        override fun observeCircles(): Flow<List<CircleEntity>> = MutableStateFlow(circleValues.values.toList())
        override suspend fun circle(circleId: String) = circleValues[circleId]
        override suspend fun upsertCircle(circle: CircleEntity) { circleValues[circle.circleId] = circle }
        override suspend fun invitation(inviteId: String) = inviteValues[inviteId]
        override suspend fun invitationsForCircle(circleId: String) = inviteValues.values.filter { it.circleId == circleId }
        override suspend fun upsertInvitation(invitation: CircleInvitationEntity) { inviteValues[invitation.inviteId] = invitation }
        override suspend fun latestSnapshot(circleId: String) = snapshotValues.filterKeys { it.first == circleId }.values.maxByOrNull { it.membershipVersion }
        override suspend fun snapshot(circleId: String, membershipVersion: Long) = snapshotValues[circleId to membershipVersion]
        override suspend fun insertSnapshot(snapshot: CircleSnapshotEntity, members: List<CircleMemberEntity>): Boolean {
            if (snapshotValues.putIfAbsent(snapshot.circleId to snapshot.membershipVersion, snapshot) != null) return false
            memberValues[snapshot.circleId to snapshot.membershipVersion] = members; return true
        }
        override suspend fun members(circleId: String, membershipVersion: Long) = memberValues[circleId to membershipVersion].orEmpty()
        override suspend fun insertMessage(message: CircleMessageEntity) = messageValues.putIfAbsent(message.messageId, message) == null
        override suspend fun message(messageId: String) = messageValues[messageId]
        override suspend fun messages(circleId: String) = messageValues.values.filter { it.circleId == circleId }
        override suspend fun addPending(packet: PendingCirclePacketEntity) { pendingValues.putIfAbsent(packet.packetId, packet) }
        override suspend fun pending(circleId: String) = pendingValues.values.filter { it.circleId == circleId }
        override suspend fun removePending(packetId: String) { pendingValues.remove(packetId) }
        override suspend fun insertReceipt(receipt: CircleMessageReceiptEntity): Boolean {
            val inserted = receiptValues.putIfAbsent(receipt.messageId to receipt.recipientNodeId, receipt) == null
            if (cancelAfterNextReceiptInsert) {
                cancelAfterNextReceiptInsert = false
                throw CancellationException("Circle receipt claim interrupted")
            }
            return inserted
        }
        override suspend fun receipt(messageId: String, recipientNodeId: String) = receiptValues[messageId to recipientNodeId]
        override suspend fun receipts(messageId: String) = receiptValues.values.filter { it.messageId == messageId }
        override suspend fun insertStatus(event: CircleStatusEventEntity) = statusValues.putIfAbsent(event.packetId, event) == null
        override suspend fun latestStatus(circleId: String, memberNodeId: String) = statusValues.values.filter { it.circleId == circleId && it.memberNodeId == memberNodeId }.maxByOrNull { it.originSequence }
        override suspend fun statusHistory(circleId: String, memberNodeId: String) = statusValues.values.filter { it.circleId == circleId && it.memberNodeId == memberNodeId }
    }
    private class MemoryPackets : PacketRepository {
        val values = linkedMapOf<String, PacketEntity>(); var sequence = 0L
        override suspend fun insert(packet: PacketEntity) = values.putIfAbsent(packet.packetId, packet) == null
        override suspend fun find(packetId: String) = values[packetId]
        override suspend fun inventoryIds() = values.values.filter { it.relayEligible }.map { it.packetId }
        override suspend fun findAll(packetIds: List<String>) = packetIds.mapNotNull(values::get)
        override suspend fun nextSequence() = ++sequence
        override suspend fun markProjected(packetId: String) = values[packetId]?.let { values[packetId] = it.copy(projectionState = ProjectionState.PROJECTED); true } ?: false
        override suspend fun markSuppressed(packetId: String) = values[packetId]?.let { values[packetId] = it.copy(projectionState = ProjectionState.SUPPRESSED); true } ?: false
        override suspend fun markRelayed(packetId: String, peerId: String) = Unit
        override suspend fun supersede(supersessionKey: String, keepPacketId: String) {
            values.replaceAll { _, packet ->
                if (packet.supersessionKey == supersessionKey && packet.packetId != keepPacketId) packet.copy(relayEligible = false)
                else packet
            }
        }
        override suspend fun resolve(packetId: String) {
            values[packetId]?.let { values[packetId] = it.copy(relayEligible = false) }
        }
        override suspend fun cleanup() = Unit
    }
    private class MemoryConversations : ConversationRepository {
        val values = linkedMapOf<String, ConversationMessageEntity>()
        override fun observeMessages(): Flow<List<ConversationMessageEntity>> = MutableStateFlow(values.values.toList())
        override suspend fun insert(message: ConversationMessageEntity) = values.putIfAbsent(message.messageId, message) == null
        override suspend fun find(messageId: String) = values[messageId]
        override suspend fun markRelayed(messageId: String) = Unit
        override suspend fun cleanup() = Unit
    }
    private class MemoryContacts : ContactRepository {
        val values = linkedMapOf<String, ContactEntity>()
        override fun observeContacts(): Flow<List<ContactEntity>> = MutableStateFlow(values.values.toList())
        override suspend fun find(nodeId: String) = values[nodeId]
        override suspend fun upsert(contact: ContactEntity) { values[contact.nodeId] = contact }
        override suspend fun delete(nodeId: String) { values.remove(nodeId) }
        override suspend fun deleteExpiredPending(now: Long) = Unit
    }
    private class MemoryReceipts : ReceiptRepository {
        val values = linkedMapOf<Pair<String, String>, MessageReceiptEntity>()
        override suspend fun find(messageId: String, recipientNodeId: String) = values[messageId to recipientNodeId]
        override suspend fun insert(receipt: MessageReceiptEntity) = values.putIfAbsent(receipt.messageId to receipt.recipientNodeId, receipt) == null
    }
    private class MemoryPeers : PeerRepository { override suspend fun upsert(profile: NodeProfile) = Unit; override suspend fun find(nodeId: String): PeerEntity? = null }
    private class JvmSigner : IdentitySigner {
        private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        override val publicKey = pair.public.encoded
        override val nodeId = MessageDigest.getInstance("SHA-256").digest(publicKey).joinToString("") { "%02x".format(it) }.take(32)
        override val fingerprint = nodeId.take(16).chunked(4).joinToString("-")
        override fun sign(payload: ByteArray) = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(payload); sign() }
        override fun verify(payload: ByteArray, signature: ByteArray, publicKey: ByteArray) = runCatching {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey)); Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(payload); verify(signature) }
        }.getOrDefault(false)
    }
}
