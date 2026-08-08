package com.resqnet.app.mesh

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.resqnet.app.R
import com.resqnet.app.ResQNetApplication
import com.resqnet.app.mesh.ble.BleMeshTransport
import com.resqnet.app.ui.ChatActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MeshService : Service() {
    companion object {
        const val ACTION_START = "com.resqnet.app.mesh.START"
        const val ACTION_STOP = "com.resqnet.app.mesh.STOP"
        const val ACTION_SYNC = "com.resqnet.app.mesh.SYNC"
        private const val CHANNEL = "resqnet_mesh"
        private const val NOTIFICATION_ID = 47

        fun command(context: Context, action: String) {
            val intent = Intent(context, MeshService::class.java).setAction(action)
            if (action == ACTION_START) context.startForegroundService(intent) else context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private lateinit var coordinator: MeshCoordinator
    @Volatile private var requestedActive = false

    private val radioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (
                    intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                ) {
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF ->
                        scope.launch { pauseForBluetooth() }
                    BluetoothAdapter.STATE_ON ->
                        scope.launch { recoverMesh("Bluetooth restored") }
                }
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> scope.launch {
                    // Some devices preserve Bluetooth in airplane mode but still invalidate
                    // existing BLE scan and advertising sessions. Recreate both sessions.
                    delay(1_000)
                    recoverMesh("Airplane-mode radio reset")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as ResQNetApplication
        val transport = BleMeshTransport(this, app.signer.nodeId, app.profile)
        coordinator = MeshCoordinator(transport, app.router, scope)
        ContextCompat.registerReceiver(
            this,
            radioReceiver,
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                requestedActive = false
                scope.launch { lifecycleMutex.withLock { coordinator.stop() }; MeshRuntime.active(false, "Mesh stopped"); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
            ACTION_SYNC -> scope.launch { coordinator.syncNow() }
            else -> {
                requestedActive = true
                startForeground(NOTIFICATION_ID, notification())
                MeshRuntime.active(true, "Starting BLE mesh…")
                scope.launch {
                    runCatching { lifecycleMutex.withLock { coordinator.start() } }
                        .onSuccess { MeshRuntime.active(true, "Mesh active") }
                        .onFailure { MeshRuntime.active(false, "Mesh error"); MeshRuntime.event(it.message ?: "Mesh start failed") }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(radioReceiver) }
        runBlocking(Dispatchers.IO) { lifecycleMutex.withLock { coordinator.stop() } }
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private suspend fun pauseForBluetooth() {
        if (!requestedActive) return
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        if (adapter?.isEnabled == true) return
        lifecycleMutex.withLock { coordinator.stop() }
        MeshRuntime.active(true, "Bluetooth unavailable — waiting to reconnect")
        MeshRuntime.event("Bluetooth radio stopped; mesh paused")
    }

    @SuppressLint("MissingPermission")
    private suspend fun recoverMesh(reason: String) {
        if (!requestedActive) return
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        if (adapter == null || !adapter.isEnabled) {
            MeshRuntime.active(true, "Bluetooth unavailable — waiting to reconnect")
            return
        }
        lifecycleMutex.withLock {
            coordinator.stop()
            delay(350)
            runCatching { coordinator.start() }
                .onSuccess {
                    MeshRuntime.active(true, "Mesh active")
                    MeshRuntime.event("$reason; BLE mesh restarted")
                }
                .onFailure {
                    MeshRuntime.active(true, "Mesh recovery failed")
                    MeshRuntime.event("Mesh recovery failed: ${it.message}")
                }
        }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.mesh_notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(): Notification {
        val content = PendingIntent.getActivity(this, 0, Intent(this, ChatActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, MeshService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mesh)
            .setContentTitle(getString(R.string.mesh_notification_title))
            .setContentText(getString(R.string.mesh_notification_text))
            .setOngoing(true).setContentIntent(content)
            .addAction(0, getString(R.string.stop_mesh), stop).build()
    }
}
