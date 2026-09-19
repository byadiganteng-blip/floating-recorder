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
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvInfo: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvPermStatus: TextView
    private lateinit var spMode: Spinner
    private lateinit var sbAmplify: SeekBar
    private lateinit var tvAmplifyValue: TextView
    private var amplifyGain = 2.0f

    companion object {
        const val REQ_ALL = PermissionHelper.REQ_ALL_PERMISSIONS
        const val REQ_MEDIA_PROJ = PermissionHelper.REQ_MEDIA_PROJ
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_main)
        Logger.lifecycle("MainActivity.onCreate")
        Logger.i("Main", "Intent: " + intent?.action)

        tvInfo = findViewById(R.id.tvInfo)
        tvStatus = findViewById(R.id.tvStatus)
        tvPermStatus = findViewById(R.id.tvPermStatus)
        spMode = findViewById(R.id.spMode)
        sbAmplify = findViewById(R.id.sbAmplify)
        tvAmplifyValue = findViewById(R.id.tvAmplifyValue)

        showDeviceInfo()

        val modes = AudioMode.availableModes()
        spMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes.map { it.display })
        val bestIdx = modes.indexOf(AudioMode.bestMode()).coerceAtLeast(0)
        spMode.setSelection(bestIdx)

        sbAmplify.max = 55
        sbAmplify.progress = 15
        updateAmplifyLabel(2.0f)
        sbAmplify.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                amplifyGain = (p + 5) / 10.0f
                updateAmplifyLabel(amplifyGain)
                Logger.d("Amplify", "Changed to " + amplifyGain + "x")
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            Logger.ui("Tap Aktifkan Floating Recorder")
            checkPermissionsAndStart()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            Logger.ui("Tap Stop")
            FloatingRecorderService.stop(this)
            tvStatus.text = "Status: Stopped"
        }
        findViewById<Button>(R.id.btnOpen).setOnClickListener {
            Logger.ui("Tap Buka Folder")
            Toast.makeText(this, "Buka: Downloads/FloatingRecorder", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.btnLogs).setOnClickListener {
            Logger.ui("Tap Lihat Log")
            showLogs()
        }
        findViewById<Button>(R.id.btnFixPerm).setOnClickListener {
            Logger.ui("Tap Minta Izin")
            requestAllPermissions()
        }

        tvStatus.text = "Status: Idle"

        this.window.decorView.postDelayed({
            Logger.lifecycle("Auto permission request start")
            requestAllPermissions()
        }, 500)
    }

    private fun requestAllPermissions() {
        val missing = PermissionHelper.getMissingPermissions(this)
        Logger.i("Perm", "Missing runtime: " + missing)
        updatePermStatus()

        if (missing.isNotEmpty()) {
            Logger.i("Perm", "Requesting " + missing.size + " permissions")
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_ALL)
            return
        }

        if (!PermissionHelper.hasOverlay(this)) {
            Logger.w("Perm", "Overlay missing")
            AlertDialog.Builder(this)
                .setTitle("Izin Tampil di Atas")
                .setMessage("Buka Pengaturan → aktifkan 'Tampil di atas aplikasi lain'.")
                .setCancelable(false)
                .setPositiveButton("Buka Pengaturan") { _, _ ->
                    startActivity(PermissionHelper.overlayIntent(this))
                }
                .setNegativeButton("Nanti", null)
                .show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJ)
            } catch (e: Exception) {
                Logger.e("Perm", "MediaProjection err: " + e.message)
                onAllPermissionsDone()
            }
        } else {
            onAllPermissionsDone()
        }
    }

    private fun onAllPermissionsDone() {
        Logger.i("Perm", "ALL PERMISSIONS DONE")
        updatePermStatus()
        tvStatus.text = "Semua izin siap — tap Aktifkan"
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, perms, res)
        Logger.i("Perm", "onRequestPermissionsResult: req=" + req)
        if (req == REQ_ALL) {
            perms.forEachIndexed { i, p ->
                val granted = i < res.size && res[i] == PackageManager.PERMISSION_GRANTED
                Logger.permission(p, granted)
            }
            this.window.decorView.postDelayed({
                requestAllPermissions()
            }, 300)
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        Logger.i("Perm", "onActivityResult req=" + req + " res=" + res)
        if (req == REQ_MEDIA_PROJ) {
            if (res == Activity.RESULT_OK && data != null) {
                FloatingRecorderService.mediaProjectionData = data
                Logger.i("Perm", "MediaProjection GRANTED")
            } else {
                Logger.w("Perm", "MediaProjection DENIED")
            }
            onAllPermissionsDone()
        }
    }

    private fun updatePermStatus() {
        try {
            val checks = listOf(
                "MIC" to PermissionHelper.hasRecord(this),
                "NOTIF" to PermissionHelper.hasNotif(this),
                "STORAGE" to PermissionHelper.hasStorage(this),
                "OVERLAY" to PermissionHelper.hasOverlay(this)
            )
            tvPermStatus.text = checks.joinToString(" | ") {
                (if (it.second) "[OK]" else "[X]") + " " + it.first
            }
        } catch (_: Exception) {}
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
            else -> "Android " + sdk
        }
        val best = AudioMode.bestMode()
        tvInfo.text = "Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
                "OS: " + versionName + "\n\n" +
                "Best mode: " + best.display + "\n" +
                "   " + best.description
    }

    private fun updateAmplifyLabel(gain: Float) {
        tvAmplifyValue.text = String.format("%.1fx", gain)
    }

    private fun checkPermissionsAndStart() {
        val missing = PermissionHelper.getMissingPermissions(this)
        if (missing.isNotEmpty()) { requestAllPermissions(); return }
        if (!PermissionHelper.hasOverlay(this)) { requestAllPermissions(); return }
        Logger.i("Main", "All perms OK — starting service")
        FloatingRecorderService.start(this)
        tvStatus.text = "Status: Service aktif — tap floating button"
    }

    private fun showLogs() {
        try {
            val log = Logger.readLog(1000)
            AlertDialog.Builder(this)
                .setTitle("Log")
                .setMessage(log)
                .setPositiveButton("OK", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: " + e.message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        Logger.lifecycle("MainActivity.onResume")
        updatePermStatus()
    }
    override fun onPause() {
        super.onPause()
        Logger.lifecycle("MainActivity.onPause")
    }
    override fun onDestroy() {
        super.onDestroy()
        Logger.lifecycle("MainActivity.onDestroy")
    }
}
