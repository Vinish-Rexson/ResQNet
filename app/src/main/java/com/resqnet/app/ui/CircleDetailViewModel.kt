package com.resqnet.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.resqnet.app.ResQNetApplication
import com.resqnet.app.circles.CircleEntity
import com.resqnet.app.circles.CircleMemberEntity
import com.resqnet.app.circles.CircleMessageEntity
import com.resqnet.app.circles.CircleStatusEventEntity
import com.resqnet.app.mesh.MeshService
import com.resqnet.app.protocol.SafetyStatus
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class MemberUiModel(
    val member: CircleMemberEntity,
    val displayName: String,
)

data class CircleDetailUiState(
    val circle: CircleEntity? = null,
    val members: List<MemberUiModel> = emptyList(),
    val messages: List<CircleMessageEntity> = emptyList(),
    val localStatus: CircleStatusEventEntity? = null,
    val memberStatuses: Map<String, CircleStatusEventEntity> = emptyMap(),
)

class CircleDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ResQNetApplication
    private val localNodeId = app.signer.nodeId

    var circleId: String = ""

    private val _circle = MutableStateFlow<CircleEntity?>(null)
    private val _members = MutableStateFlow<List<MemberUiModel>>(emptyList())
    private val _statuses = MutableStateFlow<Map<String, CircleStatusEventEntity>>(emptyMap())

    val uiState: StateFlow<CircleDetailUiState> by lazy {
        combine(
            _circle,
            _members,
            app.circleMessages.observeMessages(circleId),
            _statuses
        ) { circle, members, messages, statuses ->
            CircleDetailUiState(
                circle = circle,
                members = members,
                messages = messages,
                localStatus = statuses[localNodeId],
                memberStatuses = statuses
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CircleDetailUiState())
    }

    fun load() = viewModelScope.launch {
        app.circles.circle(circleId)?.let { _circle.value = it }
        val snapshot = app.circles.latestSnapshot(circleId)
        if (snapshot != null) {
            val members = app.circles.members(circleId, snapshot.membershipVersion)
            
            // Map to MemberUiModel
            val uiModels = members.map { member ->
                val name = if (member.nodeId == localNodeId) {
                    app.profile.displayName
                } else {
                    app.contacts.find(member.nodeId)?.displayName
                        ?: app.peers.find(member.nodeId)?.displayName
                        ?: member.nodeId.take(8)
                }
                MemberUiModel(member, name)
            }
            _members.value = uiModels

            val statuses = mutableMapOf<String, CircleStatusEventEntity>()
            for (member in members) {
                app.circleStatuses.history(circleId, member.nodeId).maxByOrNull { it.originSequence }?.let {
                    statuses[member.nodeId] = it
                }
            }
            _statuses.value = statuses
        }
    }

    fun send(text: String, onResult: (String?) -> Unit) = viewModelScope.launch {
        runCatching { app.circleMessages.send(circleId, text) }
            .onSuccess { MeshService.command(app, MeshService.ACTION_SYNC); onResult(null) }
            .onFailure { onResult(it.message ?: "Could not send message") }
    }

    fun updateStatus(status: SafetyStatus, note: String?, onResult: (String?) -> Unit) = viewModelScope.launch {
        runCatching { app.circleStatuses.update(circleId, status, note) }
            .onSuccess { 
                MeshService.command(app, MeshService.ACTION_SYNC)
                load() // Reload to reflect the new status
                onResult(null) 
            }
            .onFailure { onResult(it.message ?: "Could not update status") }
    }

    fun inviteMember(nodeId: String, onResult: (String?) -> Unit) = viewModelScope.launch {
        runCatching { app.circleService.invite(circleId, nodeId) }
            .onSuccess { MeshService.command(app, MeshService.ACTION_SYNC); onResult(null) }
            .onFailure { onResult(it.message ?: "Could not invite member") }
    }

    fun removeMember(nodeId: String, onResult: (String?) -> Unit) = viewModelScope.launch {
        runCatching { app.circleService.removeMember(circleId, nodeId) }
            .onSuccess { MeshService.command(app, MeshService.ACTION_SYNC); load(); onResult(null) }
            .onFailure { onResult(it.message ?: "Could not remove member") }
    }

    fun leave(onResult: (String?) -> Unit) = viewModelScope.launch {
        runCatching { app.circleService.leave(circleId) }
            .onSuccess { MeshService.command(app, MeshService.ACTION_SYNC); onResult(null) }
            .onFailure { onResult(it.message ?: "Could not leave circle") }
    }

    fun dissolve(onResult: (String?) -> Unit) = viewModelScope.launch {
        runCatching { app.circleService.dissolve(circleId) }
            .onSuccess { MeshService.command(app, MeshService.ACTION_SYNC); onResult(null) }
            .onFailure { onResult(it.message ?: "Could not dissolve circle") }
    }
}
