package com.ysdev.floatingrecorder

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.util.Locale
import kotlin.math.abs

class FloatingRecorderService : Service() {

    companion object {
        private const val CHANNEL_ID = "floating_recorder"
        private const val NOTIF_ID = 5001
        const val ACTION_STOP = "com.ysdev.floatingrecorder.STOP"
        const val ACTION_PAUSE = "com.ysdev.floatingrecorder.PAUSE"
        const val ACTION_TOGGLE_AUDIO = "com.ysdev.floatingrecorder.TOGGLE_AUDIO"
        const val ACTION_TOGGLE_SCREEN = "com.ysdev.floatingrecorder.TOGGLE_SCREEN"

        var instance: FloatingRecorderService? = null
        var mediaProjectionData: Intent? = null

        fun start(c: Context) {
            try {
                val i = Intent(c, FloatingRecorderService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i)
                else c.startService(i)
            } catch (e: Throwable) { Logger.e("Floating", "start: " + e.message) }
        }

        fun stop(c: Context) {
            try { c.stopService(Intent(c, FloatingRecorderService::class.java)) } catch (_: Throwable) {}
        }
    }

    enum class Mode { AUDIO, SCREEN }

    private var windowManager: WindowManager? = null
    private var floatView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var recorder: AudioRecorder? = null
    private var screenRecorder: ScreenRecorder? = null
    private var mediaProjection: MediaProjection? = null
    private var elapsedSec = 0L
    private var currentMode: Mode = Mode.AUDIO

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            Logger.i("Floating", "onCreate")
            createChannel()
            startForegroundSafely()
            initMediaProjection()
            showFloatingButton()
        } catch (e: Throwable) {
            Logger.e("Floating", "onCreate FATAL: " + e.message)
        }
    }

    private fun startForegroundSafely() {
        try {
            val notif = buildNotif("Floating Recorder", "Ready")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
                startForeground(NOTIF_ID, notif, type)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            Logger.i("Floating", "startForeground OK")
        } catch (e: Throwable) {
            Logger.e("Floating", "startForeground: " + e.message)
        }
    }

    private fun createChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, "Floating Recorder",
                            NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
                }
            }
        } catch (_: Throwable) {}
    }

    private fun buildNotif(title: String, content: String, withActions: Boolean = false): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title).setContentText(content)
            .setOngoing(true).setContentIntent(pi)

        if (withActions) {
            val stopPi = PendingIntent.getService(this, 1,
                Intent(this, FloatingRecorderService::class.java).apply { action = ACTION_STOP },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val pausePi = PendingIntent.getService(this, 2,
                Intent(this, FloatingRecorderService::class.java).apply { action = ACTION_PAUSE },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_media_pause, "Pause", pausePi)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
        }
        return builder.build()
    }

    private fun updateNotif(title: String, content: String, withActions: Boolean = false) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotif(title, content, withActions))
        } catch (_: Throwable) {}
    }

    private fun initMediaProjection() {
        try {
            val data = mediaProjectionData ?: return
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(Activity.RESULT_OK, data)
            Logger.i("Floating", "MediaProjection OK")
        } catch (e: Throwable) {
            Logger.e("Floating", "MediaProjection: " + e.message)
        }
    }

    private fun showFloatingButton() {
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            floatView = LayoutInflater.from(this).inflate(R.layout.floating_recorder, null)

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

            layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START; x = 30; y = 200 }

            var initX = 0; var initY = 0
            var touchX = 0f; var touchY = 0f
            var lastUpdate = 0L

            floatView?.setOnTouchListener { _, event ->
                try {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initX = layoutParams!!.x; initY = layoutParams!!.y
                            touchX = event.rawX; touchY = event.rawY; true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate < 16) return@setOnTouchListener true
                            lastUpdate = now
                            layoutParams!!.x = initX + (event.rawX - touchX).toInt()
                            layoutParams!!.y = initY + (event.rawY - touchY).toInt()
                            try { windowManager?.updateViewLayout(floatView, layoutParams) } catch (_: Throwable) {}
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (abs(event.rawX - touchX) < 10 && abs(event.rawY - touchY) < 10) {
                                if (currentMode == Mode.AUDIO) toggleAudio() else toggleScreen()
                            }
                            true
                        }
                        else -> false
                    }
                } catch (e: Throwable) { Logger.e("Floating", "touch: " + e.message); false }
            }

            floatView?.findViewById<ImageButton>(R.id.btnModeSwitch)?.setOnClickListener {
                currentMode = if (currentMode == Mode.AUDIO) Mode.SCREEN else Mode.AUDIO
                updateUI(false)
            }

            windowManager?.addView(floatView, layoutParams)
            Logger.i("Floating", "addView OK")
        } catch (e: Throwable) {
            Logger.e("Floating", "addView FATAL: " + e.message)
        }
    }

    private fun toggleAudio() {
        try {
            val rec = recorder ?: AudioRecorder(this).also { recorder = it }
            if (rec.isRunning()) {
                rec.stop()
                updateNotif("Floating Recorder", "Stopped")
                updateUI(false)
            } else {
                elapsedSec = 0L
                rec.setAmplify(SettingsManager.getAmplify(this))
                rec.setNoiseGate(SettingsManager.getNoiseGate(this))
                rec.setMediaProjection(mediaProjection)
                rec.onTimeUpdate = { ms ->
                    elapsedSec = ms / 1000
                    updateNotif("🎙️ Recording", formatTime(elapsedSec), true)
                }
                rec.onModeChanged = { mode, _ -> updateNotif(mode.display, formatTime(elapsedSec), true) }
                rec.onSaved = { file -> updateNotif("Saved", file.name); updateUI(false) }
                rec.onError = { err -> updateNotif("Error", err); updateUI(false) }
                rec.start()
                updateUI(true)
            }
        } catch (e: Throwable) { Logger.e("Floating", "toggleAudio: " + e.message) }
    }

    private fun toggleScreen() {
        try {
            val rec = screenRecorder ?: ScreenRecorder(this).also {
                screenRecorder = it
                it.onSaved = { f -> updateNotif("Saved", f?.name ?: "video"); updateUI(false) }
                it.onError = { e -> updateNotif("Error", e); updateUI(false) }
            }
            if (rec.isRunning()) {
                rec.stop()
                updateNotif("Floating Recorder", "Stopped")
                updateUI(false)
            } else {
                val data = mediaProjectionData ?: run {
                    updateNotif("Screen", "Izin screen record belum diberikan")
                    return
                }
                val resIdx = SettingsManager.getScrResIndex(this)
                val fpsIdx = SettingsManager.getScrFpsIndex(this)
                val brIdx = SettingsManager.getScrBitrateIndex(this)
                val cfg = ScreenSettings.buildConfig(
                    this,
                    ScreenSettings.RESOLUTIONS[resIdx].scale,
                    ScreenSettings.FPS_OPTIONS[fpsIdx],
                    ScreenSettings.BITRATE_OPTIONS[brIdx],
                    ScreenFormat.MP4
                )
                if (rec.start(Activity.RESULT_OK, data, cfg)) {
                    updateNotif("🎥 Screen Rec", "Recording", true)
                    updateUI(true)
                }
            }
        } catch (e: Throwable) { Logger.e("Floating", "toggleScreen: " + e.message) }
    }

    private fun updateUI(recording: Boolean) {
        try {
            floatView?.let { v ->
                val dot = v.findViewById<View>(R.id.statusDot)
                val tvTime = v.findViewById<TextView>(R.id.tvTime)
                val tvLabel = v.findViewById<TextView>(R.id.tvLabel)
                val btnSwitch = v.findViewById<ImageButton>(R.id.btnModeSwitch)
                dot?.setBackgroundResource(if (recording) R.drawable.dot_red else R.drawable.dot_green)
                tvTime?.text = if (recording) formatTime(elapsedSec) else "00:00"
                tvLabel?.text = when {
                    recording && currentMode == Mode.SCREEN -> "SCR"
                    recording -> "REC"
                    currentMode == Mode.SCREEN -> "Screen"
                    else -> "Audio"
                }
                btnSwitch?.setImageResource(
                    if (currentMode == Mode.AUDIO) R.drawable.ic_screen else R.drawable.ic_mic)
            }
        } catch (_: Throwable) {}
    }

    private fun formatTime(sec: Long): String =
        String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                recorder?.stop(); screenRecorder?.stop()
                updateNotif("Floating Recorder", "Stopped"); updateUI(false)
            }
            ACTION_PAUSE -> {
                if (recorder?.isRunning() == true) {
                    if (recorder!!.isPaused()) recorder!!.resume() else recorder!!.pause()
                }
            }
            ACTION_TOGGLE_AUDIO -> { currentMode = Mode.AUDIO; toggleAudio() }
            ACTION_TOGGLE_SCREEN -> { currentMode = Mode.SCREEN; toggleScreen() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try { recorder?.stop() } catch (_: Throwable) {}
        try { screenRecorder?.stop() } catch (_: Throwable) {}
        try { floatView?.let { windowManager?.removeView(it) } } catch (_: Throwable) {}
        try { mediaProjection?.stop() } catch (_: Throwable) {}
        Logger.i("Floating", "onDestroy")
    }
}
