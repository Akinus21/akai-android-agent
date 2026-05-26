package com.akinus21.akaiagent

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object RpcServerManager {
    private const val TAG = "akai-agent"
    private const val RPC_BINARY = "rpc-server"

    private var process: Process? = null

    fun getBinaryPath(context: Context): File {
        return File(context.filesDir, RPC_BINARY)
    }

    fun ensureBinary(context: Context): File {
        val target = getBinaryPath(context)
        if (target.exists() && target.canExecute()) {
            return target
        }

        val abi = if (android.os.Build.SUPPORTED_ABIS.isNotEmpty()) {
            android.os.Build.SUPPORTED_ABIS[0]
        } else {
            "arm64-v8a"
        }

        val assetPath = "rpc-server/${abi}/${RPC_BINARY}"
        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No bundled rpc-server for $abi, trying generic: $e")
            try {
                context.assets.open("rpc-server/${RPC_BINARY}").use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e2: Exception) {
                Log.e(TAG, "No rpc-server asset found: $e2")
                throw IllegalStateException("rpc-server binary not available for $abi")
            }
        }

        target.setExecutable(true)
        target.setReadable(true)
        target.setWritable(true)
        return target
    }

    fun start(context: Context, port: Int): Process {
        stop()
        val binary = ensureBinary(context)
        val gpuBackend = detectGpuBackend()

        val cmd = mutableListOf(binary.absolutePath, "--host", "127.0.0.1", "--port", port.toString())
        if (gpuBackend != "cpu") {
            cmd.addAll(listOf("--gpu", gpuBackend))
        }

        Log.i(TAG, "Starting rpc-server: ${cmd.joinToString(" ")}")
        val pb = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .directory(context.filesDir)

        val env = pb.environment()
        env["LD_LIBRARY_PATH"] = "${context.applicationInfo.nativeLibraryDir}:${env.get("LD_LIBRARY_PATH") ?: ""}"

        val proc = pb.start()
        process = proc

        Thread {
            try {
                val reader = proc.inputStream.bufferedReader()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    Log.d(TAG, "rpc-server: $line")
                }
            } catch (_: Exception) {}
        }.start()

        return proc
    }

    fun stop() {
        process?.destroy()
        process = null
    }

    fun isRunning(): Boolean {
        return process?.isAlive == true
    }

    private fun detectGpuBackend(): String {
        if (hasAdrenoGpu()) return "adreno"
        if (hasMaliGpu()) return "mali"
        return "cpu"
    }

    private fun hasAdrenoGpu(): Boolean {
        val files = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_model",
            "/sys/class/kgsl/kgsl-3d0/device/of_node/compatible"
        )
        for (f in files) {
            try {
                val content = File(f).readText().lowercase()
                if (content.contains("adreno") || content.contains("qcom")) return true
            } catch (_: Exception) {}
        }
        val gpuRenderer = android.os.Build.HARDWARE.lowercase()
        return gpuRenderer.contains("adreno") || gpuRenderer.contains("qcom")
    }

    private fun hasMaliGpu(): Boolean {
        val files = listOf(
            "/sys/class/misc/mali0/device/of_node/compatible",
            "/sys/class/gpu/gpu_model"
        )
        for (f in files) {
            try {
                val content = File(f).readText().lowercase()
                if (content.contains("mali")) return true
            } catch (_: Exception) {}
        }
        val gpuRenderer = android.os.Build.HARDWARE.lowercase()
        return gpuRenderer.contains("mali") || gpuRenderer.contains("arm")
    }
}