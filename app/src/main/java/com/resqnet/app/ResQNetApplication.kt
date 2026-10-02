package com.resqnet.app

import android.app.Application
import com.resqnet.app.data.*
import com.resqnet.app.contacts.ContactService
import com.resqnet.app.contacts.DirectMessageService
import com.resqnet.app.mesh.MessageRouter
import com.resqnet.app.security.AndroidIdentitySigner
import com.resqnet.app.circles.CircleRepository
import com.resqnet.app.circles.RoomCircleRepository
import com.resqnet.app.circles.CircleService
import com.resqnet.app.circles.CircleMessageService
import com.resqnet.app.circles.CircleStatusService

class ResQNetApplication : Application() {
    lateinit var database: ResQNetDatabase; private set
    lateinit var profile: ProfileStore; private set
    lateinit var packets: PacketRepository; private set
    lateinit var messages: ConversationRepository; private set
    lateinit var peers: PeerRepository; private set
    lateinit var contacts: ContactRepository; private set
    lateinit var receipts: ReceiptRepository; private set
    lateinit var localProjections: LocalProjectionRepository; private set
    lateinit var circles: CircleRepository; private set
    lateinit var circleService: CircleService; private set
    lateinit var circleMessages: CircleMessageService; private set
    lateinit var circleStatuses: CircleStatusService; private set
    lateinit var router: MessageRouter; private set
    lateinit var contactService: ContactService; private set
    lateinit var directMessages: DirectMessageService; private set
    lateinit var signer: AndroidIdentitySigner; private set

    override fun onCreate() {
        super.onCreate()
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
        profile = ProfileStore(this)
        database = ResQNetDatabase.create(this)
        packets = RoomPacketRepository(database.meshDao())
        messages = RoomConversationRepository(database.meshDao())
        peers = RoomPeerRepository(database.meshDao())
        contacts = RoomContactRepository(database.meshDao())
        receipts = RoomReceiptRepository(database.meshDao())
        localProjections = RoomLocalProjectionRepository(database, database.meshDao())
        circles = RoomCircleRepository(database, database.meshDao())
        signer = AndroidIdentitySigner()
        router = MessageRouter(
            packets, messages, peers, signer, { profile.displayName.ifBlank { "Anonymous" } },
            contacts = contacts, receipts = receipts, localProjections = localProjections, circles = circles,
        )
        contactService = ContactService(contacts, router)
        directMessages = DirectMessageService(messages, router)
        circleService = CircleService(circles, router)
        circleMessages = CircleMessageService(circles, router)
        circleStatuses = CircleStatusService(circles, router)
    }
}
