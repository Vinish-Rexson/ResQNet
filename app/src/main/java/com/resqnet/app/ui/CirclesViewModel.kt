package com.resqnet.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.resqnet.app.ResQNetApplication
import com.resqnet.app.circles.CircleEntity
import com.resqnet.app.circles.CircleLocalState
import com.resqnet.app.circles.CircleInvitationState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CirclesUiState(
    val active: List<CircleEntity> = emptyList(),
    val invites: List<CircleEntity> = emptyList(),
    val archived: List<CircleEntity> = emptyList(),
)

class CirclesViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ResQNetApplication

    val uiState: StateFlow<CirclesUiState> = app.circleService.observeCircles().map { circles ->
        val active = circles.filter { it.localState == CircleLocalState.ACTIVE }
        val invites = circles.filter { 
            it.localState == CircleLocalState.INVITED || 
            it.localState == CircleLocalState.ACCEPTANCE_PENDING 
        }
        val archived = circles.filter { 
            it.localState == CircleLocalState.LEAVE_PENDING || 
            it.localState == CircleLocalState.ARCHIVED_DISSOLVED || 
            it.localState == CircleLocalState.ARCHIVED_REMOVED 
        }
        CirclesUiState(active, invites, archived)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CirclesUiState())

    fun createCircle(name: String, onResult: (String?) -> Unit) = viewModelScope.launch {
        runCatching { app.circleService.create(name) }
            .onSuccess { onResult(null) }
            .onFailure { onResult(it.message ?: "Failed to create circle") }
    }

    fun acceptInvite(circleId: String, onResult: (String?) -> Unit) = viewModelScope.launch {
        runCatching {
            val invites = app.circles.invitationsForCircle(circleId)
            val invite = invites.find { it.targetNodeId == app.signer.nodeId && it.state == CircleInvitationState.ACCEPTANCE_PENDING }
                ?: throw IllegalStateException("No pending invitation found for this circle")
            app.circleService.accept(invite.inviteId)
        }
            .onSuccess { onResult(null) }
            .onFailure { onResult(it.message ?: "Failed to accept invite") }
    }

    fun declineInvite(circleId: String, onResult: (String?) -> Unit) = viewModelScope.launch {
        runCatching {
            val invites = app.circles.invitationsForCircle(circleId)
            val invite = invites.find { it.targetNodeId == app.signer.nodeId && it.state == CircleInvitationState.ACCEPTANCE_PENDING }
                ?: throw IllegalStateException("No pending invitation found for this circle")
            app.circleService.decline(invite.inviteId)
        }
            .onSuccess { onResult(null) }
            .onFailure { onResult(it.message ?: "Failed to decline invite") }
    }
}
