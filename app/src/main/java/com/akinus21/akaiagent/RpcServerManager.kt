package com.akinus21.akaiagent

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object RpcServerManager {
    private const val TAG = "akai-agent"
    private const val RPC_BINARY = "rpc-server"
    private const val BIN_DIR = "rpc-bin"

    private var process: Process? = null

    fun getBinaryPath(context: Context): File {
        return File(context.filesDir, BIN_DIR)
    }

    fun ensureBinary(context: Context): File {
        val targetDir = File(context.filesDir, BIN_DIR)
        targetDir.mkdirs()

        val binary = File(targetDir, RPC_BINARY)
        if (binary.exists() && binary.length() > 0) {
            return binary
        }

        val abi = if (android.os.Build.SUPPORTED_ABIS.isNotEmpty()) {
            android.os.Build.SUPPORTED_ABIS[0]
        } else {
            "arm64-v8a"
        }

        val assetPath = "rpc-server/${abi}/${RPC_BINARY}"
        try {
            context.assets.open(assetPath).use { input ->
                copyBinary(input, binary)
            }
        } catch (e: Exception) {
            Log.w(TAG, "No bundled rpc-server for $abi, trying generic: $e")
            try {
                context.assets.open("rpc-server/${RPC_BINARY}").use { input ->
                    copyBinary(input, binary)
                }
            } catch (e2: Exception) {
                Log.e(TAG, "No rpc-server asset found: $e2")
                throw IllegalStateException("rpc-server binary not available for $abi")
            }
        }

        Log.i(TAG, "rpc-server: path=${binary.absolutePath} size=${binary.length()}")
        return binary
    }

    private fun copyBinary(input: InputStream, target: File) {
        FileOutputStream(target).use { output ->
            input.copyTo(output)
        }
    }

    fun copyToTmpAndGetPath(context: Context, binary: File): File {
        val tmpDir = File(context.cacheDir, "rpc_bin")
        tmpDir.mkdirs()
        val tmpBinary = File(tmpDir, RPC_BINARY)

        if (tmpBinary.exists()) tmpBinary.delete()
        binary.copyTo(tmpBinary, overwrite = true)

        Runtime.getRuntime().exec(arrayOf("chmod", "755", tmpBinary.absolutePath)).waitFor()
        Log.i(TAG, "Copied to tmp: ${tmpBinary.absolutePath} size=${tmpBinary.length()}")
        return tmpBinary
    }

    fun start(context: Context, port: Int): Process {
        stop()
        val binary = ensureBinary(context)
        val execBinary = copyToTmpAndGetPath(context, binary)

        val cmd = "${execBinary.absolutePath} --host 127.0.0.1 --port $port"
        Log.i(TAG, "Starting rpc-server via Runtime.exec: $cmd")

        val envArray = System.getenv().entries.map { "${it.key}=${it.value}" }.toTypedArray()

        val proc = Runtime.getRuntime().exec(arrayOf("/bin/sh", "-c", cmd), envArray)
        process = proc

        Thread {
            try {
                val reader = proc.inputStream.bufferedReader()
                var line: String? = reader.readLine()
                while (line != null) {
                    Log.i(TAG, "rpc-server: $line")
                    line = reader.readLine()
                }
            } catch (_: Exception) {}

            val exitCode = try { proc.waitFor() } catch (_: Exception) { -1 }
            Log.i(TAG, "rpc-server exited with code $exitCode")
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
}
