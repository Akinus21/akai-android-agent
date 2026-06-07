package com.akinus21.akaiagent

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class WorkerState {
    object Idle : WorkerState()
    object EnrollingVpn : WorkerState()
    object EnrollingVpnFailed : WorkerState()
    object StartingWorker : WorkerState()
    data class Running(val hubAddr: String, val model: String) : WorkerState()
    data class Error(val message: String) : WorkerState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "akai-agent"
    private val _state = MutableStateFlow<WorkerState>(WorkerState.Idle)
    val state: StateFlow<WorkerState> = _state

    private val prefs = application.getSharedPreferences("akai_agent", 0)
    private val ctx = application

    private var currentModel: String = ""

    val savedHubAddr: String get() = prefs.getString("hub_addr", "") ?: ""
    val savedApiUrl: String get() = prefs.getString("api_url", "") ?: ""
    val savedUsername: String get() = prefs.getString("username", "") ?: ""

    fun initAndStart(apiUrl: String, username: String) {
        viewModelScope.launch {
            try {
                _state.value = WorkerState.EnrollingVpn
                Log.i(TAG, "Enrolling VPN: apiUrl=$apiUrl username=$username")

                val deviceName = android.os.Build.MODEL.replace(" ", "-").lowercase()
                val workerName = deviceName
                val workerId = "$username:$deviceName"

                TunnelNative.load(ctx)
                val enrollResult = withContext(Dispatchers.IO) {
                    TunnelNative.enrollVpn(apiUrl, username, workerName)
                }

                if (enrollResult == null) {
                    _state.value = WorkerState.EnrollingVpnFailed
                    _state.value = WorkerState.Error("VPN enrollment failed")
                    return@launch
                }

                val hubAddr = enrollResult.hubVpnAddr
                Log.i(TAG, "VPN enrolled, hub at $hubAddr, wg config: ${enrollResult.wireguardConfig.isNotEmpty()}")

                prefs.edit()
                    .putString("api_url", apiUrl)
                    .putString("username", username)
                    .putString("hub_addr", hubAddr)
                    .putString("worker_id", workerId)
                    .apply()

                startWorkerWithHub(hubAddr, workerId)

            } catch (e: Exception) {
                Log.e(TAG, "Init error", e)
                _state.value = WorkerState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun startWithSavedConfig() {
        val hubAddr = savedHubAddr.ifEmpty { return }
        val workerId = prefs.getString("worker_id", "") ?: ""

        viewModelScope.launch {
            try {
                _state.value = WorkerState.StartingWorker
                TunnelNative.load(ctx)
                startWorkerWithHub(hubAddr, workerId)
            } catch (e: Exception) {
                _state.value = WorkerState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun startWorkerWithHub(hubAddr: String, workerId: String) {
        _state.value = WorkerState.StartingWorker

        val hasGpu = false // Android typically CPU-only
        val vramGb = "0.0"

        val intent = android.content.Intent(ctx, WorkerService::class.java).apply {
            action = "ACTION_START"
            putExtra("mode", "v2")
            putExtra("hub_addr", hubAddr)
            putExtra("worker_id", workerId)
            putExtra("has_gpu", hasGpu)
            putExtra("vram_gb", vramGb)
            putExtra("rpc_port", 50052)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }

        _state.value = WorkerState.Running(hubAddr, currentModel.ifEmpty { "pending" })
    }

    fun stopWorker() {
        RpcServerManager.stop()
        ctx.stopService(android.content.Intent(ctx, WorkerService::class.java))
        _state.value = WorkerState.Idle
    }

    fun directConnect(hubAddr: String, username: String) {
        val deviceName = android.os.Build.MODEL.replace(" ", "-").lowercase()
        val workerId = "$username:$deviceName"

        prefs.edit()
            .putString("hub_addr", hubAddr)
            .putString("username", username)
            .putString("worker_id", workerId)
            .apply()

        viewModelScope.launch {
            try {
                _state.value = WorkerState.StartingWorker
                TunnelNative.load(ctx)
                startWorkerWithHub(hubAddr, workerId)
            } catch (e: Exception) {
                _state.value = WorkerState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun hasSavedConfig(): Boolean {
        return prefs.contains("hub_addr") && prefs.contains("username")
    }

    fun updateModel(model: String) {
        prefs.edit().putString("current_model", model).apply()
        currentModel = model
    }
}