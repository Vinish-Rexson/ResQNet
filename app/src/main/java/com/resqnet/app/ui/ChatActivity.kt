package com.resqnet.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.resqnet.app.R
import com.resqnet.app.ResQNetApplication
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity() {
    private val model by viewModels<ChatViewModel>()
    private val adapter = MessageAdapter()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ResQNetApplication
        if (!app.profile.configured) { startActivity(Intent(this, SetupActivity::class.java)); finish(); return }
        setContentView(R.layout.activity_chat)
        title = "Local Emergency Channel"
        val list = findViewById<RecyclerView>(R.id.messageList).apply {
            layoutManager = LinearLayoutManager(this@ChatActivity).apply { stackFromEnd = true }
            adapter = this@ChatActivity.adapter
        }
        val input = findViewById<EditText>(R.id.messageInput)
        val count = findViewById<TextView>(R.id.byteCount)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, countValue: Int) {
                val bytes = s.toString().toByteArray(Charsets.UTF_8).size
                count.text = "$bytes / 500 bytes"; count.setTextColor(getColor(if (bytes > 500) R.color.alert else R.color.text_muted))
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        findViewById<Button>(R.id.sendButton).setOnClickListener { button ->
            val text = input.text.toString()
            model.send(text) { error -> runOnUiThread {
                if (error == null) input.text.clear() else Snackbar.make(button, error, Snackbar.LENGTH_LONG).show()
            } }
        }
        findViewById<Button>(R.id.meshButton).setOnClickListener { startActivity(Intent(this, MeshControlActivity::class.java)) }
        findViewById<Button>(R.id.diagnosticsButton).setOnClickListener { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
        findViewById<Button>(R.id.contactsButton).setOnClickListener { startActivity(Intent(this, ContactsActivity::class.java)) }
        findViewById<Button>(R.id.circlesButton).setOnClickListener { startActivity(Intent(this, CirclesActivity::class.java)) }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { model.messages.collect { messages ->
                adapter.submitList(messages) { if (messages.isNotEmpty()) list.scrollToPosition(messages.lastIndex) }
                findViewById<TextView>(R.id.emptyState).visibility = if (messages.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            } }
            launch { model.mesh.collect { state ->
                findViewById<TextView>(R.id.meshStatus).text = state.status
                findViewById<TextView>(R.id.peerCount).text = "${state.peerCount} peer${if (state.peerCount == 1) "" else "s"}"
            } }
        } }
    }
}
