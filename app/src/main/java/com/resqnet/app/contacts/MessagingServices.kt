package com.resqnet.app.contacts

import com.resqnet.app.data.ContactRepository
import com.resqnet.app.data.ConversationRepository
import com.resqnet.app.mesh.MessageRouter

class ContactService(
    private val contacts: ContactRepository,
    private val router: MessageRouter,
) {
    fun observeContacts() = contacts.observeContacts()
    suspend fun request(nodeId: String, confirmedFingerprint: String) =
        router.requestContact(nodeId, confirmedFingerprint)
    suspend fun accept(nodeId: String) = router.acceptContact(nodeId)
    suspend fun decline(nodeId: String) = router.declineContact(nodeId)
    suspend fun remove(nodeId: String) = router.removeContact(nodeId)
    suspend fun block(nodeId: String) = router.blockContact(nodeId)
    suspend fun unblock(nodeId: String) = router.unblockContact(nodeId)
}

class DirectMessageService(
    private val conversations: ConversationRepository,
    private val router: MessageRouter,
) {
    fun observeConversation(conversationId: String) = conversations.observeConversation(conversationId)
    suspend fun send(nodeId: String, text: String) = router.createDirectMessage(nodeId, text)
}
