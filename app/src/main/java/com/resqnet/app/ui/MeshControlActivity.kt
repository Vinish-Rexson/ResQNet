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
        val app = application as ResQNetApplication
        findViewById<TextView>(R.id.identityText).text = "${app.profile.displayName}\n${app.signer.fingerprint}"
        findViewById<Button>(R.id.permissionButton).setOnClickListener { permissions.launch(requiredPermissions()) }
        findViewById<Button>(R.id.startMeshButton).setOnClickListener {
            if (requiredPermissions().all { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED })
                MeshService.command(this, MeshService.ACTION_START)
            else permissions.launch(requiredPermissions())
        }
        findViewById<Button>(R.id.stopMeshButton).setOnClickListener { MeshService.command(this, MeshService.ACTION_STOP) }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { MeshRuntime.state.collect {
            findViewById<TextView>(R.id.controlStatus).text = "${it.status}\nNearby connections: ${it.peerCount}"
        } } }
    }
    override fun onResume() { super.onResume(); updatePermissionText() }
    private fun updatePermissionText() {
        val granted = requiredPermissions().all { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        findViewById<TextView>(R.id.permissionStatus).text = if (granted) "Nearby devices permission granted" else "Nearby devices permission required"
    }
    private fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) arrayOf(
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT
    ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}
