package com.akinus21.akaiagent

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class WorkerService : Service() {
    private val TAG = "akai-agent"
    private val CHANNEL_ID = "akai_agent_channel"
    private val NOTIFICATION_ID = 1

    private var tunnelJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_START" -> startWorker(intent)
            "ACTION_STOP" -> stopWorker()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startWorker(intent: Intent) {
        val host = intent.getStringExtra("tunnel_host") ?: return
        val port = intent.getIntExtra("tunnel_port", 443)
        val workerId = intent.getStringExtra("worker_id") ?: return
        val rpcPort = intent.getIntExtra("rpc_port", 50052)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("akai-agent")
            .setContentText("Connected to $host")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .addAction(0, "Stop", stopPendingIntent())
            .build()

        startForeground(NOTIFICATION_ID, notification)

        tunnelJob?.cancel()
        tunnelJob = scope.launch {
            withContext(Dispatchers.IO) {
                val result = TunnelNative.connect(host, port, workerId, rpcPort)
                Log.i(TAG, "tunnel disconnected with result: $result")
            }
        }

        updateNotification("Connected to $host")
    }

    private fun stopWorker() {
        tunnelJob?.cancel()
        tunnelJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(this, WorkerService::class.java).apply {
            action = "ACTION_STOP"
        }
        return PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("akai-agent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .addAction(0, "Stop", stopPendingIntent())
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "akai-agent Worker",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "akai-agent distributed inference worker"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}