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
        if (binary.exists() && binary.canExecute()) {
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

        binary.setExecutable(true)
        Log.i(TAG, "rpc-server: path=${binary.absolutePath} size=${binary.length()} canExec=${binary.canExecute()}")
        return binary
    }

    private fun copyBinary(input: InputStream, target: File) {
        FileOutputStream(target).use { output ->
            input.copyTo(output)
        }
        target.setReadable(true, false)
        target.setWritable(true, false)
        target.setExecutable(true, false)
    }

    fun start(context: Context, port: Int): Process {
        stop()
        val binary = ensureBinary(context)

        val targetDir = File(context.filesDir, BIN_DIR)
        Runtime.getRuntime().exec(arrayOf("/system/bin/chmod", "755", targetDir.absolutePath)).waitFor()
        Runtime.getRuntime().exec(arrayOf("/system/bin/chmod", "755", binary.absolutePath)).waitFor()

        val execCmd = listOf("/system/bin/sh", "-c", "${binary.absolutePath} --host 127.0.0.1 --port $port")
        Log.i(TAG, "Starting rpc-server: ${execCmd.joinToString(" ")}")
        val pb = ProcessBuilder(execCmd)
            .redirectErrorStream(true)
            .directory(context.filesDir)
            .redirectErrorStream(true)
            .directory(context.filesDir)
            .redirectErrorStream(true)
            .directory(context.filesDir)

        val env = pb.environment()
        val ldPath = mutableListOf(context.applicationInfo.nativeLibraryDir)
        val systemLibs = listOf("/system/lib64", "/system/lib", "/vendor/lib64", "/vendor/lib")
        for (dir in systemLibs) {
            if (java.io.File(dir).exists()) ldPath.add(dir)
        }
        ldPath.add(env["LD_LIBRARY_PATH"] ?: "")
        env["LD_LIBRARY_PATH"] = ldPath.joinToString(":")

        val proc = pb.start()
        process = proc

        Thread {
            try {
                val reader = proc.inputStream.bufferedReader()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    Log.i(TAG, "rpc-server stdout: $line")
                }
            } catch (_: Exception) {}

            val exitCode = try { proc.waitFor() } catch (_: Exception) { -1 }
            if (exitCode != 0) {
                Log.e(TAG, "rpc-server exited with code $exitCode")
            }
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
