package com.resqnet.app.ui

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.resqnet.app.*
import com.resqnet.app.mesh.MeshRuntime
import kotlinx.coroutines.launch

class DiagnosticsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_diagnostics); title = "Diagnostics"
        val app = application as ResQNetApplication
        findViewById<TextView>(R.id.nodeDetails).text = "Node ${app.signer.nodeId}\nFingerprint ${app.signer.fingerprint}\nProtocol v1"
        val enabled = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.topologySwitch).apply { isChecked = app.profile.demoTopologyEnabled }
        val roles = findViewById<RadioGroup>(R.id.roleGroup)
        roles.check(when (app.profile.demoRole) { DemoRole.A -> R.id.roleA; DemoRole.B -> R.id.roleB; DemoRole.C -> R.id.roleC; else -> R.id.roleNone })
        enabled.setOnCheckedChangeListener { _, checked -> app.profile.demoTopologyEnabled = checked }
        roles.setOnCheckedChangeListener { _, id -> app.profile.demoRole = when (id) {
            R.id.roleA -> DemoRole.A; R.id.roleB -> DemoRole.B; R.id.roleC -> DemoRole.C; else -> DemoRole.NONE
        } }
        findViewById<Button>(R.id.clearEvents).setOnClickListener { MeshRuntime.clearEvents() }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { MeshRuntime.state.collect {
            findViewById<TextView>(R.id.eventLog).text = it.events.ifEmpty { listOf("No mesh events yet") }.joinToString("\n")
        } } }
    }
}
