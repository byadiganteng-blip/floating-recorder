package com.ysdev.floatingrecorder

import android.app.Application
import android.os.Build
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecorderApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Global crash handler
        try {
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try { writeCrashLog(thread, throwable) } catch (_: Throwable) {}
                defaultHandler?.uncaughtException(thread, throwable)
            }
        } catch (_: Throwable) {}

        Logger.init(this)
        Logger.lifecycle("App.onCreate", "v4.0")
        Logger.i("App", "Package: " + packageName)
        Logger.i("App", "Process: " + android.os.Process.myPid())
        Logger.i("App", "Thread: " + Thread.currentThread().name)
        Logger.i("App", "Device: " + Build.MANUFACTURER + " " + Build.MODEL)
        Logger.i("App", "Android: " + Build.VERSION.SDK_INT)
        Logger.i("App", "RAM max: " + (Runtime.getRuntime().maxMemory() / 1024 / 1024) + " MB")
        Logger.i("App", "Log path: " + Logger.getLogPath())
    }

    private fun writeCrashLog(thread: Thread, t: Throwable) {
        try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "FloatingRecorder")
            if (!dir.exists()) dir.mkdirs()
            val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            val f = File(dir, "crash_" + date + ".txt")
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

            val sb = StringBuilder()
            sb.append("\n" + "=".repeat(78) + "\n")
            sb.append("  💥 CRASH REPORT\n")
            sb.append("=".repeat(78) + "\n")
            sb.append("  Time       : " + ts + "\n")
            sb.append("  Thread     : " + thread.name + "\n")
            sb.append("  Exception  : " + t.javaClass.name + "\n")
            sb.append("  Message    : " + t.message + "\n")
            sb.append("  Device     : " + Build.MANUFACTURER + " " + Build.MODEL + "\n")
            sb.append("  Android    : " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")\n")
            sb.append("  App Ver    : 4.0\n")
            sb.append("-".repeat(78) + "\n")
            sb.append("  FULL STACK TRACE:\n")
            sb.append(t.stackTraceToString())
            sb.append("\n")
            var cause = t.cause
            var depth = 1
            while (cause != null) {
                sb.append("\n  CAUSE #" + depth + ": " + cause.javaClass.name + ": " + cause.message + "\n")
                sb.append(cause.stackTraceToString())
                cause = cause.cause
                depth++
            }
            sb.append("=".repeat(78) + "\n\n")
            f.appendText(sb.toString())
            Logger.e("App", "CRASH saved: " + f.absolutePath)
        } catch (_: Throwable) {}
    }
}
