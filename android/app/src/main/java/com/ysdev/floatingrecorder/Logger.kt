package com.ysdev.floatingrecorder

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Logger ULTRA-DETAIL
 * - Setiap log entry punya: timestamp, level, tag, thread, message, stacktrace
 * - Tulis ke file di /Downloads/FloatingRecorder/log_YYYYMMDD.txt
 * - Rotasi otomatis per hari
 * - Metadata device di awal file
 */
object Logger {
    private const val TAG = "FloatRec"
    private var logFile: File? = null
    private val counter = AtomicLong(0)
    private var startTime = 0L
    private val tsFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fullFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(c: Context) {
        try {
            startTime = System.currentTimeMillis()

            // Primary location: /Downloads/FloatingRecorder/
            val dlDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "FloatingRecorder")
            if (!dlDir.exists()) dlDir.mkdirs()

            val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            logFile = File(dlDir, "log_$date.txt")

            // Secondary: app-private files
            try {
                val dir = c.getExternalFilesDir("logs") ?: File(c.filesDir, "logs")
                if (!dir.exists()) dir.mkdirs()
                val backup = File(dir, "log_$date.txt")
                if (logFile?.exists() != true) logFile = backup
            } catch (_: Exception) {}

            // Write header
            writeHeader(c)
        } catch (e: Throwable) {
            Log.e(TAG, "Logger init failed: ${e.message}")
        }
    }

    private fun writeHeader(c: Context) {
        try {
            val sb = StringBuilder()
            sb.append("\n").append("=".repeat(78)).append("\n")
            sb.append("  FLOATING RECORDER — LOG SESSION\n")
            sb.append("=".repeat(78)).append("\n")
            sb.append("  Session start  : ${fullFormat.format(Date())}\n")
            sb.append("  Session ID     : ${System.currentTimeMillis()}\n")
            sb.append("  App version    : 3.1\n")
            sb.append("  Package        : ${c.packageName}\n")
            sb.append("  Device         : ${Build.MANUFACTURER} ${Build.MODEL}\n")
            sb.append("  Brand          : ${Build.BRAND}\n")
            sb.append("  Product        : ${Build.PRODUCT}\n")
            sb.append("  Hardware       : ${Build.HARDWARE}\n")
            sb.append("  Android SDK    : ${Build.VERSION.SDK_INT}\n")
            sb.append("  Android Ver    : ${Build.VERSION.RELEASE}\n")
            sb.append("  CPU ABI        : ${Build.SUPPORTED_ABIS.joinToString()}\n")
            sb.append("  Java VM        : ${System.getProperty("java.vm.version")}\n")
            sb.append("  Total RAM      : ${Runtime.getRuntime().totalMemory() / 1024 / 1024} MB\n")
            sb.append("  Max RAM        : ${Runtime.getRuntime().maxMemory() / 1024 / 1024} MB\n")
            sb.append("=".repeat(78)).append("\n\n")
            logFile?.appendText(sb.toString())
        } catch (_: Throwable) {}
    }

    fun i(tag: String, msg: String) { log("INFO", tag, msg, null) }
    fun w(tag: String, msg: String) { log("WARN", tag, msg, null) }
    fun e(tag: String, msg: String) { log("ERROR", tag, msg, null) }
    fun e(tag: String, msg: String, t: Throwable?) { log("ERROR", tag, msg, t) }
    fun d(tag: String, msg: String) { log("DEBUG", tag, msg, null) }

    fun lifecycle(event: String, detail: String = "") {
        log("LIFECYCLE", "App", "$event ${if (detail.isNotEmpty()) "— $detail" else ""}", null)
    }

    fun permission(name: String, granted: Boolean, extra: String = "") {
        val status = if (granted) "GRANTED" else "DENIED"
        log("PERM", "Perm", "$name : $status ${if (extra.isNotEmpty()) "— $extra" else ""}", null)
    }

    fun audio(event: String, detail: String = "") {
        log("AUDIO", "Recorder", "$event ${if (detail.isNotEmpty()) "— $detail" else ""}", null)
    }

    fun ui(action: String, detail: String = "") {
        log("UI", "User", "$action ${if (detail.isNotEmpty()) "— $detail" else ""}", null)
    }

    private fun log(level: String, tag: String, msg: String, t: Throwable?) {
        try {
            val now = System.currentTimeMillis()
            val elapsed = if (startTime > 0) now - startTime else 0
            val seq = counter.incrementAndGet()
            val threadName = Thread.currentThread().name
            val ts = tsFormat.format(Date(now))

            val line = StringBuilder()
            line.append("$ts ")
            line.append("[${level.padEnd(9)}]")
            line.append(" [${seq.toString().padStart(6, '0')}]")
            line.append(" (+${elapsed.toString().padStart(6, '0')}ms)")
            line.append(" T[$threadName]")
            line.append(" $tag: $msg")

            if (t != null) {
                line.append("\n    └─ EXCEPTION: ${t.javaClass.name}")
                line.append("\n       message: ${t.message}")
                line.append("\n       stack:")
                t.stackTrace.take(20).forEach { st ->
                    line.append("\n         at $st")
                }
                var cause = t.cause
                while (cause != null) {
                    line.append("\n       caused by: ${cause.javaClass.name}: ${cause.message}")
                    cause.stackTrace.take(10).forEach { st ->
                        line.append("\n         at $st")
                    }
                    cause = cause.cause
                }
            }

            val out = line.toString()

            // Android logcat
            when (level) {
                "ERROR" -> Log.e(TAG, out)
                "WARN" -> Log.w(TAG, out)
                "DEBUG" -> Log.d(TAG, out)
                else -> Log.i(TAG, out)
            }

            // Write to file
            logFile?.appendText(out + "\n")
        } catch (_: Throwable) {}
    }

    fun getLogPath(): String? = logFile?.absolutePath

    fun readLog(maxLines: Int = 500): String {
        return try {
            val f = logFile ?: return "(no log file)"
            if (!f.exists()) return "(file not found)"
            val lines = f.readLines()
            lines.takeLast(maxLines).joinToString("\n")
        } catch (e: Exception) {
            "(read error: ${e.message})"
        }
    }
}
