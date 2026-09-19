package com.ysdev.floatingrecorder

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.tabs.TabLayout

class MainActivity : AppCompatActivity() {

    private lateinit var tvInfo: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvPermStatus: TextView
    private lateinit var spMode: Spinner
    private lateinit var sbAmplify: SeekBar
    private lateinit var sbNoiseGate: SeekBar
    private lateinit var tvAmplifyValue: TextView
    private lateinit var tvNoiseValue: TextView
    private lateinit var tabMode: TabLayout

    private var amplifyGain = 2.0f
    private var noiseGate = 0.005f
    private var mediaProjectionIntent: Intent? = null

    companion object {
        const val REQ_ALL = PermissionHelper.REQ_ALL_PERMISSIONS
        const val REQ_MEDIA_PROJ = PermissionHelper.REQ_MEDIA_PROJ
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_main)
        Logger.lifecycle("MainActivity.onCreate")

        tvInfo = findViewById(R.id.tvInfo)
        tvStatus = findViewById(R.id.tvStatus)
        tvPermStatus = findViewById(R.id.tvPermStatus)
        spMode = findViewById(R.id.spMode)
        sbAmplify = findViewById(R.id.sbAmplify)
        sbNoiseGate = findViewById(R.id.sbNoiseGate)
        tvAmplifyValue = findViewById(R.id.tvAmplifyValue)
        tvNoiseValue = findViewById(R.id.tvNoiseValue)
        tabMode = findViewById(R.id.tabMode)

        showDeviceInfo()
        setupTabs()
        setupAudioSettings()
        setupButtons()

        tvStatus.text = "Status: Idle"
        this.window.decorView.postDelayed({ requestAllPermissions(auto = true) }, 500)
    }

    private fun setupTabs() {
        tabMode.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val audio = findViewById<LinearLayout>(R.id.panelAudio)
                val screen = findViewById<LinearLayout>(R.id.panelScreen)
                if (tab.position == 0) {
                    audio.visibility = LinearLayout.VISIBLE
                    screen.visibility = LinearLayout.GONE
                } else {
                    audio.visibility = LinearLayout.GONE
                    screen.visibility = LinearLayout.VISIBLE
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupAudioSettings() {
        val modes = AudioMode.availableModes()
        spMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            modes.map { it.display })
        val savedIdx = modes.indexOfFirst { it.name == SettingsManager.getMode(this) }
        val bestIdx = modes.indexOf(AudioMode.bestMode()).coerceAtLeast(0)
        spMode.setSelection(if (savedIdx >= 0) savedIdx else bestIdx)
        spMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                SettingsManager.setMode(this@MainActivity, modes[pos].name)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        sbAmplify.max = 95
        amplifyGain = SettingsManager.getAmplify(this)
        sbAmplify.progress = ((amplifyGain - 0.5f) * 10).toInt().coerceIn(0, 95)
        updateAmplifyLabel(amplifyGain)
        sbAmplify.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                amplifyGain = (p / 10f) + 0.5f
                updateAmplifyLabel(amplifyGain)
                SettingsManager.setAmplify(this@MainActivity, amplifyGain)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        sbNoiseGate.max = 50
        noiseGate = SettingsManager.getNoiseGate(this)
        sbNoiseGate.progress = (noiseGate * 1000).toInt().coerceIn(0, 50)
        updateNoiseLabel(noiseGate)
        sbNoiseGate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                noiseGate = p / 1000f
                updateNoiseLabel(noiseGate)
                SettingsManager.setNoiseGate(this@MainActivity, noiseGate)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            Logger.ui("Tap Aktifkan Floating")
            checkPermissionsAndStart()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            Logger.ui("Tap Stop")
            FloatingRecorderService.stop(this)
            tvStatus.text = "Status: Stopped"
        }
        findViewById<Button>(R.id.btnFixPerm).setOnClickListener {
            Logger.ui("Tap Minta Izin")
            requestAllPermissions(auto = false)
        }
        findViewById<Button>(R.id.btnFiles).setOnClickListener {
            Logger.ui("Tap Buka Files")
            startActivity(Intent(this, FileBrowserActivity::class.java))
        }
        findViewById<Button>(R.id.btnLogs).setOnClickListener {
            Logger.ui("Tap Lihat Log")
            showLogs()
        }
    }

    private fun requestAllPermissions(auto: Boolean) {
        val missing = PermissionHelper.getMissingPermissions(this)
        updatePermStatus()

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_ALL)
            return
        }

        if (!PermissionHelper.hasOverlay(this)) {
            if (auto) return
            AlertDialog.Builder(this)
                .setTitle("Izin Tampil di Atas")
                .setMessage("Aktifkan 'Tampil di atas aplikasi lain' di Pengaturan.")
                .setPositiveButton("Buka") { _, _ -> startActivity(PermissionHelper.overlayIntent(this)) }
                .setNegativeButton("Nanti", null)
                .show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjectionIntent == null) {
            try {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJ)
            } catch (e: Exception) {
                Logger.e("Perm", "MediaProj: " + e.message)
                onAllPermissionsDone()
            }
        } else {
            onAllPermissionsDone()
        }
    }

    private fun onAllPermissionsDone() {
        updatePermStatus()
        tvStatus.text = "Semua izin siap — tap Aktifkan"
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, perms, res)
        if (req == REQ_ALL) {
            perms.forEachIndexed { i, p ->
                Logger.permission(p, i < res.size && res[i] == PackageManager.PERMISSION_GRANTED)
            }
            this.window.decorView.postDelayed({ requestAllPermissions(auto = false) }, 300)
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_MEDIA_PROJ) {
            if (res == Activity.RESULT_OK && data != null) {
                FloatingRecorderService.mediaProjectionData = data
                mediaProjectionIntent = data
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
            tvPermStatus.text = checks.joinToString("   ") {
                (if (it.second) "OK" else "X") + " " + it.first
            }
        } catch (_: Exception) {}
    }

    private fun showDeviceInfo() {
        val sdk = Build.VERSION.SDK_INT
        val version = when {
            sdk >= 35 -> "Android 15"
            sdk >= 34 -> "Android 14"
            sdk >= 33 -> "Android 13"
            sdk >= 32 -> "Android 12L"
            sdk >= 31 -> "Android 12"
            sdk >= 30 -> "Android 11"
            sdk >= 29 -> "Android 10"
            sdk >= 28 -> "Android 9"
            sdk >= 27 -> "Android 8.1"
            sdk >= 26 -> "Android 8.0"
            sdk >= 25 -> "Android 7.1"
            sdk >= 24 -> "Android 7.0"
            sdk >= 23 -> "Android 6.0"
            else -> "Android $sdk"
        }
        val best = AudioMode.bestMode()
        tvInfo.text = "Device: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                "OS: $version (SDK $sdk)\n" +
                "Best audio: ${best.display}\n" +
                "   ${best.description}"
    }

    private fun updateAmplifyLabel(g: Float) { tvAmplifyValue.text = String.format("%.1fx", g) }
    private fun updateNoiseLabel(v: Float) { tvNoiseValue.text = String.format("%.0f%%", v * 1000) }

    private fun checkPermissionsAndStart() {
        val missing = PermissionHelper.getMissingPermissions(this)
        if (missing.isNotEmpty() || !PermissionHelper.hasOverlay(this)) {
            requestAllPermissions(auto = false); return
        }
        Logger.i("Main", "Starting service")
        FloatingRecorderService.start(this)
        tvStatus.text = "Status: Service aktif — tap floating button"
    }

    private fun showLogs() {
        try {
            val log = Logger.readLog(500)
            AlertDialog.Builder(this)
                .setTitle("Log")
                .setMessage(log)
                .setPositiveButton("OK", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Err: " + e.message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() { super.onResume(); updatePermStatus() }
}
