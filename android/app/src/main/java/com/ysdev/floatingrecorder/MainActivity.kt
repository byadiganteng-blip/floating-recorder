package com.ysdev.floatingrecorder

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvInfo: TextView
    private lateinit var tvStatus: TextView
    private lateinit var spMode: Spinner
    private lateinit var sbAmplify: SeekBar
    private lateinit var tvAmplifyValue: TextView
    private var amplifyGain = 3.0f

    companion object {
        const val REQ_RECORD = 100
        const val REQ_NOTIF = 101
        const val REQ_OVERLAY = 102
        const val REQ_MEDIA_PROJ = 200
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_main)
        Logger.i("Main", "onCreate")

        tvInfo = findViewById(R.id.tvInfo)
        tvStatus = findViewById(R.id.tvStatus)
        spMode = findViewById(R.id.spMode)
        sbAmplify = findViewById(R.id.sbAmplify)
        tvAmplifyValue = findViewById(R.id.tvAmplifyValue)

        showDeviceInfo()

        val modes = AudioMode.availableModes()
        spMode.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, modes.map { it.display })
        val bestIdx = modes.indexOf(AudioMode.bestMode()).coerceAtLeast(0)
        spMode.setSelection(bestIdx)

        sbAmplify.max = 90
        sbAmplify.progress = 20
        updateAmplifyLabel(3.0f)
        sbAmplify.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                amplifyGain = (p + 10) / 10.0f
                updateAmplifyLabel(amplifyGain)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        findViewById<AppCompatButton>(R.id.btnStart).setOnClickListener { startFloating() }
        findViewById<AppCompatButton>(R.id.btnStop).setOnClickListener {
            FloatingRecorderService.stop(this)
            tvStatus.text = "Status: Stopped"
        }
        findViewById<AppCompatButton>(R.id.btnOpen).setOnClickListener {
            Toast.makeText(this, "Buka: Downloads/FloatingRecorder", Toast.LENGTH_LONG).show()
        }
        findViewById<AppCompatButton>(R.id.btnLogs).setOnClickListener { showLogs() }

        tvStatus.text = "Status: Idle"
    }

    private fun showDeviceInfo() {
        val sdk = Build.VERSION.SDK_INT
        val versionName = when {
            sdk >= 35 -> "Android 15 (API 35)"
            sdk >= 34 -> "Android 14 (API 34)"
            sdk >= 33 -> "Android 13 (API 33)"
            sdk >= 32 -> "Android 12L (API 32)"
            sdk >= 31 -> "Android 12 (API 31)"
            sdk >= 30 -> "Android 11 (API 30)"
            sdk >= 29 -> "Android 10 (API 29)"
            sdk >= 28 -> "Android 9 (API 28)"
            sdk >= 27 -> "Android 8.1 (API 27)"
            sdk >= 26 -> "Android 8.0 (API 26)"
            sdk >= 25 -> "Android 7.1 (API 25)"
            sdk >= 24 -> "Android 7.0 (API 24)"
            sdk >= 23 -> "Android 6.0 (API 23)"
            else -> "Android $sdk"
        }
        val best = AudioMode.bestMode()
        val modes = AudioMode.availableModes()
        tvInfo.text = """Device: ${Build.MANUFACTURER} ${Build.MODEL}
OS: $versionName

🎯 Best mode: ${best.display}
   ${best.description}

📋 Available modes (${modes.size}):
${modes.joinToString("\n") { "  • ${it.display}" }}""".trimIndent()
    }

    private fun updateAmplifyLabel(gain: Float) {
        tvAmplifyValue.text = String.format("%.1fx", gain)
    }

    private fun startFloating() {
        if (!canDrawOverlay()) {
            AlertDialog.Builder(this)
                .setTitle("Izin Floating Window")
                .setMessage("Aktifkan 'Tampil di atas aplikasi lain' supaya button recorder bisa muncul.")
                .setPositiveButton("Buka Pengaturan") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Batal", null).show()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD)
            return
        }

        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJ)
        } else {
            doStartService()
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_MEDIA_PROJ) {
            if (res == Activity.RESULT_OK && data != null) {
                FloatingRecorderService.mediaProjectionData = data
            }
            doStartService()
        }
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, perms, res)
        if (req == REQ_RECORD && res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) {
            startFloating()
        }
    }

    private fun doStartService() {
        FloatingRecorderService.start(this)
        tvStatus.text = "Status: Service aktif — tap floating button"
    }

    private fun canDrawOverlay(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    private fun showLogs() {
        try {
            val dir = getExternalFilesDir("logs")
            val files = dir?.listFiles()?.sortedByDescending { it.lastModified() }
            if (files.isNullOrEmpty()) {
                Toast.makeText(this, "Belum ada log", Toast.LENGTH_SHORT).show()
                return
            }
            val content = files.first().readText().takeLast(3000)
            AlertDialog.Builder(this)
                .setTitle("Log: ${files.first().name}")
                .setMessage(content)
                .setPositiveButton("OK", null).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
