package com.resqnet.app

import android.app.Application
import com.resqnet.app.data.*
import com.resqnet.app.mesh.MessageRouter
import com.resqnet.app.security.AndroidIdentitySigner

class ResQNetApplication : Application() {
    lateinit var database: ResQNetDatabase; private set
    lateinit var profile: ProfileStore; private set
    lateinit var packets: PacketRepository; private set
    lateinit var messages: ConversationRepository; private set
    lateinit var peers: PeerRepository; private set
    lateinit var router: MessageRouter; private set
    lateinit var signer: AndroidIdentitySigner; private set

    override fun onCreate() {
        super.onCreate()
        profile = ProfileStore(this)
        database = ResQNetDatabase.create(this)
        packets = RoomPacketRepository(database.meshDao())
        messages = RoomConversationRepository(database.meshDao())
        peers = RoomPeerRepository(database.meshDao())
        signer = AndroidIdentitySigner()
        router = MessageRouter(packets, messages, peers, signer, { profile.displayName.ifBlank { "Anonymous" } })
    }
}
