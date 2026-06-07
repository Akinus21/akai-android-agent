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

    private var workerThread: Thread? = null
    private var rpcProcess: Process? = null

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
        val mode = intent.getStringExtra("mode") ?: "v2"
        val rpcPort = intent.getIntExtra("rpc_port", 50052)

        showNotification("Starting worker ($mode)...")

        try {
            rpcProcess = RpcServerManager.start(this, rpcPort)
            Log.i(TAG, "rpc-server started on port $rpcPort")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start rpc-server: ${e.message}")
            showNotification("rpc-server failed: ${e.message}")
            return
        }

        when (mode) {
            "v1" -> startTunnelWorker(intent)
            else -> startV2Worker(intent)
        }
    }

    private fun startV2Worker(intent: Intent) {
        val hubAddr = intent.getStringExtra("hub_addr") ?: run { stopSelf(); return }
        val workerId = intent.getStringExtra("worker_id") ?: run { stopSelf(); return }
        val hasGpu = intent.getBooleanExtra("has_gpu", false)
        val vramGb = intent.getStringExtra("vram_gb") ?: "0.0"

        Thread {
            Log.i(TAG, "Starting v2 worker: hub=$hubAddr, worker=$workerId, gpu=$hasGpu")
            val result = TunnelNative.startWorker(hubAddr, workerId, hasGpu, vramGb, 50052)
            Log.i(TAG, "v2 worker exited with result: $result")
        }.also { workerThread = it }.start()

        showNotification("Running: $hubAddr")
    }

    private fun startTunnelWorker(intent: Intent) {
        val host = intent.getStringExtra("tunnel_host") ?: "tunnel.akinus21.com"
        val port = intent.getIntExtra("tunnel_port", 443)
        val workerId = intent.getStringExtra("worker_id") ?: run { stopSelf(); return }

        Thread {
            Log.i(TAG, "Starting tunnel thread to $host:$port")
            val result = TunnelNative.connect(host, port, workerId, 50052)
            Log.i(TAG, "Tunnel exited with result: $result")
        }.also { workerThread = it }.start()

        showNotification("Running: $host")
    }

    private fun stopWorker() {
        workerThread?.interrupt()
        workerThread = null
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
        workerThread?.interrupt()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        RpcServerManager.stop()
        workerThread?.interrupt()
        super.onTaskRemoved(rootIntent)
    }
}