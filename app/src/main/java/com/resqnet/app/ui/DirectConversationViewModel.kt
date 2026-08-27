package com.resqnet.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.resqnet.app.ResQNetApplication
import com.resqnet.app.contacts.directConversationId
import com.resqnet.app.data.ConversationMessageEntity
import com.resqnet.app.mesh.MeshService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DirectConversationViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ResQNetApplication
    private val localNodeId = app.signer.nodeId

    /** Populated by the Activity before any flow is collected. */
    var remoteNodeId: String = ""
    var remoteDisplayName: String = ""

    /** Conversation ID is deterministic from the two node IDs. */
    val conversationId: String by lazy { directConversationId(localNodeId, remoteNodeId) }

    val messages: StateFlow<List<ConversationMessageEntity>> by lazy {
        app.directMessages.observeConversation(conversationId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    fun send(text: String, onResult: (String?) -> Unit) = viewModelScope.launch {
        runCatching { app.directMessages.send(remoteNodeId, text) }
            .onSuccess { MeshService.command(app, MeshService.ACTION_SYNC); onResult(null) }
            .onFailure { onResult(it.message ?: "Could not send message") }
    }
}
