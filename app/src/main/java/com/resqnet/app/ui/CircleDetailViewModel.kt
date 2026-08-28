package com.resqnet.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.resqnet.app.ResQNetApplication
import com.resqnet.app.circles.CircleDeliveryProgress
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
    val status: CircleStatusEventEntity?,
)

data class CircleDetailUiState(
    val circle: CircleEntity? = null,
    val members: List<MemberUiModel> = emptyList(),
    val messages: List<CircleMessageEntity> = emptyList(),
    val localStatus: CircleStatusEventEntity? = null,
    val memberStatuses: Map<String, CircleStatusEventEntity> = emptyMap(),
    // messageId → delivery progress (delivered/possible)
    val deliveryProgress: Map<String, CircleDeliveryProgress> = emptyMap(),
)

class CircleDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ResQNetApplication
    private val localNodeId = app.signer.nodeId

    var circleId: String = ""

    private val _circle = MutableStateFlow<CircleEntity?>(null)
    private val _rawMembers = MutableStateFlow<List<CircleMemberEntity>>(emptyList())
    private val _progress = MutableStateFlow<Map<String, CircleDeliveryProgress>>(emptyMap())

    val uiState: StateFlow<CircleDetailUiState> by lazy {
        combine(
            _circle,
            _rawMembers,
            app.circleMessages.observeMessages(circleId),
            app.circleStatuses.observeStatuses(circleId),
            _progress,
        ) { circle, rawMembers, messages, statuses, progress ->
            val statusMap = statuses
                .groupBy { it.memberNodeId }
                .mapValues { (_, memberStatuses) -> memberStatuses.maxByOrNull { it.originSequence }!! }
                
            val members = rawMembers.map { member ->
                val name = if (member.nodeId == localNodeId) {
                    app.profile.displayName
                } else {
                    app.contacts.find(member.nodeId)?.displayName
                        ?: app.peers.find(member.nodeId)?.displayName
                        ?: member.nodeId.take(8)
                }
                MemberUiModel(member, name, statusMap[member.nodeId])
            }

            CircleDetailUiState(
                circle = circle,
                members = members,
                messages = messages,
                localStatus = statusMap[localNodeId],
                memberStatuses = statusMap,
                deliveryProgress = progress,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CircleDetailUiState())
    }

    fun load() = viewModelScope.launch {
        app.circles.circle(circleId)?.let { _circle.value = it }
        val snapshot = app.circles.latestSnapshot(circleId)
        if (snapshot != null) {
            _rawMembers.value = app.circles.members(circleId, snapshot.membershipVersion)
        }

        // Load delivery progress for all outgoing messages in this circle
        val messages = app.circleMessages.messages(circleId)
        val progressMap = mutableMapOf<String, CircleDeliveryProgress>()
        for (msg in messages) {
            val progress = runCatching { app.circleMessages.deliveryProgress(msg.messageId) }.getOrNull()
            if (progress != null && progress.possible > 0) {
                progressMap[msg.messageId] = progress
            }
        }
        _progress.value = progressMap
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
                load()
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

    fun rename(name: String, onResult: (String?) -> Unit) = viewModelScope.launch {
        runCatching { app.circleService.rename(circleId, name) }
            .onSuccess { MeshService.command(app, MeshService.ACTION_SYNC); load(); onResult(null) }
            .onFailure { onResult(it.message ?: "Could not rename circle") }
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
