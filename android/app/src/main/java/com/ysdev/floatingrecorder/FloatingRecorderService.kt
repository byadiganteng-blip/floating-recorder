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
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.util.Locale
import kotlin.math.abs

class FloatingRecorderService : Service() {

    companion object {
        private const val CHANNEL_ID = "floating_recorder"
        private const val NOTIF_ID = 5001
        var instance: FloatingRecorderService? = null
        var mediaProjectionData: Intent? = null

        fun start(c: Context) {
            try {
                val i = Intent(c, FloatingRecorderService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    c.startForegroundService(i)
                } else c.startService(i)
            } catch (e: Throwable) {
                Logger.e("Floating", "start err: ${e.message}")
            }
        }

        fun stop(c: Context) {
            try { c.stopService(Intent(c, FloatingRecorderService::class.java)) }
            catch (_: Throwable) {}
        }
    }

    private var windowManager: WindowManager? = null
    private var floatView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var recorder: AudioRecorder? = null
    private var mediaProjection: MediaProjection? = null
    private var elapsedSec = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            Logger.i("Floating", "onCreate")

            createChannel()

            // Foreground notification WAJIB
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIF_ID, buildNotif("Floating Recorder", "Ready"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } else {
                    startForeground(NOTIF_ID, buildNotif("Floating Recorder", "Ready"))
                }
                Logger.i("Floating", "startForeground OK")
            } catch (e: Throwable) {
                Logger.e("Floating", "startForeground err: ${e.message}")
            }

            initMediaProjection()
            showFloatingButton()
        } catch (e: Throwable) {
            Logger.e("Floating", "onCreate FATAL: ${e.message}")
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

    private fun buildNotif(title: String, content: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title).setContentText(content)
            .setOngoing(true).setContentIntent(pi).build()
    }

    private fun updateNotif(title: String, content: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotif(title, content))
        } catch (_: Throwable) {}
    }

    private fun initMediaProjection() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            val data = mediaProjectionData ?: return
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(Activity.RESULT_OK, data)
            Logger.i("Floating", "MediaProjection acquired")
        } catch (e: Throwable) {
            Logger.e("Floating", "MediaProjection err: ${e.message}")
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
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 30; y = 200
            }

            var initX = 0; var initY = 0
            var touchX = 0f; var touchY = 0f

            floatView?.setOnTouchListener { _, event ->
                try {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initX = layoutParams!!.x; initY = layoutParams!!.y
                            touchX = event.rawX; touchY = event.rawY; true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            layoutParams!!.x = initX + (event.rawX - touchX).toInt()
                            layoutParams!!.y = initY + (event.rawY - touchY).toInt()
                            try { windowManager?.updateViewLayout(floatView, layoutParams) } catch (_: Throwable) {}
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (abs(event.rawX - touchX) < 10 && abs(event.rawY - touchY) < 10) {
                                toggleRecording()
                            }
                            true
                        }
                        else -> false
                    }
                } catch (e: Throwable) {
                    Logger.e("Floating", "touch err: ${e.message}")
                    false
                }
            }

            windowManager?.addView(floatView, layoutParams)
            Logger.i("Floating", "addView OK")
        } catch (e: Throwable) {
            Logger.e("Floating", "addView FATAL: ${e.message}")
        }
    }

    private fun toggleRecording() {
        try {
            val rec = recorder ?: AudioRecorder(this).also { recorder = it }
            if (rec.isRunning()) {
                rec.stop()
                updateNotif("Floating Recorder", "Stopped")
                updateUI(false)
            } else {
                elapsedSec = 0L
                rec.setMediaProjection(mediaProjection)
                rec.onAmplitude = { try { updateUI(true) } catch (_: Throwable) {} }
                rec.onTimeUpdate = { ms ->
                    try {
                        elapsedSec = ms / 1000
                        updateNotif("🎙️ Recording", formatTime(elapsedSec))
                    } catch (_: Throwable) {}
                }
                rec.onModeChanged = { mode, _ ->
                    try { updateNotif("🎙️ ${mode.display}", formatTime(elapsedSec)) } catch (_: Throwable) {}
                }
                rec.onSaved = { file ->
                    try {
                        updateNotif("✅ Saved", file.name)
                        updateUI(false)
                    } catch (_: Throwable) {}
                }
                rec.onError = { err ->
                    try {
                        updateNotif("❌ Error", err)
                        updateUI(false)
                    } catch (_: Throwable) {}
                }
                rec.start()
                updateUI(true)
            }
        } catch (e: Throwable) {
            Logger.e("Floating", "toggle FATAL: ${e.message}")
        }
    }

    private fun updateUI(recording: Boolean) {
        try {
            floatView?.let { v ->
                val dot = v.findViewById<View>(R.id.statusDot)
                val tvTime = v.findViewById<TextView>(R.id.tvTime)
                val tvLabel = v.findViewById<TextView>(R.id.tvLabel)
                dot?.setBackgroundResource(
                    if (recording) R.drawable.dot_red else R.drawable.dot_green)
                tvTime?.text = if (recording) formatTime(elapsedSec) else "00:00"
                tvLabel?.text = if (recording) "REC" else "Idle"
            }
        } catch (_: Throwable) {}
    }

    private fun formatTime(sec: Long): String =
        String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60)

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try { recorder?.stop() } catch (_: Throwable) {}
        try { floatView?.let { windowManager?.removeView(it) } } catch (_: Throwable) {}
        try { mediaProjection?.stop() } catch (_: Throwable) {}
        Logger.i("Floating", "onDestroy")
    }
}
