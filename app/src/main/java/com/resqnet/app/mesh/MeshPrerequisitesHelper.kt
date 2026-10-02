package com.resqnet.app.mesh

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.provider.Settings
import androidx.core.location.LocationManagerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.resqnet.app.ui.PermissionHelper

object MeshPrerequisitesHelper {

    fun isBluetoothEnabled(context: Context): Boolean {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return false
        val adapter = manager.adapter ?: return false
        return adapter.isEnabled
    }

    fun isLocationEnabled(context: Context): Boolean {
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    /**
     * Verifies that all prerequisites (Permissions, Bluetooth radio, Location services)
     * are satisfied before turning on the BLE mesh.
     *
     * If anything is missing, prompts the user with an actionable dialog or permission request.
     */
    fun checkAndPrompt(
        activity: Activity,
        onRequestPermissions: () -> Unit,
        onReadyToStart: () -> Unit
    ) {
        // 1. Check all required runtime permissions
        if (!PermissionHelper.hasPermissions(activity)) {
            onRequestPermissions()
            return
        }

        // 2. Check if Bluetooth is ON
        if (!isBluetoothEnabled(activity)) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("Bluetooth is Turned Off")
                .setMessage("ResQNet uses Bluetooth Low Energy to connect to nearby emergency nodes. Please turn on Bluetooth to start the mesh network.")
                .setPositiveButton("Turn On") { _, _ ->
                    runCatching {
                        activity.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    }.onFailure {
                        activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        // 3. Check if Location Services (GPS) are ON (mandatory for Android BLE discovery)
        if (!isLocationEnabled(activity)) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("Location Services Required")
                .setMessage("Android requires Location services to be enabled in order to discover nearby Bluetooth devices. Please turn on Location.")
                .setPositiveButton("Turn On") { _, _ ->
                    activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        // All prerequisites satisfied!
        onReadyToStart()
    }
}
