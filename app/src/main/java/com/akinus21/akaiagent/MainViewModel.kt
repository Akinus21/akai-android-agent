package com.akinus21.akaiagent

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

sealed class WorkerState {
    object Idle : WorkerState()
    object Initializing : WorkerState()
    object FetchingCerts : WorkerState()
    object StartingPetals : WorkerState()
    object Connecting : WorkerState()
    data class Running(val host: String, val model: String) : WorkerState()
    data class Error(val message: String) : WorkerState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "akai-agent"
    private val _state = MutableStateFlow<WorkerState>(WorkerState.Idle)
    val state: StateFlow<WorkerState> = _state

    private val prefs = application.getSharedPreferences("akai_agent", 0)
    private val ctx = application

    private var currentModel: String = ""

    val savedQueueUrl: String get() = prefs.getString("queue_url", "") ?: ""
    val savedUsername: String get() = prefs.getString("username", "") ?: ""
    val savedTunnelHost: String get() = prefs.getString("tunnel_host", "") ?: ""
    val savedTunnelPort: Int get() = prefs.getInt("tunnel_port", 443)

    fun initAndStart(queueUrl: String, username: String) {
        viewModelScope.launch {
            try {
                _state.value = WorkerState.Initializing
                Log.i(TAG, "Initializing with queue=$queueUrl username=$username")

                val deviceName = android.os.Build.MODEL.replace(" ", "-").lowercase()
                val initResult = withContext(Dispatchers.IO) {
                    TunnelNative.init(queueUrl, username, deviceName)
                }

                when (initResult) {
                    0 -> {
                        Log.i(TAG, "Init successful")

                        readTunnelConfigFromRust()?.let { (host, port) ->
                            prefs.edit()
                                .putString("tunnel_host", host)
                                .putInt("tunnel_port", port)
                                .apply()
                        }

                        prefs.edit()
                            .putString("queue_url", queueUrl)
                            .putString("username", username)
                            .apply()

                        val host = prefs.getString("tunnel_host", null) ?: "tunnel.akinus21.com"
                        val port = prefs.getInt("tunnel_port", 443)
                        val workerId = "$username:$deviceName"

                        // Start heartbeat polling to get model
                        startHeartbeatPolling(queueUrl, username, workerId, host, port)

                    } else -> {
                        _state.value = WorkerState.Error("Init failed: $initResult")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Init error", e)
                _state.value = WorkerState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun startHeartbeatPolling(queueUrl: String, username: String, workerId: String, tunnelHost: String, tunnelPort: Int) {
        viewModelScope.launch {
            while (true) {
                try {
                    val model = pollForModel(queueUrl, username, workerId)
                    if (model.isNotEmpty() && model != currentModel) {
                        Log.i(TAG, "Model changed: $currentModel -> $model")
                        currentModel = model

                        _state.value = WorkerState.StartingPetals
                        withContext(Dispatchers.IO) {
                            PetalsServerManager.stop()
                            PetalsServerManager.start(ctx, model, 50052)
                        }
                        Log.i(TAG, "Petals started for model: $model")

                        _state.value = WorkerState.Running(tunnelHost, model)

                        // Start tunnel connection
                        startWorkerService(tunnelHost, tunnelPort, workerId, 50052)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat poll error: ${e.message}")
                }
                kotlinx.coroutines.delay(30_000) // Poll every 30 seconds
            }
        }
    }

    private fun pollForModel(queueUrl: String, username: String, workerId: String): String {
        // Call native heartbeat to get current model from queue
        return try {
            val result = TunnelNative.heartbeat(queueUrl, username, workerId)
            if (result != null && result.isNotEmpty()) {
                prefs.edit().putString("current_model", result).apply()
                result
            } else {
                prefs.getString("current_model", "") ?: ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "Heartbeat poll failed: ${e.message}")
            prefs.getString("current_model", "") ?: ""
        }
    }

    fun startWithSavedConfig() {
        val host = savedTunnelHost.ifEmpty { "tunnel.akinus21.com" }
        val port = savedTunnelPort
        val username = savedUsername
        val deviceName = android.os.Build.MODEL.replace(" ", "-").lowercase()
        val workerId = "$username:$deviceName"

        viewModelScope.launch {
            try {
                _state.value = WorkerState.StartingPetals
                val model = prefs.getString("current_model", "meta-llama/Meta-Llama-3.1-8B-Instruct") ?: "meta-llama/Meta-Llama-3.1-8B-Instruct"
                currentModel = model

                withContext(Dispatchers.IO) {
                    PetalsServerManager.start(ctx, model, 50052)
                }

                _state.value = WorkerState.Running(host, model)
                startWorkerService(host, port, workerId, 50052)
            } catch (e: Exception) {
                _state.value = WorkerState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun startWorkerService(host: String, port: Int, workerId: String, rpcPort: Int) {
        val intent = android.content.Intent(ctx, WorkerService::class.java).apply {
            action = "ACTION_START"
            putExtra("tunnel_host", host)
            putExtra("tunnel_port", port)
            putExtra("worker_id", workerId)
            putExtra("rpc_port", rpcPort)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
    }

    fun stopWorker() {
        PetalsServerManager.stop()
        ctx.stopService(android.content.Intent(ctx, WorkerService::class.java))
        _state.value = WorkerState.Idle
    }

    fun hasSavedConfig(): Boolean {
        return prefs.contains("queue_url") && prefs.contains("username")
    }

    fun updateModel(model: String) {
        prefs.edit().putString("current_model", model).apply()
        currentModel = model
    }

    private fun readTunnelConfigFromRust(): Pair<String, Int>? {
        return try {
            val file = java.io.File(ctx.filesDir, "akai-agent/android-prefs.json")
            if (!file.exists()) return null
            val json = file.readText()
            val obj = Gson().fromJson(json, Map::class.java) as Map<String, Any>
            val host = obj["tunnel_host"] as? String ?: return null
            val port = (obj["tunnel_port"] as? Double)?.toInt() ?: 443
            Pair(host, port)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read tunnel config: ${e.message}")
            null
        }
    }
}