package com.resqnet.app.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MeshUiState(
    val active: Boolean = false,
    val peerCount: Int = 0,
    val status: String = "Mesh stopped",
    val events: List<String> = emptyList(),
)

object MeshRuntime {
    private val mutable = MutableStateFlow(MeshUiState())
    val state: StateFlow<MeshUiState> = mutable.asStateFlow()

    fun active(value: Boolean, status: String) { mutable.value = mutable.value.copy(active = value, status = status) }
    fun peers(count: Int) { mutable.value = mutable.value.copy(peerCount = count) }
    fun event(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        mutable.value = mutable.value.copy(events = (listOf("$time  $message") + mutable.value.events).take(100))
    }
    fun clearEvents() { mutable.value = mutable.value.copy(events = emptyList()) }
}
