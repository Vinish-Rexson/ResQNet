package com.resqnet.app.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.resqnet.app.R
import com.resqnet.app.ResQNetApplication
import com.resqnet.app.mesh.MeshRuntime
import com.resqnet.app.mesh.MeshService
import kotlinx.coroutines.launch

class MeshControlActivity : AppCompatActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { updatePermissionText() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_mesh_control); title = "Mesh control"
        val meshToolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(meshToolbar)
        meshToolbar.navigationIcon = null // top-level tab, no back button
        val app = application as ResQNetApplication
        findViewById<TextView>(R.id.identityText).text = "${app.profile.displayName}\n${app.signer.fingerprint}"
        findViewById<Button>(R.id.permissionButton).setOnClickListener { permissions.launch(PermissionHelper.getRequiredPermissions()) }
        findViewById<Button>(R.id.startMeshButton).setOnClickListener {
            if (PermissionHelper.hasPermissions(this))
                MeshService.command(this, MeshService.ACTION_START)
            else permissions.launch(PermissionHelper.getRequiredPermissions())
        }
        findViewById<Button>(R.id.stopMeshButton).setOnClickListener { MeshService.command(this, MeshService.ACTION_STOP) }
        
        findViewById<Button>(R.id.btnEditProfile)?.setOnClickListener {
            startActivity(android.content.Intent(this, SetupActivity::class.java).putExtra("IS_EDITING", true))
        }
        findViewById<Button>(R.id.btnLogout)?.setOnClickListener {
            LogoutManager.showLogoutDialog(this)
        }
        findViewById<Button>(R.id.btnOpenDiagnostics)?.setOnClickListener {
            startActivity(android.content.Intent(this, DiagnosticsActivity::class.java))
        }
        
        setupBottomNav(this, R.id.nav_mesh)
        
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { MeshRuntime.state.collect {
            findViewById<TextView>(R.id.controlStatus).text = "${it.status}\nNearby connections: ${it.peerCount}"
            
            val isActive = it.active
            findViewById<Button>(R.id.startMeshButton).visibility = if (isActive) android.view.View.GONE else android.view.View.VISIBLE
            findViewById<Button>(R.id.stopMeshButton).visibility = if (isActive) android.view.View.VISIBLE else android.view.View.GONE
        } } }
    }
    override fun onResume() { 
        super.onResume()
        val app = application as ResQNetApplication
        findViewById<TextView>(R.id.identityText).text = "${app.profile.displayName}\n${app.signer.fingerprint}"
        updatePermissionText()
        TutorialManager.checkAndResumeTour(this)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        TutorialManager.checkAndResumeTour(this)
    }
    private fun updatePermissionText() {
        val granted = PermissionHelper.hasPermissions(this)
        findViewById<TextView>(R.id.permissionStatus).text = if (granted) "All required permissions granted" else "Required permissions missing"
        findViewById<Button>(R.id.permissionButton).visibility = if (granted) android.view.View.GONE else android.view.View.VISIBLE
    }
}
