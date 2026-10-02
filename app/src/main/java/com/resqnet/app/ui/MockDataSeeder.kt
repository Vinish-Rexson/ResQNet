package com.resqnet.app.ui

import com.resqnet.app.ResQNetApplication
import com.resqnet.app.circles.*
import com.resqnet.app.contacts.directConversationId
import com.resqnet.app.data.ContactEntity
import com.resqnet.app.data.ContactState
import com.resqnet.app.data.ConversationMessageEntity
import com.resqnet.app.data.PeerEntity
import com.resqnet.app.protocol.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MockDataSeeder {

    suspend fun seed(app: ResQNetApplication) = withContext(Dispatchers.IO) {
        val dao = app.database.meshDao()
        val now = System.currentTimeMillis()
        val myName = app.profile.displayName.ifBlank { "You" }
        val myNodeId = app.signer.nodeId

        // 1. Seed Public Broadcast Messages (Chat Screen)
        val messages = listOf(
            ConversationMessageEntity(
                messageId = "mock-msg-1",
                conversationId = CHANNEL_ID,
                kind = PacketKind.PUBLIC_TEXT,
                audienceType = AudienceType.PUBLIC_CHANNEL,
                audienceId = CHANNEL_ID,
                originNodeId = "mock-node-alice",
                originName = "Alice (Base Camp)",
                originSequence = 1L,
                createdAt = now - 1000L * 60 * 20,
                expiresAt = now + 1000L * 60 * 60 * 24,
                text = "All teams check in. Sector 4 medical relief center is now operational at 19.0820, 72.8845.",
                outgoing = false,
                relayed = true,
                delivered = true,
                hopCount = 1
            ),
            ConversationMessageEntity(
                messageId = "mock-msg-2",
                conversationId = CHANNEL_ID,
                kind = PacketKind.PUBLIC_TEXT,
                audienceType = AudienceType.PUBLIC_CHANNEL,
                audienceId = CHANNEL_ID,
                originNodeId = myNodeId,
                originName = myName,
                originSequence = 2L,
                createdAt = now - 1000L * 60 * 14,
                expiresAt = now + 1000L * 60 * 60 * 24,
                text = "Sector 2 team reporting. All 5 members safe. Moving towards bridge checkpoint 19.0655, 72.8710.",
                outgoing = true,
                relayed = true,
                delivered = true,
                hopCount = 0
            ),
            ConversationMessageEntity(
                messageId = "mock-msg-3",
                conversationId = CHANNEL_ID,
                kind = PacketKind.PUBLIC_TEXT,
                audienceType = AudienceType.PUBLIC_CHANNEL,
                audienceId = CHANNEL_ID,
                originNodeId = "mock-node-relay",
                originName = "Relay Unit 03",
                originSequence = 3L,
                createdAt = now - 1000L * 60 * 8,
                expiresAt = now + 1000L * 60 * 60 * 24,
                text = "Bridge checkpoint route is clear. Cellular network is down, mesh link strong across 5 hops.",
                outgoing = false,
                relayed = true,
                delivered = true,
                hopCount = 2
            ),
            ConversationMessageEntity(
                messageId = "mock-msg-4",
                conversationId = CHANNEL_ID,
                kind = PacketKind.PUBLIC_TEXT,
                audienceType = AudienceType.PUBLIC_CHANNEL,
                audienceId = CHANNEL_ID,
                originNodeId = myNodeId,
                originName = myName,
                originSequence = 4L,
                createdAt = now - 1000L * 60 * 4,
                expiresAt = now + 1000L * 60 * 60 * 24,
                text = "Understood. Requesting emergency power packs at GPS grid 19.0760, 72.8777.",
                outgoing = true,
                relayed = true,
                delivered = false,
                hopCount = 0
            ),
            ConversationMessageEntity(
                messageId = "mock-msg-5",
                conversationId = CHANNEL_ID,
                kind = PacketKind.PUBLIC_TEXT,
                audienceType = AudienceType.PUBLIC_CHANNEL,
                audienceId = CHANNEL_ID,
                originNodeId = "mock-node-alice",
                originName = "Alice (Base Camp)",
                originSequence = 5L,
                createdAt = now - 1000L * 60 * 2,
                expiresAt = now + 1000L * 60 * 60 * 24,
                text = "Supplies dispatched. Secondary staging point marked at 19.0915, 72.8640.",
                outgoing = false,
                relayed = true,
                delivered = true,
                hopCount = 1
            )
        )
        for (m in messages) {
            dao.insertConversationMessage(m)
        }

        // 2. Seed Contacts (Contacts Screen)
        val contacts = listOf(
            ContactEntity(
                nodeId = "mock-contact-priya",
                displayName = "Dr. Priya Sharma",
                publicKey = ByteArray(32) { 1 },
                fingerprint = "07a3-3bb9-fe7f-e4f0",
                state = ContactState.TRUSTED,
                outgoingRequestId = null,
                outgoingRequestExpiresAt = null,
                incomingRequestId = null,
                incomingRequestExpiresAt = null,
                updatedAt = now - 1000L * 60 * 60
            ),
            ContactEntity(
                nodeId = "mock-contact-vikram",
                displayName = "Rescue Team Lead (Vikram)",
                publicKey = ByteArray(32) { 2 },
                fingerprint = "9a8b-7c6d-5e4f-3210",
                state = ContactState.TRUSTED,
                outgoingRequestId = null,
                outgoingRequestExpiresAt = null,
                incomingRequestId = null,
                incomingRequestExpiresAt = null,
                updatedAt = now - 1000L * 60 * 30
            ),
            ContactEntity(
                nodeId = "mock-contact-rahul",
                displayName = "Volunteer Rahul",
                publicKey = ByteArray(32) { 3 },
                fingerprint = "cafe-babe-1234-5678",
                state = ContactState.PENDING_INCOMING,
                outgoingRequestId = null,
                outgoingRequestExpiresAt = null,
                incomingRequestId = "mock-req-rahul",
                incomingRequestExpiresAt = now + 1000L * 60 * 60 * 24,
                updatedAt = now - 1000L * 60 * 10
            )
        )
        for (c in contacts) {
            dao.upsertContact(c)
        }

        // 3. Seed Circles & Circle Details (Circles Screen)
        val circleAlphaId = "mock-circle-alpha"
        val circleShelterId = "mock-circle-shelter"

        val circles = listOf(
            CircleEntity(
                circleId = circleAlphaId,
                name = "Alpha Evac Squad",
                ownerNodeId = myNodeId,
                currentMembershipVersion = 1L,
                localState = CircleLocalState.OWNER_ACTIVE,
                leaveRequestPacketId = null,
                leaveRequestMembershipVersion = null,
                createdAt = now - 1000L * 60 * 120,
                updatedAt = now - 1000L * 60 * 5
            ),
            CircleEntity(
                circleId = circleShelterId,
                name = "Sector 4 Community Shelter",
                ownerNodeId = "mock-contact-priya",
                currentMembershipVersion = 1L,
                localState = CircleLocalState.ACTIVE,
                leaveRequestPacketId = null,
                leaveRequestMembershipVersion = null,
                createdAt = now - 1000L * 60 * 180,
                updatedAt = now - 1000L * 60 * 25
            )
        )
        for (circle in circles) {
            dao.upsertCircle(circle)
        }

        // Seed Snapshots & Members for Alpha Evac Squad
        val snapshotAlpha = CircleSnapshotEntity(
            circleId = circleAlphaId,
            membershipVersion = 1L,
            circleName = "Alpha Evac Squad",
            ownerNodeId = myNodeId,
            dissolved = false,
            packetId = "mock-pkt-snap-alpha",
            originSequence = 1L,
            createdAt = now - 1000L * 60 * 120
        )
        dao.insertCircleSnapshot(snapshotAlpha)

        val alphaMembers = listOf(
            CircleMemberEntity(circleAlphaId, 1L, myNodeId, CircleMemberRole.OWNER),
            CircleMemberEntity(circleAlphaId, 1L, "mock-contact-priya", CircleMemberRole.MEMBER),
            CircleMemberEntity(circleAlphaId, 1L, "mock-contact-vikram", CircleMemberRole.MEMBER)
        )
        runCatching { dao.insertCircleMembers(alphaMembers) }

        // Seed Circle Messages in Alpha Evac Squad
        val circleMessages = listOf(
            CircleMessageEntity(
                messageId = "mock-circle-msg-1",
                circleId = circleAlphaId,
                membershipVersion = 1L,
                originNodeId = "mock-contact-priya",
                originName = "Dr. Priya Sharma",
                originSequence = 1L,
                createdAt = now - 1000L * 60 * 25,
                text = "Triage clinic set up at north gate (19.0850, 72.8890). We have first aid supplies ready.",
                outgoing = false,
                hopCount = 1
            ),
            CircleMessageEntity(
                messageId = "mock-circle-msg-2",
                circleId = circleAlphaId,
                membershipVersion = 1L,
                originNodeId = myNodeId,
                originName = myName,
                originSequence = 2L,
                createdAt = now - 1000L * 60 * 18,
                text = "Copy that Dr. Priya. Rescue team is inbound with 3 civilians from 19.0725, 72.8655.",
                outgoing = true,
                hopCount = 0
            ),
            CircleMessageEntity(
                messageId = "mock-circle-msg-3",
                circleId = circleAlphaId,
                membershipVersion = 1L,
                originNodeId = "mock-contact-vikram",
                originName = "Rescue Team Lead (Vikram)",
                originSequence = 3L,
                createdAt = now - 1000L * 60 * 8,
                text = "Route 9 bridge is flooded. We are detouring through hill trail at 19.0690, 72.8812. ETA 20 mins.",
                outgoing = false,
                hopCount = 2
            ),
            CircleMessageEntity(
                messageId = "mock-circle-msg-4",
                circleId = circleAlphaId,
                membershipVersion = 1L,
                originNodeId = myNodeId,
                originName = myName,
                originSequence = 4L,
                createdAt = now - 1000L * 60 * 3,
                text = "Understood Vikram. We will rendezvous with you near coordinates 19.0782, 72.8735.",
                outgoing = true,
                hopCount = 0
            )
        )
        for (cm in circleMessages) {
            dao.insertCircleMessage(cm)
        }

        // Direct Messages with Dr. Priya Sharma
        val priyaDmConvId = directConversationId(myNodeId, "mock-contact-priya")
        val directMessages = listOf(
            ConversationMessageEntity(
                messageId = "mock-dm-priya-1",
                conversationId = priyaDmConvId,
                kind = PacketKind.DIRECT_TEXT,
                audienceType = AudienceType.DIRECT_NODE,
                audienceId = "mock-contact-priya",
                originNodeId = "mock-contact-priya",
                originName = "Dr. Priya Sharma",
                originSequence = 1L,
                createdAt = now - 1000L * 60 * 15,
                expiresAt = now + 1000L * 60 * 60 * 24,
                text = "Hi, our medical van is stationed near 19.0835, 72.8790. Let us know if any casualties need transport.",
                outgoing = false,
                relayed = true,
                delivered = true,
                hopCount = 1
            ),
            ConversationMessageEntity(
                messageId = "mock-dm-priya-2",
                conversationId = priyaDmConvId,
                kind = PacketKind.DIRECT_TEXT,
                audienceType = AudienceType.DIRECT_NODE,
                audienceId = "mock-contact-priya",
                originNodeId = myNodeId,
                originName = myName,
                originSequence = 2L,
                createdAt = now - 1000L * 60 * 10,
                expiresAt = now + 1000L * 60 * 60 * 24,
                text = "Thanks Dr. Priya! We marked your position at 19.0835, 72.8790 on our offline map.",
                outgoing = true,
                relayed = true,
                delivered = true,
                hopCount = 0
            )
        )
        for (dm in directMessages) {
            dao.insertConversationMessage(dm)
        }

        // Seed Circle Status Events
        val statusEvents = listOf(
            CircleStatusEventEntity(
                packetId = "mock-status-priya",
                circleId = circleAlphaId,
                membershipVersion = 1L,
                memberNodeId = "mock-contact-priya",
                status = SafetyStatus.SAFE,
                note = "At Triage Station 1",
                originSequence = 1L,
                createdAt = now - 1000L * 60 * 30
            ),
            CircleStatusEventEntity(
                packetId = "mock-status-vikram",
                circleId = circleAlphaId,
                membershipVersion = 1L,
                memberNodeId = "mock-contact-vikram",
                status = SafetyStatus.NEED_HELP,
                note = "Need stretcher at hill trail junction",
                originSequence = 1L,
                createdAt = now - 1000L * 60 * 10
            )
        )
        for (se in statusEvents) {
            dao.insertCircleStatus(se)
        }

        // 4. Seed Nearby Peers (Mesh Control / Nearby Screen)
        val peers = listOf(
            PeerEntity(
                nodeId = "mock-peer-relay-1",
                displayName = "Relay Hub North",
                publicKey = ByteArray(32) { 4 },
                fingerprint = "2233-4455-6677-8899",
                protocolVersion = 1,
                lastSeenAt = now
            ),
            PeerEntity(
                nodeId = "mock-peer-mobile-2",
                displayName = "Mobile Mesh Unit 7",
                publicKey = ByteArray(32) { 5 },
                fingerprint = "1122-3344-5566-7788",
                protocolVersion = 1,
                lastSeenAt = now - 35000L
            )
        )
        for (p in peers) {
            dao.upsertPeer(p)
        }
    }

    suspend fun clear(app: ResQNetApplication) = withContext(Dispatchers.IO) {
        // Surgically remove only seeded mock items, protecting user's real conversations and data
        val db = app.database.openHelper.writableDatabase
        db.execSQL("DELETE FROM conversation_messages WHERE messageId LIKE 'mock-%'")
        db.execSQL("DELETE FROM contacts WHERE nodeId LIKE 'mock-%'")
        db.execSQL("DELETE FROM circles WHERE circleId LIKE 'mock-%'")
        db.execSQL("DELETE FROM circle_snapshots WHERE circleId LIKE 'mock-%'")
        db.execSQL("DELETE FROM circle_members WHERE circleId LIKE 'mock-%'")
        db.execSQL("DELETE FROM circle_messages WHERE circleId LIKE 'mock-%' OR messageId LIKE 'mock-%'")
        db.execSQL("DELETE FROM circle_status_events WHERE circleId LIKE 'mock-%'")
        db.execSQL("DELETE FROM peers WHERE nodeId LIKE 'mock-%'")
    }
}
