package com.resqnet.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.resqnet.app.ResQNetApplication
import com.resqnet.app.contacts.ContactService
import com.resqnet.app.data.ContactEntity
import com.resqnet.app.data.ContactState
import com.resqnet.app.data.PeerEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ContactsUiState(
    val trusted: List<ContactEntity> = emptyList(),
    val pending: List<ContactEntity> = emptyList(),
    val nearby: List<PeerEntity> = emptyList(),
)

class ContactsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ResQNetApplication

    val uiState: StateFlow<ContactsUiState> = combine(
        app.contacts.observeContacts(),
        app.peers.observePeers(),
    ) { contacts, peers ->
        val trustedAndBlocked = contacts.filter {
            it.state == ContactState.TRUSTED || it.state == ContactState.BLOCKED
        }
        val pending = contacts.filter { it.state.isPending }
        val contactNodeIds = contacts.map { it.nodeId }.toSet()
        val now = System.currentTimeMillis()
        // Nearby tab: peers discovered via BLE in the last 2 minutes that are not yet contacts
        val nearby = peers.filter { 
            it.nodeId !in contactNodeIds && (now - it.lastSeenAt) < 2L * 60 * 1000 
        }
        ContactsUiState(trustedAndBlocked, pending, nearby)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContactsUiState())

    fun acceptContact(nodeId: String, onResult: (String?) -> Unit) = viewModelScope.launch {
        runCatching { app.contactService.accept(nodeId) }
            .onSuccess { com.resqnet.app.mesh.MeshService.command(app, com.resqnet.app.mesh.MeshService.ACTION_SYNC); onResult(null) }
            .onFailure { onResult(it.message ?: "Could not accept contact") }
    }

    fun declineContact(nodeId: String, onResult: (String?) -> Unit) = viewModelScope.launch {
        runCatching { app.contactService.decline(nodeId) }
            .onSuccess { com.resqnet.app.mesh.MeshService.command(app, com.resqnet.app.mesh.MeshService.ACTION_SYNC); onResult(null) }
            .onFailure { onResult(it.message ?: "Could not decline contact") }
    }

    fun removeContact(nodeId: String, onResult: (String?) -> Unit) = viewModelScope.launch {
        runCatching { app.contactService.remove(nodeId) }
            .onSuccess { com.resqnet.app.mesh.MeshService.command(app, com.resqnet.app.mesh.MeshService.ACTION_SYNC); onResult(null) }
            .onFailure { onResult(it.message ?: "Could not remove contact") }
    }

    fun blockContact(nodeId: String, onResult: (String?) -> Unit) = viewModelScope.launch {
        runCatching { app.contactService.block(nodeId) }
            .onSuccess { com.resqnet.app.mesh.MeshService.command(app, com.resqnet.app.mesh.MeshService.ACTION_SYNC); onResult(null) }
            .onFailure { onResult(it.message ?: "Could not block contact") }
    }

    fun unblockContact(nodeId: String, onResult: (String?) -> Unit) = viewModelScope.launch {
        runCatching { app.contactService.unblock(nodeId) }
            .onSuccess { com.resqnet.app.mesh.MeshService.command(app, com.resqnet.app.mesh.MeshService.ACTION_SYNC); onResult(null) }
            .onFailure { onResult(it.message ?: "Could not unblock contact") }
    }

    /**
     * Sends a contact request to a nearby BLE peer, using the fingerprint the user has
     * visually confirmed by comparing screens. The fingerprint is the peer's public-key
     * digest (first 32 hex chars of SHA-256 of the raw public key bytes).
     */
    fun requestContact(nodeId: String, fingerprint: String, onResult: (String?) -> Unit) =
        viewModelScope.launch {
            runCatching { app.contactService.request(nodeId, fingerprint) }
                .onSuccess { com.resqnet.app.mesh.MeshService.command(app, com.resqnet.app.mesh.MeshService.ACTION_SYNC); onResult(null) }
                .onFailure { onResult(it.message ?: "Could not send contact request") }
        }
}
