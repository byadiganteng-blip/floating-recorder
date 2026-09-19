package com.ysdev.floatingrecorder

import android.app.Application
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecorderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            val default = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try { writeCrashLog(thread, throwable) } catch (_: Throwable) {}
                default?.uncaughtException(thread, throwable)
            }
        } catch (_: Throwable) {}

        Logger.init(this)
        Logger.lifecycle("App.onCreate", "v5.0")
        Logger.i("App", "Device: " + Build.MANUFACTURER + " " + Build.MODEL)
        Logger.i("App", "Android: " + Build.VERSION.SDK_INT)
        Logger.i("App", "Log path: " + Logger.getLogPath())
    }

    private fun writeCrashLog(thread: Thread, t: Throwable) {
        try {
            val dir = File(getExternalFilesDir(null), "crashes")
            if (!dir.exists()) dir.mkdirs()
            val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            val f = File(dir, "crash_" + date + ".txt")
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

            val sb = StringBuilder()
            sb.append("\n" + "=".repeat(78) + "\n")
            sb.append("  CRASH REPORT\n")
            sb.append("=".repeat(78) + "\n")
            sb.append("  Time     : " + ts + "\n")
            sb.append("  Thread   : " + thread.name + "\n")
            sb.append("  Exception: " + t.javaClass.name + "\n")
            sb.append("  Message  : " + t.message + "\n")
            sb.append("  Device   : " + Build.MANUFACTURER + " " + Build.MODEL + "\n")
            sb.append("  Android  : " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")\n")
            sb.append("-".repeat(78) + "\n")
            sb.append(t.stackTraceToString())
            sb.append("\n" + "=".repeat(78) + "\n\n")
            f.appendText(sb.toString())
            Logger.e("App", "CRASH saved: " + f.absolutePath)
        } catch (_: Throwable) {}
    }
}
