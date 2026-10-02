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
import com.resqnet.app.mesh.MeshRuntime
import com.resqnet.app.mesh.MeshService

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
            addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
                if (bottom < oldBottom && this@ChatActivity.adapter.itemCount > 0) {
                    post { scrollToPosition(this@ChatActivity.adapter.itemCount - 1) }
                }
            }
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

        findViewById<View>(R.id.locationButton).setOnClickListener {
            if (!PermissionHelper.hasPermissions(this)) {
                permissionLauncher.launch(PermissionHelper.getRequiredPermissions())
                return@setOnClickListener
            }
            CoordinateUtils.fetchCoordinates(
                context = this,
                onSuccess = { lat, lon ->
                    CoordinateUtils.insertCoordinatesIntoInput(input, lat, lon)
                },
                onError = { err ->
                    android.widget.Toast.makeText(this, err, android.widget.Toast.LENGTH_SHORT).show()
                }
            )
        }

        fun doSend(msg: String) {
            model.send(msg) { error ->
                runOnUiThread {
                    if (error == null) input.text.clear()
                    else Snackbar.make(findViewById(R.id.sendButton), error, Snackbar.LENGTH_LONG).show()
                }
            }
        }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.sendButton).setOnClickListener { btn ->
            val text = input.text.toString().trim()
            if (text.isBlank()) return@setOnClickListener

            // Check if Mesh is stopped
            if (!MeshRuntime.state.value.active) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("Mesh Network is Stopped")
                    .setMessage("Nearby devices won't receive this message until the Mesh network is active. Would you like to start Mesh now?")
                    .setPositiveButton("Start Mesh & Send") { _, _ ->
                        com.resqnet.app.mesh.MeshPrerequisitesHelper.checkAndPrompt(
                            activity = this,
                            onRequestPermissions = { permissionLauncher.launch(PermissionHelper.getRequiredPermissions()) },
                            onReadyToStart = {
                                MeshService.command(this, MeshService.ACTION_START)
                                doSend(text)
                            }
                        )
                    }
                    .setNegativeButton("Send Offline") { _, _ ->
                        doSend(text)
                    }
                    .show()
                return@setOnClickListener
            }

            // Check if Bluetooth was turned off while Mesh was supposed to be running
            if (!com.resqnet.app.mesh.MeshPrerequisitesHelper.isBluetoothEnabled(this)) {
                com.resqnet.app.mesh.MeshPrerequisitesHelper.checkAndPrompt(
                    activity = this,
                    onRequestPermissions = { permissionLauncher.launch(PermissionHelper.getRequiredPermissions()) },
                    onReadyToStart = {
                        MeshService.command(this, MeshService.ACTION_START)
                        doSend(text)
                    }
                )
                return@setOnClickListener
            }

            doSend(text)
        }

        // Mesh status bar
        val statusDot = findViewById<View>(R.id.statusDot)
        val meshStatusView = findViewById<TextView>(R.id.meshStatus)
        val peerCountView = findViewById<TextView>(R.id.peerCount)

        val meshButton = findViewById<Button>(R.id.meshButton)
        meshButton.setOnClickListener {
            if (MeshRuntime.state.value.active) {
                showStopMeshDialog()
            } else {
                com.resqnet.app.mesh.MeshPrerequisitesHelper.checkAndPrompt(
                    activity = this,
                    onRequestPermissions = { permissionLauncher.launch(PermissionHelper.getRequiredPermissions()) },
                    onReadyToStart = {
                        MeshService.command(this, MeshService.ACTION_START)
                    }
                )
            }
        }

        // Bottom Navigation
        setupBottomNav(this, R.id.nav_chat)

        // Show emergency feature tutorial once on first launch
        if (!TutorialManager.isTutorialCompleted(this)) {
            window.decorView.postDelayed({
                TutorialManager.showTutorial(this)
            }, 600)
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
                        meshButton.text = if (isActive) "STOP" else "START"
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        TutorialManager.checkAndResumeTour(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        TutorialManager.checkAndResumeTour(this)
    }

    private fun showStopMeshDialog() {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val view = layoutInflater.inflate(R.layout.dialog_stop_mesh, null)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        val slideButton = view.findViewById<SlideButton>(R.id.stopMeshSlideButton)
        val btnCancel = view.findViewById<Button>(R.id.btnCancelStop)

        slideButton.setAlertTheme()
        slideButton.setText("Slide to stop mesh  ❯❯❯")

        slideButton.onSlideCompleteListener = {
            dialog.dismiss()
            com.resqnet.app.mesh.MeshService.command(this, com.resqnet.app.mesh.MeshService.ACTION_STOP)
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
}
