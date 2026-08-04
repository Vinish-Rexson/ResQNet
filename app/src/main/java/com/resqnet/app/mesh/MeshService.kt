package com.resqnet.app.mesh

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.resqnet.app.R
import com.resqnet.app.ResQNetApplication
import com.resqnet.app.mesh.ble.BleMeshTransport
import com.resqnet.app.ui.ChatActivity
import kotlinx.coroutines.*

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
    private lateinit var coordinator: MeshCoordinator

    override fun onCreate() {
        super.onCreate()
        val app = application as ResQNetApplication
        val transport = BleMeshTransport(this, app.signer.nodeId, app.profile)
        coordinator = MeshCoordinator(transport, app.router, scope)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> scope.launch { coordinator.stop(); MeshRuntime.active(false, "Mesh stopped"); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            ACTION_SYNC -> scope.launch { coordinator.syncNow() }
            else -> {
                startForeground(NOTIFICATION_ID, notification())
                MeshRuntime.active(true, "Starting BLE mesh…")
                scope.launch {
                    runCatching { coordinator.start() }
                        .onSuccess { MeshRuntime.active(true, "Mesh active") }
                        .onFailure { MeshRuntime.active(false, "Mesh error"); MeshRuntime.event(it.message ?: "Mesh start failed") }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() { runBlocking(Dispatchers.IO) { coordinator.stop() }; scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

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
