package com.akinus21.akaiagent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class WorkerState {
    object Idle : WorkerState()
    object Initializing : WorkerState()
    data class Connected(val host: String) : WorkerState()
    data class Error(val message: String) : WorkerState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<WorkerState>(WorkerState.Idle)
    val state: StateFlow<WorkerState> = _state

    private val prefs = application.getSharedPreferences("akai_agent", 0)

    fun init(queueUrl: String, username: String) {
        viewModelScope.launch {
            _state.value = WorkerState.Initializing
            val result = TunnelNative.init(queueUrl, username)
            when (result) {
                0 -> {
                    prefs.edit()
                        .putString("queue_url", queueUrl)
                        .putString("username", username)
                        .apply()
                    _state.value = WorkerState.Connected("initialized")
                }
                else -> _state.value = WorkerState.Error("Init failed: $result")
            }
        }
    }

    fun startWorker(host: String, port: Int, workerId: String, rpcPort: Int) {
        val ctx = getApplication<Application>()
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
        _state.value = WorkerState.Connected(host)
    }

    fun stopWorker() {
        val ctx = getApplication<Application>()
        ctx.stopService(android.content.Intent(ctx, WorkerService::class.java))
        _state.value = WorkerState.Idle
    }
}