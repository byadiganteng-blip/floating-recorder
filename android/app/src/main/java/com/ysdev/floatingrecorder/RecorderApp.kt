package com.ysdev.floatingrecorder

import android.app.Application
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecorderApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // ═══ GLOBAL CRASH HANDLER ═══
        try {
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try { writeCrashLog(throwable) } catch (_: Exception) {}
                defaultHandler?.uncaughtException(thread, throwable)
            }
        } catch (_: Exception) {}

        Logger.init(this)
        Logger.i("App", "Floating Recorder v3 started")
        Logger.i("App", "Android ${android.os.Build.VERSION.SDK_INT}")
        Logger.i("App", "Device ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
    }

    private fun writeCrashLog(t: Throwable) {
        try {
            val dir = getExternalFilesDir("logs") ?: File(filesDir, "logs")
            if (!dir.exists()) dir.mkdirs()
            val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            val f = File(dir, "crash_${date}.log")
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val sb = StringBuilder()
            sb.append("\n=== CRASH at $ts ===\n")
            sb.append("Thread: ${Thread.currentThread().name}\n")
            sb.append("Exception: ${t.javaClass.name}\n")
            sb.append("Message: ${t.message}\n")
            sb.append("Stack:\n")
            sb.append(t.stackTraceToString())
            var cause = t.cause
            while (cause != null) {
                sb.append("\nCause: ${cause.javaClass.name}: ${cause.message}\n")
                cause = cause.cause
            }
            sb.append("=====================\n\n")
            f.appendText(sb.toString())
        } catch (_: Exception) {}
    }
}
