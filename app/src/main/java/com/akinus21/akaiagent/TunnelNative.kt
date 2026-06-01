package com.akinus21.akaiagent

import android.content.Context

object TunnelNative {
    external fun nativeSetDataDir(dataDir: String)
    external fun nativeInit(queueUrl: String, username: String, deviceName: String): Int
    external fun nativeConnect(host: String, port: Int, workerId: String, rpcPort: Int): Int
    external fun nativeGetPublicKey(): String?
    external fun nativeSignRequest(message: String): String?
    external fun nativeHeartbeat(queueUrl: String, username: String, workerId: String): String?

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
}