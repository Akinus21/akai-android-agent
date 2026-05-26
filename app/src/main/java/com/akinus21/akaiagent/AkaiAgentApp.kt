package com.akinus21.akaiagent

import android.app.Application

class AkaiAgentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("akai_tunnel")
    }
}