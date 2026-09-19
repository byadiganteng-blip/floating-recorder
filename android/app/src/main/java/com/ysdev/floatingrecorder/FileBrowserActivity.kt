package com.ysdev.floatingrecorder

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

class FileBrowserActivity : AppCompatActivity() {

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_file_browser)
        Logger.lifecycle("FileBrowser.onCreate")

        val listView = findViewById<ListView>(R.id.listFiles)
        val files = loadRecordings()

        if (files.isEmpty()) {
            Toast.makeText(this, "Belum ada rekaman", Toast.LENGTH_SHORT).show()
        }

        listView.adapter = ArrayAdapter(this,
            android.R.layout.simple_list_item_2,
            android.R.id.text1,
            files.map { "${it.name}\n${humanSize(it.length())}" })

        listView.setOnItemClickListener { _, _, pos, _ -> playFile(files[pos]) }
    }

    private fun loadRecordings(): List<File> {
        val dirs = mutableListOf<File>()
        dirs.add(File(getExternalFilesDir(null), "recordings"))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            dirs.add(File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MUSIC), "FloatingRecorder"))
            @Suppress("DEPRECATION")
            dirs.add(File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES), "FloatingRecorder"))
        }
        return dirs.flatMap { d ->
            if (d.exists()) d.listFiles()?.toList() ?: emptyList() else emptyList()
        }.filter { it.isFile }.sortedByDescending { it.lastModified() }
    }

    private fun playFile(file: File) {
        val ext = file.extension.lowercase()
        val mime = when (ext) {
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            else -> "*/*"
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No player: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun humanSize(n: Long): String {
        var s = n.toDouble()
        for (u in listOf("B", "KB", "MB", "GB")) {
            if (s < 1024) return String.format("%.1f %s", s, u)
            s /= 1024
        }
        return String.format("%.1f TB", s)
    }
}
