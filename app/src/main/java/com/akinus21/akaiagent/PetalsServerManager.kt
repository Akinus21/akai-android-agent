package com.akinus21.akaiagent

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.lang.ProcessBuilder

object PetalsServerManager {
    private const val TAG = "akai-agent"
    private const val PETALS_SCRIPT = "start_petals.sh"
    private const val BIN_DIR = "petals-bin"

    private var process: Process? = null

    fun getScriptPath(context: Context): File {
        return File(File(context.filesDir, BIN_DIR), PETALS_SCRIPT)
    }

    fun ensureScript(context: Context): File {
        val targetDir = File(context.filesDir, BIN_DIR)
        targetDir.mkdirs()

        val script = File(targetDir, PETALS_SCRIPT)
        if (script.exists() && script.length() > 0) {
            return script
        }

        try {
            context.assets.open(PETALS_SCRIPT).use { input ->
                FileOutputStream(script).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "No petals script asset found: $e")
            throw IllegalStateException("petals script not available")
        }

        script.setExecutable(true)
        Log.i(TAG, "petals script: path=${script.absolutePath}")
        return script
    }

    fun start(context: Context, model: String, port: Int = 50052): Process {
        stop()

        val script = ensureScript(context)

        val cmd = listOf(
            "/system/bin/sh", script.absolutePath,
            "--model", model,
            "--port", port.toString()
        )
        Log.i(TAG, "Starting Petals: $cmd")

        try {
            val pb = ProcessBuilder(cmd)
            pb.directory(script.parentFile)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            process = proc

            Thread {
                try {
                    val reader = proc.inputStream.bufferedReader()
                    var line: String? = reader.readLine()
                    while (line != null) {
                        Log.i(TAG, "petals: $line")
                        line = reader.readLine()
                    }
                } catch (_: Exception) {}

                val exitCode = try { proc.waitFor() } catch (_: Exception) { -1 }
                Log.i(TAG, "petals exited with code $exitCode")
            }.start()

            return proc
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start petals: ${e.message}")
            throw e
        }
    }

    fun stop() {
        process?.destroy()
        process = null
    }

    fun isRunning(): Boolean {
        return process?.isAlive == true
    }
}