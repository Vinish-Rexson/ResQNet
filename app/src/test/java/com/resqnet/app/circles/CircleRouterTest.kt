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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        val members = listOf(
            CircleSnapshotMember(owner.signer.nodeId, CircleMemberRole.OWNER),
            CircleSnapshotMember(member.signer.nodeId, CircleMemberRole.MEMBER),
        )
        owner.router.ingest(owner.signed(snapshot(
            owner, circleId, 1, "Family", listOf(members.first()), clock.now,
        )), owner.signer.nodeId)
        member.expectMembership(circleId, "Family", owner, clock.now)
        val text = owner.signed(circleText(owner, circleId, 2, "Before snapshot", clock.now))
        assertTrue(member.router.ingest(text, owner.signer.nodeId) is IngestResult.StoredOnly)
        assertEquals(0, member.circles.messages(circleId).size)

        val snapshot = owner.signed(snapshot(owner, circleId, 2, "Family", members, clock.now))
        owner.router.ingest(snapshot, owner.signer.nodeId)
        assertTrue(member.router.ingest(snapshot, owner.signer.nodeId) is IngestResult.Projected)
        assertEquals(listOf("Before snapshot"), member.circles.messages(circleId).map { it.text })
        assertTrue(member.router.ingest(snapshot, owner.signer.nodeId) is IngestResult.Duplicate)
        assertEquals(1, member.circles.messages(circleId).size)

        val forged = mallory.signed(snapshot(mallory, circleId, 3, "Hacked", members, clock.now))
        assertTrue(member.router.ingest(forged, mallory.signer.nodeId) is IngestResult.StoredOnly)
        assertEquals(2L, member.circles.latestSnapshot(circleId)!!.membershipVersion)

        val duplicateOwner = owner.signed(snapshot(owner, circleId, 3, "Bad", listOf(
            CircleSnapshotMember(owner.signer.nodeId, CircleMemberRole.OWNER),
            CircleSnapshotMember(owner.signer.nodeId, CircleMemberRole.MEMBER),
        ), clock.now))
        assertTrue(member.router.ingest(duplicateOwner, owner.signer.nodeId) is IngestResult.StoredOnly)
        assertEquals(2L, member.circles.latestSnapshot(circleId)!!.membershipVersion)

        val gap = owner.signed(snapshot(owner, circleId, 5, "Family", members, clock.now))
        assertTrue(member.router.ingest(gap, owner.signer.nodeId) is IngestResult.Projected)
        assertEquals(5L, member.circles.latestSnapshot(circleId)!!.membershipVersion)

        val nonmemberText = mallory.signed(circleText(mallory, circleId, 5, "Invisible", clock.now))
        assertTrue(member.router.ingest(nonmemberText, mallory.signer.nodeId) is IngestResult.StoredOnly)
        assertEquals(listOf("Before snapshot"), member.circles.messages(circleId).map { it.text })

        val unissuedAccept = mallory.signed(PayloadV2(
            UUID.randomUUID(), PacketKind.CIRCLE_INVITE_ACCEPT, Audience.DirectNode(owner.signer.nodeId),
            mallory.signer.nodeId, mallory.name, 8, clock.now, Long.MAX_VALUE,
            RelayPolicy.DURABLE_UNTIL_RESOLVED,
            CircleInviteAcceptBody(UUID.randomUUID().toString(), circleId),
        ))
        assertTrue(owner.router.ingest(unissuedAccept, mallory.signer.nodeId) is IngestResult.StoredOnly)
        assertEquals(2L, owner.circles.latestSnapshot(circleId)!!.membershipVersion)
        val regression = owner.signed(snapshot(owner, circleId, 4, "Old", members, clock.now))
        assertTrue(member.router.ingest(regression, owner.signer.nodeId) is IngestResult.StoredOnly)
        assertEquals(5L, member.circles.latestSnapshot(circleId)!!.membershipVersion)
    }

    @Test fun circleTextReceiptsUseReferencedVersionAndRemovalSuppressesDelayedTextDespiteContactChanges() = runTest {
        val clock = TestClock(40_000L)
        val owner = TestNode("Owner", clock)
        val bob = TestNode("Bob", clock)
        val carol = TestNode("Carol", clock)
        val relay = TestNode("Relay", clock)
        val circleId = UUID.randomUUID().toString()
        establishCircle(owner, listOf(bob, carol), circleId, "Family", clock.now)
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
        val afterContactRemoval = owner.signed(circleText(owner, circleId, 2, "Still authorized", clock.now))
        assertTrue(bob.router.ingest(afterContactRemoval, owner.signer.nodeId) is IngestResult.Projected)

        val forgedReceipt = relay.signed(receipt(relay, owner.signer.nodeId, outgoing.messageId, circleId, 2, clock.now))
        assertTrue(owner.router.ingest(forgedReceipt, relay.signer.nodeId) is IngestResult.StoredOnly)
        assertEquals(CircleDeliveryProgress(2, 2), owner.router.circleDeliveryProgress(outgoing.messageId))

        val v3 = listOf(
            CircleSnapshotMember(owner.signer.nodeId, CircleMemberRole.OWNER),
            CircleSnapshotMember(carol.signer.nodeId, CircleMemberRole.MEMBER),
        )
        bob.router.ingest(owner.signed(snapshot(owner, circleId, 3, "Family", v3, clock.now)), owner.signer.nodeId)
        assertEquals(CircleLocalState.ARCHIVED_REMOVED, bob.circles.circle(circleId)!!.localState)
        val delayed = owner.signed(circleText(owner, circleId, 2, "Delayed", clock.now))
        assertTrue(bob.router.ingest(delayed, owner.signer.nodeId) is IngestResult.StoredOnly)
        assertEquals(listOf("Check in", "Still authorized"), bob.circles.messages(circleId).map { it.text })
    }

    @Test fun statusIsSelfAuthoredSequenceOrderedAndDerivesFreshnessWithoutLosingNeedHelp() = runTest {
        val clock = TestClock(50_000L)
        val owner = TestNode("Owner", clock)
        val member = TestNode("Member", clock)
        val circleId = UUID.randomUUID().toString()
        establishCircle(owner, listOf(member), circleId, "Family", clock.now)
        member.router.updateCircleStatus(circleId, SafetyStatus.SAFE, null)
        val needHelp = member.router.updateCircleStatus(circleId, SafetyStatus.NEED_HELP, "Need insulin")
        assertEquals(SafetyStatus.NEED_HELP, member.router.effectiveCircleStatus(circleId, member.signer.nodeId)!!.effectiveStatus)

        val forgedSubject = owner.signed(status(owner, circleId, 2, member.signer.nodeId, SafetyStatus.SAFE, null, 99, clock.now))
        assertTrue(member.router.ingest(forgedSubject, owner.signer.nodeId) is IngestResult.StoredOnly)
        val older = member.signed(status(member, circleId, 2, member.signer.nodeId, SafetyStatus.SAFE, null, needHelp.originSequence - 1, clock.now + 1))
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
        establishCircle(owner, listOf(member, other), circleId, "Family", clock.now)

        val leavePacketId = member.router.leaveCircle(circleId)
        assertEquals(leavePacketId, member.router.leaveCircle(circleId))
        assertEquals(CircleLocalState.LEAVE_PENDING, member.circles.circle(circleId)!!.localState)
        assertTrue(runCatching { member.router.createCircleMessage(circleId, "Blocked") }.exceptionOrNull() is IllegalArgumentException)

        owner.router.renameCircle(circleId, "Renamed")
        member.router.ingest(owner.onlySnapshot(3), owner.signer.nodeId)
        assertEquals(CircleLocalState.LEAVE_PENDING, member.circles.circle(circleId)!!.localState)
        member.router.ingest(owner.signed(circleText(owner, circleId, 3, "Suppressed", clock.now)), owner.signer.nodeId)
        assertEquals(emptyList<String>(), member.circles.messages(circleId).map { it.text })

        owner.router.ingest(member.envelope(leavePacketId), member.signer.nodeId)
        assertFalse(owner.router.inventory().contains(leavePacketId))
        member.router.ingest(owner.onlySnapshot(4), owner.signer.nodeId)
        assertEquals(CircleLocalState.ARCHIVED_REMOVED, member.circles.circle(circleId)!!.localState)

        owner.router.dissolveCircle(circleId)
        other.router.ingest(owner.onlySnapshot(5), owner.signer.nodeId)
        assertEquals(CircleLocalState.ARCHIVED_DISSOLVED, other.circles.circle(circleId)!!.localState)
    }

    @Test fun duplicateProjectedCircleTextRecoversDeterministicReceiptAfterClaimInterruption() = runTest {
        val clock = TestClock(70_000L)
        val owner = TestNode("Owner", clock)
        val member = TestNode("Member", clock)
        val circleId = UUID.randomUUID().toString()
        establishCircle(owner, listOf(member), circleId, "Family", clock.now)
        val text = owner.signed(circleText(owner, circleId, 2, "Recover receipt", clock.now))
        member.circles.cancelAfterNextReceiptInsert = true

        assertTrue(runCatching { member.router.ingest(text, owner.signer.nodeId) }.exceptionOrNull() is CancellationException)
        assertEquals(1, member.circles.receiptValues.size)
        assertEquals(0, member.packetStore.values.values.count { it.kind == PacketKind.DELIVERY_RECEIPT })

        assertTrue(member.router.ingest(text, owner.signer.nodeId) is IngestResult.Duplicate)
        assertEquals(1, member.circles.receiptValues.size)
        assertEquals(1, member.packetStore.values.values.count { it.kind == PacketKind.DELIVERY_RECEIPT })
    }

    @Test fun invitedAndAcceptancePendingStatesSurviveUnrelatedOwnerSnapshots() = runTest {
        val clock = TestClock(80_000L)
        val owner = TestNode("Owner", clock)
        val member = TestNode("Member", clock)
        owner.trust(member); member.trust(owner)
        val circle = owner.router.createCircle("Family")
        val invite = owner.router.inviteToCircle(circle.circleId, member.signer.nodeId)
        member.router.ingest(owner.envelope(invite.packetId), owner.signer.nodeId)

        owner.router.renameCircle(circle.circleId, "Before acceptance")
        member.router.ingest(owner.onlySnapshot(2), owner.signer.nodeId)
        assertEquals(CircleLocalState.INVITED, member.circles.circle(circle.circleId)!!.localState)

        val acceptancePacketId = member.router.acceptCircleInvite(invite.inviteId)
        owner.router.renameCircle(circle.circleId, "While acceptance is pending")
        member.router.ingest(owner.onlySnapshot(3), owner.signer.nodeId)
        assertEquals(CircleLocalState.ACCEPTANCE_PENDING, member.circles.circle(circle.circleId)!!.localState)
        assertTrue(member.router.inventory().contains(acceptancePacketId))
    }

    @Test fun interruptedSnapshotApplicationRetriesWithoutPartialState() = runTest {
        val clock = TestClock(90_000L)
        val owner = TestNode("Owner", clock)
        val circleId = UUID.randomUUID().toString()
        val members = listOf(
            CircleSnapshotMember(owner.signer.nodeId, CircleMemberRole.OWNER),
        )
        val envelope = owner.signed(snapshot(owner, circleId, 1, "Family", members, clock.now))
        owner.circles.cancelNextSnapshotApplication = true

        assertTrue(runCatching { owner.router.ingest(envelope, owner.signer.nodeId) }.exceptionOrNull() is CancellationException)
        assertNull(owner.circles.circle(circleId))
        assertNull(owner.circles.snapshot(circleId, 1))

        assertTrue(owner.router.ingest(envelope, owner.signer.nodeId) is IngestResult.Projected)
        assertEquals(1L, owner.circles.circle(circleId)!!.currentMembershipVersion)
    }

    @Test fun acceptanceRecoversAfterDeterministicPacketInsertInterruption() = runTest {
        val clock = TestClock(95_000L)
        val owner = TestNode("Owner", clock)
        val member = TestNode("Member", clock)
        owner.trust(member); member.trust(owner)
        val circle = owner.router.createCircle("Family")
        val invite = owner.router.inviteToCircle(circle.circleId, member.signer.nodeId)
        member.router.ingest(owner.envelope(invite.packetId), owner.signer.nodeId)
        member.packetStore.cancelAfterNextInsertKind = PacketKind.CIRCLE_INVITE_ACCEPT

        assertTrue(runCatching {
            member.router.acceptCircleInvite(invite.inviteId)
        }.exceptionOrNull() is CancellationException)
        val prepared = member.circles.invitation(invite.inviteId)!!
        assertEquals(CircleInvitationState.ACCEPTANCE_PENDING, prepared.state)
        val acceptancePacketId = requireNotNull(prepared.acceptancePacketId)

        assertEquals(acceptancePacketId, member.router.acceptCircleInvite(invite.inviteId))
        assertEquals(1, member.packetStore.values.values.count { it.kind == PacketKind.CIRCLE_INVITE_ACCEPT })
        assertTrue(member.router.inventory().contains(acceptancePacketId))
    }

    @Test fun concurrentSnapshotsCannotRegressCurrentVersionOrRelayInventory() = runTest {
        val clock = TestClock(100_000L)
        val owner = TestNode("Owner", clock)
        val member = TestNode("Member", clock)
        val circleId = UUID.randomUUID().toString()
        val members = establishCircle(owner, listOf(member), circleId, "Family", clock.now)
        val v3 = owner.signed(snapshot(owner, circleId, 3, "Version three", members, clock.now))
        val v4 = owner.signed(snapshot(owner, circleId, 4, "Version four", members, clock.now))
        member.circles.pauseSnapshotVersion = 3

        val slowV3 = async { member.router.ingest(v3, owner.signer.nodeId) }
        member.circles.snapshotApplicationStarted.await()
        val fastV4 = async { member.router.ingest(v4, owner.signer.nodeId) }
        fastV4.await()
        member.circles.continueSnapshotApplication.complete(Unit)
        slowV3.await()

        assertEquals(4L, member.circles.circle(circleId)!!.currentMembershipVersion)
        assertEquals("Version four", member.circles.circle(circleId)!!.name)
        val relayableVersions = member.router.inventory().map(member::payload).mapNotNull {
            (it.body as? CircleMembershipSnapshotBody)?.membershipVersion
        }
        assertEquals(listOf(4L), relayableVersions)
    }

    @Test fun firstSeenOrUnacceptedMemberSnapshotsCannotActivateLocalUser() = runTest {
        val clock = TestClock(105_000L)
        val owner = TestNode("Owner", clock)
        val member = TestNode("Member", clock)
        val circleId = UUID.randomUUID().toString()
        val members = listOf(
            CircleSnapshotMember(owner.signer.nodeId, CircleMemberRole.OWNER),
            CircleSnapshotMember(member.signer.nodeId, CircleMemberRole.MEMBER),
        )

        val malformedV1 = owner.signed(snapshot(owner, circleId, 1, "Family", members, clock.now))
        assertTrue(member.router.ingest(malformedV1, owner.signer.nodeId) is IngestResult.StoredOnly)
        assertNull(member.circles.circle(circleId))
        val uninvitedV2 = owner.signed(snapshot(owner, circleId, 2, "Family", members, clock.now))
        assertTrue(member.router.ingest(uninvitedV2, owner.signer.nodeId) is IngestResult.StoredOnly)
        assertNull(member.circles.circle(circleId))

        owner.trust(member); member.trust(owner)
        val circle = owner.router.createCircle("Invited family")
        val invite = owner.router.inviteToCircle(circle.circleId, member.signer.nodeId)
        member.router.ingest(owner.envelope(invite.packetId), owner.signer.nodeId)
        val prematureAddition = owner.signed(snapshot(owner, circle.circleId, 2, "Invited family", members.map {
            if (it.nodeId == owner.signer.nodeId) it else CircleSnapshotMember(member.signer.nodeId, CircleMemberRole.MEMBER)
        }, clock.now))
        member.router.ingest(prematureAddition, owner.signer.nodeId)
        assertEquals(CircleLocalState.INVITED, member.circles.circle(circle.circleId)!!.localState)
    }

    @Test fun lateOlderSnapshotReplaysPendingExactVersionContent() = runTest {
        val clock = TestClock(107_000L)
        val owner = TestNode("Owner", clock)
        val member = TestNode("Member", clock)
        val circleId = UUID.randomUUID().toString()
        val members = listOf(
            CircleSnapshotMember(owner.signer.nodeId, CircleMemberRole.OWNER),
            CircleSnapshotMember(member.signer.nodeId, CircleMemberRole.MEMBER),
        )
        establishCircle(owner, listOf(member), circleId, "Family", clock.now)
        member.router.ingest(owner.signed(snapshot(owner, circleId, 4, "Family", members, clock.now)), owner.signer.nodeId)
        val delayedText = owner.signed(circleText(owner, circleId, 3, "Version three", clock.now))
        assertTrue(member.router.ingest(delayedText, owner.signer.nodeId) is IngestResult.StoredOnly)

        val lateV3 = owner.signed(snapshot(owner, circleId, 3, "Family", members, clock.now))
        assertTrue(member.router.ingest(lateV3, owner.signer.nodeId) is IngestResult.StoredOnly)
        assertEquals(listOf("Version three"), member.circles.messages(circleId).map { it.text })
        assertEquals(emptyList<PendingCirclePacketEntity>(), member.circles.pending(circleId))
    }

    @Test fun removalWinsAgainstContentProjectionThatHasNotCommitted() = runTest {
        val clock = TestClock(110_000L)
        val owner = TestNode("Owner", clock)
        val member = TestNode("Member", clock)
        val circleId = UUID.randomUUID().toString()
        establishCircle(owner, listOf(member), circleId, "Family", clock.now)
        member.circles.pauseNextMessageProjection = true
        val text = owner.signed(circleText(owner, circleId, 2, "Must stay hidden", clock.now))

        val projection = async { member.router.ingest(text, owner.signer.nodeId) }
        member.circles.messageProjectionStarted.await()
        val removal = owner.signed(snapshot(owner, circleId, 3, "Family", listOf(
            CircleSnapshotMember(owner.signer.nodeId, CircleMemberRole.OWNER),
        ), clock.now))
        member.router.ingest(removal, owner.signer.nodeId)
        member.circles.continueMessageProjection.complete(Unit)
        projection.await()

        assertEquals(CircleLocalState.ARCHIVED_REMOVED, member.circles.circle(circleId)!!.localState)
        assertEquals(emptyList<CircleMessageEntity>(), member.circles.messages(circleId))
    }

    @Test fun dissolutionWinsAgainstStatusProjectionThatHasNotCommitted() = runTest {
        val clock = TestClock(115_000L)
        val owner = TestNode("Owner", clock)
        val member = TestNode("Member", clock)
        val circleId = UUID.randomUUID().toString()
        val members = establishCircle(owner, listOf(member), circleId, "Family", clock.now)
        member.circles.pauseNextStatusProjection = true
        val safe = owner.signed(status(
            owner, circleId, 2, owner.signer.nodeId, SafetyStatus.SAFE, null, 8, clock.now,
        ))

        val projection = async { member.router.ingest(safe, owner.signer.nodeId) }
        member.circles.statusProjectionStarted.await()
        member.router.ingest(
            owner.signed(snapshot(owner, circleId, 3, "Family", members, clock.now, dissolved = true)),
            owner.signer.nodeId,
        )
        member.circles.continueStatusProjection.complete(Unit)
        projection.await()

        assertEquals(CircleLocalState.ARCHIVED_DISSOLVED, member.circles.circle(circleId)!!.localState)
        assertEquals(emptyList<CircleStatusEventEntity>(), member.circles.statusHistory(circleId, owner.signer.nodeId))
    }

    @Test fun dissolutionIsTerminalEvenForHigherOwnerSnapshots() = runTest {
        val clock = TestClock(120_000L)
        val owner = TestNode("Owner", clock)
        val member = TestNode("Member", clock)
        val circleId = UUID.randomUUID().toString()
        val members = establishCircle(owner, listOf(member), circleId, "Family", clock.now)
        val dissolved = owner.signed(snapshot(owner, circleId, 3, "Family", members, clock.now, dissolved = true))
        member.router.ingest(dissolved, owner.signer.nodeId)
        val attemptedRevival = owner.signed(snapshot(owner, circleId, 4, "Revived", members, clock.now))
        assertTrue(member.router.ingest(attemptedRevival, owner.signer.nodeId) is IngestResult.StoredOnly)

        val terminal = member.circles.circle(circleId)!!
        assertEquals(CircleLocalState.ARCHIVED_DISSOLVED, terminal.localState)
        assertEquals(3L, terminal.currentMembershipVersion)
        assertEquals("Family", terminal.name)
        assertNull(member.circles.snapshot(circleId, 4))
        assertTrue(member.router.ingest(dissolved, owner.signer.nodeId) is IngestResult.Duplicate)
        val relayableVersions = member.router.inventory().map(member::payload).mapNotNull {
            (it.body as? CircleMembershipSnapshotBody)?.membershipVersion
        }
        assertEquals(listOf(3L), relayableVersions)
    }

    @Test fun equalStatusSequencesHaveOneAtomicWinner() = runTest {
        val clock = TestClock(130_000L)
        val owner = TestNode("Owner", clock)
        val member = TestNode("Member", clock)
        val circleId = UUID.randomUUID().toString()
        establishCircle(owner, listOf(member), circleId, "Family", clock.now)
        val safe = member.signed(status(member, circleId, 2, member.signer.nodeId, SafetyStatus.SAFE, null, 7, clock.now))
        val help = member.signed(status(member, circleId, 2, member.signer.nodeId, SafetyStatus.NEED_HELP, "Help", 7, clock.now + 1))
        owner.circles.pauseNextStatusProjection = true

        val slowSafe = async { owner.router.ingest(safe, member.signer.nodeId) }
        owner.circles.statusProjectionStarted.await()
        val fastHelp = async { owner.router.ingest(help, member.signer.nodeId) }
        fastHelp.await()
        owner.circles.continueStatusProjection.complete(Unit)
        slowSafe.await()

        val history = owner.circles.statusHistory(circleId, member.signer.nodeId)
        assertEquals(1, history.size)
        assertEquals(SafetyStatus.NEED_HELP, history.single().status)
    }

    @Test fun staleLeaveFromPriorMembershipCannotRemoveRejoinedMember() = runTest {
        val clock = TestClock(140_000L)
        val owner = TestNode("Owner", clock)
        val member = TestNode("Member", clock)
        val circleId = UUID.randomUUID().toString()
        val members = establishCircle(owner, listOf(member), circleId, "Family", clock.now)
        val staleLeaveId = member.router.leaveCircle(circleId)
        assertEquals(2L, (member.payload(staleLeaveId).body as CircleLeaveRequestBody).membershipVersion)

        owner.router.removeCircleMember(circleId, member.signer.nodeId)
        val rejoined = owner.signed(snapshot(owner, circleId, 4, "Family", members, clock.now))
        owner.router.ingest(rejoined, owner.signer.nodeId)
        assertTrue(owner.router.ingest(member.envelope(staleLeaveId), member.signer.nodeId) is IngestResult.StoredOnly)

        assertEquals(4L, owner.circles.circle(circleId)!!.currentMembershipVersion)
        assertTrue(owner.circles.members(circleId, 4).any { it.nodeId == member.signer.nodeId })
    }

    @Test fun reflectedOwnCircleTextNeverCreatesSelfReceipt() = runTest {
        val clock = TestClock(150_000L)
        val owner = TestNode("Owner", clock)
        val circle = owner.router.createCircle("Family")
        val message = owner.router.createCircleMessage(circle.circleId, "Hello")

        assertTrue(owner.router.ingest(owner.envelope(message.messageId), owner.signer.nodeId) is IngestResult.Duplicate)
        assertEquals(0, owner.packetStore.values.values.count { it.kind == PacketKind.DELIVERY_RECEIPT })
        assertEquals(0, owner.circles.receiptValues.size)
    }

    private suspend fun establishCircle(
        owner: TestNode,
        members: List<TestNode>,
        circleId: String,
        name: String,
        now: Long,
    ): List<CircleSnapshotMember> {
        val ownerOnly = listOf(CircleSnapshotMember(owner.signer.nodeId, CircleMemberRole.OWNER))
        owner.router.ingest(
            owner.signed(snapshot(owner, circleId, 1, name, ownerOnly, now)),
            owner.signer.nodeId,
        )
        members.forEach { it.expectMembership(circleId, name, owner, now) }
        val activeMembers = ownerOnly + members.map {
            CircleSnapshotMember(it.signer.nodeId, CircleMemberRole.MEMBER)
        }
        val activeSnapshot = owner.signed(snapshot(owner, circleId, 2, name, activeMembers, now))
        owner.router.ingest(activeSnapshot, owner.signer.nodeId)
        members.forEach { it.router.ingest(activeSnapshot, owner.signer.nodeId) }
        return activeMembers
    }

    private fun snapshot(
        origin: TestNode,
        circleId: String,
        version: Long,
        name: String,
        members: List<CircleSnapshotMember>,
        now: Long,
        dissolved: Boolean = false,
    ) = PayloadV2(
        UUID.randomUUID(), PacketKind.CIRCLE_MEMBERSHIP_SNAPSHOT, Audience.Circle(circleId), origin.signer.nodeId,
        origin.name, 1, now, Long.MAX_VALUE, RelayPolicy.DURABLE_UNTIL_SUPERSEDED,
        CircleMembershipSnapshotBody(
            circleId, version, name, members.first { it.role == CircleMemberRole.OWNER }.nodeId, members, dissolved,
        ),
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
        val circles = MemoryCircles(packetStore)
        val router = MessageRouter(packetStore, conversations, MemoryPeers(), signer, { name }, { clock.now }, contacts, MemoryReceipts(), circles = circles)
        suspend fun trust(other: TestNode) = contacts.upsert(ContactEntity(other.signer.nodeId, other.name, other.signer.publicKey, other.signer.fingerprint, ContactState.TRUSTED, null, null, null, null, clock.now))
        suspend fun expectMembership(circleId: String, circleName: String, owner: TestNode, now: Long) {
            val inviteId = UUID.randomUUID().toString()
            val acceptancePacketId = circleAcceptancePacketId(inviteId, signer.nodeId)
            circles.upsertInvitation(CircleInvitationEntity(
                inviteId = inviteId,
                packetId = UUID.randomUUID().toString(),
                circleId = circleId,
                circleName = circleName,
                ownerNodeId = owner.signer.nodeId,
                targetNodeId = signer.nodeId,
                membershipVersion = 1,
                expiresAt = Long.MAX_VALUE,
                state = CircleInvitationState.ACCEPTANCE_PENDING,
                acceptancePacketId = acceptancePacketId,
                activeMemberPreviewEncoded = encodeCircleMembers(listOf(
                    CircleSnapshotMember(owner.signer.nodeId, CircleMemberRole.OWNER),
                )),
                createdAt = now,
                updatedAt = now,
            ))
            circles.upsertCircle(CircleEntity(
                circleId, circleName, owner.signer.nodeId, 1, CircleLocalState.ACCEPTANCE_PENDING,
                null, null, now, now,
            ))
        }
        fun envelope(id: String) = ProtocolCodec.decodeEnvelope(packetStore.values.getValue(id).rawEnvelope)
        fun payload(id: String) = ProtocolCodec.decodePayload(envelope(id).packet.payloadBytes)
        fun signed(payload: PayloadV2): RelayEnvelope { val bytes = ProtocolCodec.encodePayload(payload); return RelayEnvelope(SignedPacket(bytes, signer.sign(bytes), signer.publicKey)) }
        fun only(kind: PacketKind) = packetStore.values.values.single { it.kind == kind }.let { ProtocolCodec.decodeEnvelope(it.rawEnvelope) }
        fun onlySnapshot(version: Long) = packetStore.values.values.single {
            it.kind == PacketKind.CIRCLE_MEMBERSHIP_SNAPSHOT && (ProtocolCodec.decodePayload(ProtocolCodec.decodeEnvelope(it.rawEnvelope).packet.payloadBytes).body as CircleMembershipSnapshotBody).membershipVersion == version
        }.let { ProtocolCodec.decodeEnvelope(it.rawEnvelope) }
    }

    private class MemoryCircles(private val packets: MemoryPackets) : CircleRepository {
        private val transaction = Mutex()
        val circleValues = linkedMapOf<String, CircleEntity>(); val inviteValues = linkedMapOf<String, CircleInvitationEntity>()
        val snapshotValues = linkedMapOf<Pair<String, Long>, CircleSnapshotEntity>(); val memberValues = linkedMapOf<Pair<String, Long>, List<CircleMemberEntity>>()
        val messageValues = linkedMapOf<String, CircleMessageEntity>(); val pendingValues = linkedMapOf<String, PendingCirclePacketEntity>()
        val receiptValues = linkedMapOf<Pair<String, String>, CircleMessageReceiptEntity>(); val statusValues = linkedMapOf<String, CircleStatusEventEntity>()
        var cancelAfterNextReceiptInsert = false
        var cancelNextSnapshotApplication = false
        var pauseSnapshotVersion: Long? = null
        val snapshotApplicationStarted = CompletableDeferred<Unit>()
        val continueSnapshotApplication = CompletableDeferred<Unit>()
        var pauseNextMessageProjection = false
        val messageProjectionStarted = CompletableDeferred<Unit>()
        val continueMessageProjection = CompletableDeferred<Unit>()
        var pauseNextStatusProjection = false
        val statusProjectionStarted = CompletableDeferred<Unit>()
        val continueStatusProjection = CompletableDeferred<Unit>()
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
            memberValues[snapshot.circleId to snapshot.membershipVersion] = members
            return true
        }
        override suspend fun applySnapshot(application: CircleSnapshotApplication): CircleStoreResult {
            if (pauseSnapshotVersion == application.snapshot.membershipVersion) {
                pauseSnapshotVersion = null
                snapshotApplicationStarted.complete(Unit)
                continueSnapshotApplication.await()
            }
            if (cancelNextSnapshotApplication) {
                cancelNextSnapshotApplication = false
                throw CancellationException("Snapshot application interrupted")
            }
            return transaction.withLock {
                val snapshot = application.snapshot
                val existingCircle = circleValues[snapshot.circleId]
                val currentSnapshot = snapshotValues.filterKeys { it.first == snapshot.circleId }
                    .values.maxByOrNull { it.membershipVersion }
                if (existingCircle?.ownerNodeId?.let { it != snapshot.ownerNodeId } == true) {
                    packets.markSuppressed(snapshot.packetId)
                    return@withLock CircleStoreResult.SUPPRESSED
                }
                if (existingCircle?.localState == CircleLocalState.ARCHIVED_DISSOLVED) {
                    if (currentSnapshot?.packetId == snapshot.packetId && currentSnapshot.dissolved) {
                        packets.supersede("circle-snapshot:${snapshot.circleId}", snapshot.packetId)
                        packets.markProjected(snapshot.packetId)
                        return@withLock CircleStoreResult.PROJECTED
                    }
                    packets.resolve(snapshot.packetId)
                    return@withLock CircleStoreResult.STORED_ONLY
                }
                val localInSnapshot = application.members.any { it.nodeId == application.localNodeId }
                val createsOwnerCircle = existingCircle == null && snapshot.membershipVersion == 1L &&
                    snapshot.ownerNodeId == application.localNodeId && application.members.size == 1 && localInSnapshot
                if (existingCircle == null && !createsOwnerCircle) return@withLock CircleStoreResult.STORED_ONLY
                val currentVersion = currentSnapshot?.membershipVersion ?: 0L
                val existingAtVersion = snapshotValues[snapshot.circleId to snapshot.membershipVersion]
                val retrying = currentSnapshot?.packetId == snapshot.packetId &&
                    currentVersion == snapshot.membershipVersion
                if (application.expectedPreviousVersion?.let { it != currentVersion } == true ||
                    (!retrying && existingAtVersion != null)
                ) {
                    packets.resolve(snapshot.packetId)
                    return@withLock CircleStoreResult.STORED_ONLY
                }
                val continuity = application.continuousMembership
                if (continuity != null) {
                    val history = snapshotValues.values.filter {
                        it.circleId == snapshot.circleId &&
                            it.membershipVersion in continuity.sinceVersion..currentVersion
                    }
                    if (continuity.sinceVersion > currentVersion ||
                        memberValues[snapshot.circleId to continuity.sinceVersion].orEmpty()
                            .none { it.nodeId == continuity.nodeId } ||
                        memberValues[snapshot.circleId to currentVersion].orEmpty()
                            .none { it.nodeId == continuity.nodeId } ||
                        history.any { historical ->
                            memberValues[historical.circleId to historical.membershipVersion].orEmpty()
                                .none { it.nodeId == continuity.nodeId }
                        }
                    ) {
                        packets.resolve(snapshot.packetId)
                        return@withLock CircleStoreResult.STORED_ONLY
                    }
                }
                if (!retrying && snapshot.membershipVersion <= currentVersion) {
                    if (existingAtVersion == null) {
                        snapshotValues[snapshot.circleId to snapshot.membershipVersion] = snapshot
                        memberValues[snapshot.circleId to snapshot.membershipVersion] = application.members
                    }
                    packets.resolve(snapshot.packetId)
                    return@withLock CircleStoreResult.STORED_ONLY
                }
                if (!retrying) {
                    snapshotValues[snapshot.circleId to snapshot.membershipVersion] = snapshot
                    memberValues[snapshot.circleId to snapshot.membershipVersion] = application.members
                }
                val hasPendingAcceptance = existingCircle?.localState == CircleLocalState.ACCEPTANCE_PENDING &&
                    inviteValues.values.any {
                        it.circleId == snapshot.circleId && it.targetNodeId == application.localNodeId &&
                            it.state == CircleInvitationState.ACCEPTANCE_PENDING
                    }
                val localState = when {
                    snapshot.dissolved -> CircleLocalState.ARCHIVED_DISSOLVED
                    existingCircle?.localState == CircleLocalState.ARCHIVED_REMOVED -> CircleLocalState.ARCHIVED_REMOVED
                    snapshot.ownerNodeId == application.localNodeId -> CircleLocalState.OWNER_ACTIVE
                    existingCircle?.localState == CircleLocalState.LEAVE_PENDING && localInSnapshot -> CircleLocalState.LEAVE_PENDING
                    existingCircle?.localState == CircleLocalState.INVITED -> CircleLocalState.INVITED
                    existingCircle?.localState == CircleLocalState.ACCEPTANCE_PENDING && !hasPendingAcceptance ->
                        CircleLocalState.ACCEPTANCE_PENDING
                    localInSnapshot -> CircleLocalState.ACTIVE
                    existingCircle?.localState in setOf(CircleLocalState.INVITED, CircleLocalState.ACCEPTANCE_PENDING) ->
                        requireNotNull(existingCircle).localState
                    existingCircle != null -> CircleLocalState.ARCHIVED_REMOVED
                    else -> return@withLock CircleStoreResult.STORED_ONLY
                }
                circleValues[snapshot.circleId] = CircleEntity(
                    snapshot.circleId,
                    snapshot.circleName,
                    snapshot.ownerNodeId,
                    snapshot.membershipVersion,
                    localState,
                    existingCircle?.leaveRequestPacketId?.takeIf { localState == CircleLocalState.LEAVE_PENDING },
                    existingCircle?.leaveRequestMembershipVersion?.takeIf { localState == CircleLocalState.LEAVE_PENDING },
                    existingCircle?.createdAt ?: snapshot.createdAt,
                    application.now,
                )
                if (localInSnapshot || snapshot.dissolved) {
                    inviteValues.keys.toList().forEach { inviteId ->
                        val invitation = inviteValues.getValue(inviteId)
                        if (invitation.circleId == snapshot.circleId &&
                            invitation.targetNodeId == application.localNodeId &&
                            invitation.state == CircleInvitationState.ACCEPTANCE_PENDING
                        ) {
                            invitation.acceptancePacketId?.let { packets.resolve(it) }
                            inviteValues[inviteId] = invitation.copy(
                                state = if (snapshot.dissolved) CircleInvitationState.EXPIRED else CircleInvitationState.ACCEPTED,
                                updatedAt = application.now,
                            )
                        }
                    }
                }
                application.acceptedInviteId?.let { inviteId ->
                    inviteValues[inviteId]?.let {
                        inviteValues[inviteId] = it.copy(
                            state = CircleInvitationState.ACCEPTED,
                            updatedAt = application.now,
                        )
                    }
                }
                if (!localInSnapshot || snapshot.dissolved) {
                    existingCircle?.leaveRequestPacketId?.let { packets.resolve(it) }
                }
                application.resolvedControlPacketId?.let { packets.resolve(it) }
                packets.supersede("circle-snapshot:${snapshot.circleId}", snapshot.packetId)
                packets.markProjected(snapshot.packetId)
                CircleStoreResult.PROJECTED
            }
        }
        override suspend fun members(circleId: String, membershipVersion: Long) = memberValues[circleId to membershipVersion].orEmpty()
        override suspend fun projectMessage(message: CircleMessageEntity, localNodeId: String): CircleStoreResult {
            if (pauseNextMessageProjection) {
                pauseNextMessageProjection = false
                messageProjectionStarted.complete(Unit)
                continueMessageProjection.await()
            }
            return transaction.withLock {
                if (snapshotValues[message.circleId to message.membershipVersion] == null) {
                    pendingValues.putIfAbsent(
                        message.messageId,
                        PendingCirclePacketEntity(
                            message.messageId, message.circleId, message.membershipVersion, PacketKind.CIRCLE_TEXT,
                        ),
                    )
                    return@withLock CircleStoreResult.STORED_ONLY
                }
                val active = circleValues[message.circleId]?.localState in
                    setOf(CircleLocalState.OWNER_ACTIVE, CircleLocalState.ACTIVE)
                val members = memberValues[message.circleId to message.membershipVersion].orEmpty()
                if (!active || members.none { it.nodeId == message.originNodeId } || members.none { it.nodeId == localNodeId }) {
                    pendingValues.remove(message.messageId)
                    packets.markSuppressed(message.messageId)
                    return@withLock CircleStoreResult.SUPPRESSED
                }
                messageValues.putIfAbsent(message.messageId, message)
                pendingValues.remove(message.messageId)
                packets.markProjected(message.messageId)
                CircleStoreResult.PROJECTED
            }
        }
        override suspend fun message(messageId: String) = messageValues[messageId]
        override suspend fun messages(circleId: String) = messageValues.values.filter { it.circleId == circleId }
        override fun observeMessages(circleId: String) = kotlinx.coroutines.flow.flowOf(messageValues.values.filter { it.circleId == circleId })
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
        override suspend fun projectStatus(event: CircleStatusEventEntity, localNodeId: String): CircleStoreResult {
            if (pauseNextStatusProjection) {
                pauseNextStatusProjection = false
                statusProjectionStarted.complete(Unit)
                continueStatusProjection.await()
            }
            return transaction.withLock {
                if (snapshotValues[event.circleId to event.membershipVersion] == null) {
                    pendingValues.putIfAbsent(
                        event.packetId,
                        PendingCirclePacketEntity(
                            event.packetId, event.circleId, event.membershipVersion, PacketKind.CIRCLE_STATUS,
                        ),
                    )
                    return@withLock CircleStoreResult.STORED_ONLY
                }
                val active = circleValues[event.circleId]?.localState in
                    setOf(CircleLocalState.OWNER_ACTIVE, CircleLocalState.ACTIVE)
                val members = memberValues[event.circleId to event.membershipVersion].orEmpty()
                if (!active || members.none { it.nodeId == event.memberNodeId } || members.none { it.nodeId == localNodeId }) {
                    pendingValues.remove(event.packetId)
                    packets.markSuppressed(event.packetId)
                    return@withLock CircleStoreResult.SUPPRESSED
                }
                val latest = statusValues.values.filter {
                    it.circleId == event.circleId && it.memberNodeId == event.memberNodeId
                }.maxByOrNull { it.originSequence }
                if (latest == null || event.originSequence > latest.originSequence) {
                    statusValues.putIfAbsent(event.packetId, event)
                }
                pendingValues.remove(event.packetId)
                packets.markProjected(event.packetId)
                CircleStoreResult.PROJECTED
            }
        }
        override suspend fun latestStatus(circleId: String, memberNodeId: String) = statusValues.values.filter { it.circleId == circleId && it.memberNodeId == memberNodeId }.maxByOrNull { it.originSequence }
        override suspend fun statusHistory(circleId: String, memberNodeId: String) = statusValues.values.filter { it.circleId == circleId && it.memberNodeId == memberNodeId }
        override fun observeStatuses(circleId: String): Flow<List<CircleStatusEventEntity>> =
            MutableStateFlow(statusValues.values.filter { it.circleId == circleId })
        override fun observeLatestStatusPerMemberAllCircles(): Flow<List<CircleStatusEventEntity>> =
            MutableStateFlow(
                statusValues.values
                    .groupBy { it.circleId to it.memberNodeId }
                    .mapValues { (_, events) -> events.maxByOrNull { it.originSequence }!! }
                    .values
                    .toList()
            )
        override suspend fun prepareAcceptance(
            inviteId: String,
            localNodeId: String,
            now: Long,
        ): PreparedCircleAcceptance = transaction.withLock {
            val invitation = requireNotNull(inviteValues[inviteId]) { "Unknown Circle invitation" }
            require(invitation.targetNodeId == localNodeId &&
                invitation.state in setOf(CircleInvitationState.PENDING, CircleInvitationState.ACCEPTANCE_PENDING)
            ) { "Invitation is not active" }
            require(now < invitation.expiresAt) { "Invitation expired" }
            val packetId = invitation.acceptancePacketId ?: circleAcceptancePacketId(inviteId, localNodeId)
            if (invitation.state != CircleInvitationState.ACCEPTANCE_PENDING || invitation.acceptancePacketId == null) {
                inviteValues[inviteId] = invitation.copy(
                    state = CircleInvitationState.ACCEPTANCE_PENDING,
                    acceptancePacketId = packetId,
                    updatedAt = now,
                )
                circleValues[invitation.circleId]?.let { circle ->
                    circleValues[invitation.circleId] = circle.copy(
                        localState = CircleLocalState.ACCEPTANCE_PENDING,
                        updatedAt = now,
                    )
                }
            }
            PreparedCircleAcceptance(inviteId, invitation.circleId, invitation.ownerNodeId, packetId)
        }

        override suspend fun prepareLeave(circleId: String, localNodeId: String, now: Long): PreparedCircleLeave =
            transaction.withLock {
                val circle = requireNotNull(circleValues[circleId]) { "Unknown Circle" }
                if (circle.localState == CircleLocalState.LEAVE_PENDING) {
                    return@withLock PreparedCircleLeave(
                        circleId,
                        circle.ownerNodeId,
                        requireNotNull(circle.leaveRequestMembershipVersion),
                        requireNotNull(circle.leaveRequestPacketId),
                    )
                }
                require(circle.localState == CircleLocalState.ACTIVE && circle.ownerNodeId != localNodeId) {
                    "Circle is read-only"
                }
                val version = circle.currentMembershipVersion
                check(memberValues[circleId to version].orEmpty().any { it.nodeId == localNodeId })
                val packetId = circleLeavePacketId(circleId, localNodeId, version)
                circleValues[circleId] = circle.copy(
                    localState = CircleLocalState.LEAVE_PENDING,
                    leaveRequestPacketId = packetId,
                    leaveRequestMembershipVersion = version,
                    updatedAt = now,
                )
                PreparedCircleLeave(circleId, circle.ownerNodeId, version, packetId)
            }
    }
    private class MemoryPackets : PacketRepository {
        val values = linkedMapOf<String, PacketEntity>(); var sequence = 0L
        var cancelAfterNextInsertKind: PacketKind? = null
        override suspend fun insert(packet: PacketEntity): Boolean {
            val inserted = values.putIfAbsent(packet.packetId, packet) == null
            if (inserted && cancelAfterNextInsertKind == packet.kind) {
                cancelAfterNextInsertKind = null
                throw CancellationException("Packet insert interrupted")
            }
            return inserted
        }
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
    private class MemoryPeers : PeerRepository { override suspend fun upsert(profile: NodeProfile) = Unit; override suspend fun find(nodeId: String): PeerEntity? = null; override fun observePeers(): Flow<List<PeerEntity>> = kotlinx.coroutines.flow.flowOf(emptyList()) }
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
