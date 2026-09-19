package com.ysdev.floatingrecorder

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ScreenFormat(val ext: String, val mime: String) {
    MP4("mp4", "video/mp4"),
    WEBM("webm", "video/webm")
}

data class ScreenConfig(
    val width: Int,
    val height: Int,
    val dpi: Int,
    val fps: Int = 30,
    val videoBitrate: Int = 8_000_000,
    val audioBitrate: Int = 128_000,
    val format: ScreenFormat = ScreenFormat.MP4,
    val withAudio: Boolean = true
)

class ScreenRecorder(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaProjection: MediaProjection? = null
    private var outputFile: File? = null
    private var pendingUri: Uri? = null
    private var isRecording = false

    var onSaved: ((File?) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun start(resultCode: Int, data: Intent, config: ScreenConfig): Boolean {
        if (isRecording) return false
        return try {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(resultCode, data)
                ?: run { onError?.invoke("MediaProjection null"); return false }

            val pair = createOutput(config.format)
            outputFile = pair.first
            pendingUri = pair.second

            mediaRecorder = buildRecorder(config)
            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "FloatingRecorderScreen",
                config.width, config.height, config.dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder!!.surface, null, null
            )
            mediaRecorder!!.start()
            isRecording = true
            Logger.i("ScreenRec", "Started")
            true
        } catch (e: Throwable) {
            Logger.e("ScreenRec", "start: " + e.message)
            cleanup()
            onError?.invoke(e.message ?: "start fail")
            false
        }
    }

    fun stop() {
        if (!isRecording) return
        try {
            mediaRecorder?.stop()
            Logger.i("ScreenRec", "Stopped")
            publishPending()
            onSaved?.invoke(outputFile)
        } catch (e: Throwable) {
            Logger.e("ScreenRec", "stop: " + e.message)
            onError?.invoke(e.message ?: "stop fail")
        } finally {
            cleanup()
            isRecording = false
        }
    }

    fun isRunning(): Boolean = isRecording

    private fun buildRecorder(cfg: ScreenConfig): MediaRecorder {
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()

        return rec.apply {
            setVideoSource(MediaRecorder.VideoSource.SCREEN)
            if (cfg.withAudio) setAudioSource(MediaRecorder.AudioSource.MIC)

            setOutputFormat(
                if (cfg.format == ScreenFormat.MP4)
                    MediaRecorder.OutputFormat.MPEG_4
                else MediaRecorder.OutputFormat.WEBM
            )

            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(cfg.width, cfg.height)
            setVideoFrameRate(cfg.fps)
            setVideoEncodingBitRate(cfg.videoBitrate)

            if (cfg.withAudio) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(cfg.audioBitrate)
                setAudioChannels(2)
            }

            if (outputFile != null) setOutputFile(outputFile!!.absolutePath)
            prepare()
        }
    }

    private fun createOutput(format: ScreenFormat): Pair<File?, Uri?> {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "SCR_" + ts + "." + format.ext

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, format.mime)
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/FloatingRecorder")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            val tmp = File(context.cacheDir, name)
            Pair(tmp, uri)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES), "FloatingRecorder")
            if (!dir.exists()) dir.mkdirs()
            Pair(File(dir, name), null)
        }
    }

    private fun publishPending() {
        if (pendingUri == null || outputFile == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            context.contentResolver.openOutputStream(pendingUri!!)?.use { os ->
                outputFile!!.inputStream().use { it.copyTo(os) }
            }
            val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            context.contentResolver.update(pendingUri!!, values, null, null)
            outputFile?.delete()
        } catch (e: Throwable) {
            Logger.e("ScreenRec", "publish: " + e.message)
        }
    }

    private fun cleanup() {
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { mediaRecorder?.reset() } catch (_: Throwable) {}
        try { mediaRecorder?.release() } catch (_: Throwable) {}
        try { mediaProjection?.stop() } catch (_: Throwable) {}
        virtualDisplay = null; mediaRecorder = null; mediaProjection = null
    }
}
