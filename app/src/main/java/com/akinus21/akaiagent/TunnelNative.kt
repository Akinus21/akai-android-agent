package com.akinus21.akaiagent

import android.content.Context

object TunnelNative {
    external fun nativeSetDataDir(dataDir: String)
    external fun nativeInit(queueUrl: String, username: String, deviceName: String): Int
    external fun nativeConnect(host: String, port: Int, workerId: String, rpcPort: Int): Int
    external fun nativeGetPublicKey(): String?
    external fun nativeSignRequest(message: String): String?
    external fun nativeHeartbeat(queueUrl: String, username: String, workerId: String): String?
    external fun nativeEnrollVpn(apiUrl: String, username: String, workerName: String): String?
    external fun nativeStartWorker(hubAddr: String, workerId: String, hasGpu: Boolean, vramGb: String, rpcPort: Int): Int

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        try {
            System.loadLibrary("akai_tunnel_android")
            nativeSetDataDir(context.filesDir.absolutePath + "/akai-agent")
            loaded = true
        } catch (e: UnsatisfiedLinkError) {
            throw RuntimeException("Failed to load native library: ${e.message}", e)
        }
    }

    fun init(queueUrl: String, username: String, deviceName: String): Int {
        return nativeInit(queueUrl, username, deviceName)
    }

    fun connect(host: String, port: Int, workerId: String, rpcPort: Int): Int {
        return nativeConnect(host, port, workerId, rpcPort)
    }

    fun getPublicKey(): String? {
        return nativeGetPublicKey()
    }

    fun signRequest(message: String): String? {
        return nativeSignRequest(message)
    }

    fun heartbeat(queueUrl: String, username: String, workerId: String): String? {
        return nativeHeartbeat(queueUrl, username, workerId)
    }

    data class VpnEnrollResult(val hubVpnAddr: String, val wireguardConfig: String)

    fun enrollVpn(apiUrl: String, username: String, workerName: String): VpnEnrollResult? {
        val json = nativeEnrollVpn(apiUrl, username, workerName) ?: return null
        return try {
            val obj = org.json.JSONObject(json)
            VpnEnrollResult(
                hubVpnAddr = obj.optString("hub_vpn_addr", ""),
                wireguardConfig = obj.optString("wireguard_config", "")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun startWorker(hubAddr: String, workerId: String, hasGpu: Boolean, vramGb: String, rpcPort: Int): Int {
        return nativeStartWorker(hubAddr, workerId, hasGpu, vramGb, rpcPort)
    }
}
    }

    fun init(queueUrl: String, username: String, deviceName: String): Int {
        return nativeInit(queueUrl, username, deviceName)
    }

    fun connect(host: String, port: Int, workerId: String, rpcPort: Int): Int {
        return nativeConnect(host, port, workerId, rpcPort)
    }

    fun getPublicKey(): String? {
        return nativeGetPublicKey()
    }

    fun signRequest(message: String): String? {
        return nativeSignRequest(message)
    }

    fun heartbeat(queueUrl: String, username: String, workerId: String): String? {
        return nativeHeartbeat(queueUrl, username, workerId)
    }

    fun enrollVpn(apiUrl: String, username: String, workerName: String): String? {
        return nativeEnrollVpn(apiUrl, username, workerName)
    }

    fun startWorker(hubAddr: String, workerId: String, hasGpu: Boolean, vramGb: String, rpcPort: Int): Int {
        return nativeStartWorker(hubAddr, workerId, hasGpu, vramGb, rpcPort)
    }
}