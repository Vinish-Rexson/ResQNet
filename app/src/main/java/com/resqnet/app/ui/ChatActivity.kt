package com.resqnet.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar
import com.resqnet.app.R
import com.resqnet.app.ResQNetApplication
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity() {
    private val model by viewModels<ChatViewModel>()
    private val adapter = MessageAdapter()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ResQNetApplication
        if (!app.profile.configured) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_chat)

        // Request all permissions immediately when ChatActivity opens
        if (!PermissionHelper.hasPermissions(this)) {
            permissionLauncher.launch(PermissionHelper.getRequiredPermissions())
        }

        // Toolbar
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.navigationIcon = null // top-level tab, no back button
        supportActionBar?.title = "ResQNet"

        // Message list
        val list = findViewById<RecyclerView>(R.id.messageList).apply {
            layoutManager = LinearLayoutManager(this@ChatActivity).apply { stackFromEnd = true }
            adapter = this@ChatActivity.adapter
        }

        // Input
        val input = findViewById<EditText>(R.id.messageInput)
        val countView = findViewById<TextView>(R.id.byteCount)
        val emptyState = findViewById<View>(R.id.emptyState)

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val bytes = s.toString().toByteArray(Charsets.UTF_8).size
                countView.text = "$bytes / 500 bytes"
                countView.setTextColor(getColor(if (bytes > 500) R.color.alert else R.color.text_muted))
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        findViewById<com.google.android.material.button.MaterialButton>(R.id.sendButton).setOnClickListener { btn ->
            val text = input.text.toString().trim()
            if (text.isBlank()) return@setOnClickListener
            model.send(text) { error ->
                runOnUiThread {
                    if (error == null) input.text.clear()
                    else Snackbar.make(btn, error, Snackbar.LENGTH_LONG).show()
                }
            }
        }

        // Mesh status bar
        val statusDot = findViewById<View>(R.id.statusDot)
        val meshStatusView = findViewById<TextView>(R.id.meshStatus)
        val peerCountView = findViewById<TextView>(R.id.peerCount)

        findViewById<Button>(R.id.meshButton).setOnClickListener {
            startActivity(Intent(this, MeshControlActivity::class.java))
        }

        // Bottom Navigation
        setupBottomNav(this, R.id.nav_chat)

        // Hide bottom navigation bar when soft keyboard is open so message input rests cleanly on keyboard
        val bottomNav = findViewById<View>(R.id.bottomNav)
        val bottomNavDivider = findViewById<View>(R.id.bottomNavDivider)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            bottomNav?.visibility = if (imeVisible) View.GONE else View.VISIBLE
            bottomNavDivider?.visibility = if (imeVisible) View.GONE else View.VISIBLE
            ViewCompat.onApplyWindowInsets(v, insets)
        }

        // Observe state
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    model.messages.collect { messages ->
                        adapter.submitList(messages) {
                            if (messages.isNotEmpty()) list.scrollToPosition(messages.lastIndex)
                        }
                        emptyState.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    model.mesh.collect { state ->
                        meshStatusView.text = state.status
                        peerCountView.text = "${state.peerCount} peer${if (state.peerCount == 1) "" else "s"} nearby"
                        val isActive = state.active
                        statusDot.setBackgroundResource(
                            if (isActive) R.drawable.bg_status_dot_active
                            else R.drawable.bg_status_dot_inactive
                        )
                    }
                }
            }
        }
    }
}
