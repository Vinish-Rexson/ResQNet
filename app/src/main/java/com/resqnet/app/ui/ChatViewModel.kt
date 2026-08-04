package com.resqnet.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.resqnet.app.ResQNetApplication
import com.resqnet.app.data.MessageEntity
import com.resqnet.app.mesh.MeshRuntime
import com.resqnet.app.mesh.MeshService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ResQNetApplication
    val messages: StateFlow<List<MessageEntity>> = app.messages.observeMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val mesh = MeshRuntime.state

    fun send(text: String, onResult: (String?) -> Unit) = viewModelScope.launch {
        runCatching { app.router.createMessage(text) }
            .onSuccess { MeshService.command(app, MeshService.ACTION_SYNC); onResult(null) }
            .onFailure { onResult(it.message ?: "Could not send message") }
    }
}
