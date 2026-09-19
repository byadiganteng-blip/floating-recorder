package com.ysdev.floatingrecorder

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

object Logger {
    private const val TAG = "FloatRec"
    private var logFile: File? = null
    private val lock = Any()
    private val counter = AtomicLong(0)
    private var startTime = 0L
    private val tsFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fullFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(c: Context) {
        try {
            startTime = System.currentTimeMillis()
            val dir = File(c.getExternalFilesDir(null), "logs")
            if (!dir.exists()) dir.mkdirs()
            val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            logFile = File(dir, "log_" + date + ".txt")
            writeHeader(c)
        } catch (e: Throwable) {
            Log.e(TAG, "Logger init failed: " + e.message)
        }
    }

    private fun writeHeader(c: Context) {
        try {
            val sb = StringBuilder()
            sb.append("\n" + "=".repeat(78) + "\n")
            sb.append("  FLOATING RECORDER — LOG SESSION\n")
            sb.append("=".repeat(78) + "\n")
            sb.append("  Session : " + fullFormat.format(Date()) + "\n")
            sb.append("  Version : 5.0\n")
            sb.append("  Package : " + c.packageName + "\n")
            sb.append("  Device  : " + Build.MANUFACTURER + " " + Build.MODEL + "\n")
            sb.append("  Android : " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")\n")
            sb.append("  ABI     : " + Build.SUPPORTED_ABIS.joinToString() + "\n")
            sb.append("  RAM     : " + (Runtime.getRuntime().maxMemory() / 1024 / 1024) + " MB\n")
            sb.append("=".repeat(78) + "\n\n")
            synchronized(lock) { logFile?.appendText(sb.toString()) }
        } catch (_: Throwable) {}
    }

    fun i(tag: String, msg: String) { log("INFO", tag, msg, null) }
    fun w(tag: String, msg: String) { log("WARN", tag, msg, null) }
    fun e(tag: String, msg: String) { log("ERROR", tag, msg, null) }
    fun d(tag: String, msg: String) { log("DEBUG", tag, msg, null) }

    fun lifecycle(event: String, detail: String = "") {
        val msg = event + (if (detail.isNotEmpty()) " — " + detail else "")
        log("LIFECYCLE", "App", msg, null)
    }

    fun permission(name: String, granted: Boolean, extra: String = "") {
        val status = if (granted) "GRANTED" else "DENIED"
        val msg = name + " : " + status + (if (extra.isNotEmpty()) " — " + extra else "")
        log("PERM", "Perm", msg, null)
    }

    fun audio(event: String, detail: String = "") {
        val msg = event + (if (detail.isNotEmpty()) " — " + detail else "")
        log("AUDIO", "Recorder", msg, null)
    }

    fun ui(action: String, detail: String = "") {
        val msg = action + (if (detail.isNotEmpty()) " — " + detail else "")
        log("UI", "User", msg, null)
    }

    private fun log(level: String, tag: String, msg: String, t: Throwable?) {
        try {
            val now = System.currentTimeMillis()
            val elapsed = if (startTime > 0) now - startTime else 0
            val seq = counter.incrementAndGet()
            val threadName = Thread.currentThread().name
            val ts = tsFormat.format(Date(now))

            val line = StringBuilder()
                .append(ts).append(" ")
                .append("[").append(level.padEnd(9)).append("]")
                .append(" [").append(seq.toString().padStart(6, '0')).append("]")
                .append(" (+").append(elapsed.toString().padStart(6, '0')).append("ms)")
                .append(" T[").append(threadName).append("]")
                .append(" ").append(tag).append(": ").append(msg)

            if (t != null) {
                line.append("\n    └─ ").append(t.javaClass.name).append(": ").append(t.message)
                t.stackTrace.take(15).forEach { line.append("\n         at ").append(it) }
            }

            val out = line.toString()
            when (level) {
                "ERROR" -> Log.e(TAG, out)
                "WARN" -> Log.w(TAG, out)
                "DEBUG" -> Log.d(TAG, out)
                else -> Log.i(TAG, out)
            }
            synchronized(lock) { logFile?.appendText(out + "\n") }
        } catch (_: Throwable) {}
    }

    fun getLogPath(): String? = logFile?.absolutePath

    fun readLog(maxLines: Int = 500): String {
        return try {
            val f = logFile
            if (f == null || !f.exists()) {
                "(no log)"
            } else {
                f.readLines().takeLast(maxLines).joinToString("\n")
            }
        } catch (e: Exception) {
            "(err: " + e.message + ")"
        }
    }
}
