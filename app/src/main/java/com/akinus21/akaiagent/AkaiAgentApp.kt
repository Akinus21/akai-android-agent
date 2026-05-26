package com.akinus21.akaiagent

import android.app.Application
import android.util.Log

class AkaiAgentApp : Application() {
    private val TAG = "akai-agent"

    override fun onCreate() {
        super.onCreate()
        try {
            TunnelNative.load(this)
            Log.i(TAG, "Native library loaded")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load native library: ${e.message}")
        }
    }
}