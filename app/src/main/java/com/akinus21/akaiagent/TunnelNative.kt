package com.akinus21.akaiagent

object TunnelNative {
    init {
        System.loadLibrary("akai_tunnel")
    }

    fun init(queueUrl: String, username: String): Int {
        return nativeInit(queueUrl, username)
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

    private external fun nativeInit(queueUrl: String, username: String): Int
    private external fun nativeConnect(host: String, port: Int, workerId: String, rpcPort: Int): Int
    private external fun nativeGetPublicKey(): String?
    private external fun nativeSignRequest(message: String): String?
}