package com.akinus21.akaiagent

import android.app.*
import android.content.Context
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

    private var tunnelThread: Thread? = null
    private var rpcProcess: Process? = null
    private var tunnelResult: Int = 0

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
        val host = intent.getStringExtra("tunnel_host") ?: run { stopSelf(); return }
        val port = intent.getIntExtra("tunnel_port", 443)
        val workerId = intent.getStringExtra("worker_id") ?: run { stopSelf(); return }
        val rpcPort = intent.getIntExtra("rpc_port", 50052)

        showNotification("Starting worker...")

        try {
            rpcProcess = RpcServerManager.start(this, rpcPort)
            Log.i(TAG, "rpc-server started on port $rpcPort")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start rpc-server: ${e.message}")
            showNotification("rpc-server failed: ${e.message}")
            return
        }

        Thread {
            Log.i(TAG, "Starting tunnel thread to $host:$port")
            tunnelResult = TunnelNative.connect(host, port, workerId, rpcPort)
            Log.i(TAG, "Tunnel exited with result: $tunnelResult")
        }.also { tunnelThread = it }.start()

        showNotification("Running: $host")
    }

    private fun stopWorker() {
        tunnelThread?.interrupt()
        tunnelThread = null
        RpcServerManager.stop()
        rpcProcess = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("akai-agent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .addAction(0, "Stop", stopPendingIntent())
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(this, WorkerService::class.java).apply {
            action = "ACTION_STOP"
        }
        return PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
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
        RpcServerManager.stop()
        tunnelThread?.interrupt()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        RpcServerManager.stop()
        tunnelThread?.interrupt()
        super.onTaskRemoved(rootIntent)
    }
}