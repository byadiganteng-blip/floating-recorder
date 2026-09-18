package com.ysdev.floatingrecorder
import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
object Logger {
    private const val TAG = "FloatRec"
    private var logFile: File? = null
    fun init(c: Context) {
        try {
            val dir = c.getExternalFilesDir("logs") ?: File(c.filesDir, "logs")
            if (!dir.exists()) dir.mkdirs()
            val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            logFile = File(dir, "app_$date.log")
        } catch (_: Exception) {}
    }
    fun i(tag: String, msg: String) { Log.i(TAG, "[$tag] $msg"); write("I", tag, msg) }
    fun w(tag: String, msg: String) { Log.w(TAG, "[$tag] $msg"); write("W", tag, msg) }
    fun e(tag: String, msg: String) { Log.e(TAG, "[$tag] $msg"); write("E", tag, msg) }
    private fun write(level: String, tag: String, msg: String) {
        try {
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            logFile?.appendText("$ts [$level] $tag — $msg\n")
        } catch (_: Exception) {}
    }
}
