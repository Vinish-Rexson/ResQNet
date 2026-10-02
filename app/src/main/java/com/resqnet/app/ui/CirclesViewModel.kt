package com.resqnet.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.resqnet.app.ResQNetApplication
import com.resqnet.app.circles.CircleEntity
import com.resqnet.app.circles.CircleLocalState
import com.resqnet.app.circles.CircleInvitationState
import com.resqnet.app.protocol.SafetyStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Per-circle status summary shown on list cards. */
data class CircleStatusSummary(
    val safeCount: Int,
    val needHelpCount: Int,
    val unknownCount: Int,
    val totalWithStatus: Int,
) {
    val hasAnyStatus: Boolean get() = totalWithStatus > 0
    val worstStatus: SafetyStatus
        get() = when {
            needHelpCount > 0 -> SafetyStatus.NEED_HELP
            safeCount > 0     -> SafetyStatus.SAFE
            else              -> SafetyStatus.UNKNOWN
        }
}

data class CirclesUiState(
    val active: List<CircleEntity> = emptyList(),
    val invites: List<CircleEntity> = emptyList(),
    val archived: List<CircleEntity> = emptyList(),
    /** circleId → summary, derived live from status events. */
    val statusSummaries: Map<String, CircleStatusSummary> = emptyMap(),
)

class CirclesViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ResQNetApplication

    val uiState: StateFlow<CirclesUiState> = combine(
        app.circleService.observeCircles(),
        app.circleStatuses.observeAllLatestStatuses(),
    ) { circles, allStatuses ->
        // Build per-circle latest-status map: circleId → memberNodeId → status
        val byCircle = allStatuses
            .groupBy { it.circleId }
            .mapValues { (_, events) ->
                // Keep only the latest event per member
                events
                    .groupBy { it.memberNodeId }
                    .mapValues { (_, memberEvents) ->
                        memberEvents.maxByOrNull { it.originSequence }!!.status
                    }
            }

        val summaries = circles.associate { circle ->
            val statuses = byCircle[circle.circleId]?.values ?: emptyList()
            val safe     = statuses.count { it == SafetyStatus.SAFE }
            val help     = statuses.count { it == SafetyStatus.NEED_HELP }
            val unknown  = statuses.count { it == SafetyStatus.UNKNOWN }
            circle.circleId to CircleStatusSummary(safe, help, unknown, statuses.size)
        }

        val active = circles.filter {
            it.localState == CircleLocalState.ACTIVE ||
            it.localState == CircleLocalState.OWNER_ACTIVE
        }
        val invites = circles.filter {
            it.localState == CircleLocalState.INVITED ||
            it.localState == CircleLocalState.ACCEPTANCE_PENDING
        }
        val archived = circles.filter {
            it.localState == CircleLocalState.LEAVE_PENDING ||
            it.localState == CircleLocalState.ARCHIVED_DISSOLVED ||
            it.localState == CircleLocalState.ARCHIVED_REMOVED
        }
        CirclesUiState(active, invites, archived, summaries)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CirclesUiState())

    fun createCircle(name: String, onResult: (String?) -> Unit) = viewModelScope.launch {
        runCatching { app.circleService.create(name) }
            .onSuccess { com.resqnet.app.mesh.MeshService.command(app, com.resqnet.app.mesh.MeshService.ACTION_SYNC); onResult(null) }
            .onFailure { onResult(it.message ?: "Failed to create circle") }
    }

    fun acceptInvite(circleId: String, onResult: (String?) -> Unit) = viewModelScope.launch {
        runCatching {
            val invites = app.circles.invitationsForCircle(circleId)
            val invite = invites.find { it.targetNodeId == app.signer.nodeId && it.state == CircleInvitationState.PENDING }
                ?: throw IllegalStateException("No pending invitation found for this circle")
            app.circleService.accept(invite.inviteId)
        }
            .onSuccess { com.resqnet.app.mesh.MeshService.command(app, com.resqnet.app.mesh.MeshService.ACTION_SYNC); onResult(null) }
            .onFailure { onResult(it.message ?: "Failed to accept invite") }
    }

    fun declineInvite(circleId: String, onResult: (String?) -> Unit) = viewModelScope.launch {
        runCatching {
            val invites = app.circles.invitationsForCircle(circleId)
            val invite = invites.find { it.targetNodeId == app.signer.nodeId && it.state == CircleInvitationState.PENDING }
                ?: throw IllegalStateException("No pending invitation found for this circle")
            app.circleService.decline(invite.inviteId)
        }
            .onSuccess { com.resqnet.app.mesh.MeshService.command(app, com.resqnet.app.mesh.MeshService.ACTION_SYNC); onResult(null) }
            .onFailure { onResult(it.message ?: "Failed to decline invite") }
    }
}
