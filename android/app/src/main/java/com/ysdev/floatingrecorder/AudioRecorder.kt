package com.ysdev.floatingrecorder

import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecorder(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val CHANNELS = AudioFormat.CHANNEL_IN_STEREO
        private const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE = 8192
        private const val NUM_CHANNELS = 2
        private const val BITS_PER_SAMPLE = 16
    }

    private val isRecording = AtomicBoolean(false)
    private var recordThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var mediaProjection: MediaProjection? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var agc: AutomaticGainControl? = null
    private var activeMode: AudioMode = AudioMode.bestMode()
    private var processor = AudioProcessor(SAMPLE_RATE, 3.0f, 0.015f, 0.4f)

    var onAmplitude: ((Float) -> Unit)? = null
    var onTimeUpdate: ((Long) -> Unit)? = null
    var onSaved: ((File) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onModeChanged: ((AudioMode, String) -> Unit)? = null

    fun setAmplify(gain: Float) {
        processor = AudioProcessor(SAMPLE_RATE, gain.coerceIn(1f, 10f), 0.015f, 0.4f)
    }

    fun setMediaProjection(mp: MediaProjection?) { mediaProjection = mp }
    fun getActiveMode(): AudioMode = activeMode

    fun start() {
        try {
            if (isRecording.get()) return
            isRecording.set(true)
            recordThread = Thread {
                try { recordLoop() } catch (e: Throwable) {
                    Logger.e("Recorder", "Thread FATAL: ${e.message}")
                    try { onError?.invoke(e.message ?: "thread crash") } catch (_: Exception) {}
                }
            }.also { it.start() }
        } catch (e: Throwable) {
            Logger.e("Recorder", "start err: ${e.message}")
            isRecording.set(false)
            try { onError?.invoke(e.message ?: "start fail") } catch (_: Exception) {}
        }
    }

    fun stop() {
        if (!isRecording.get()) return
        isRecording.set(false)
        try { recordThread?.join(3000) } catch (_: Exception) {}
        recordThread = null
        releaseEffects()
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    fun isRunning(): Boolean = isRecording.get()

    private fun recordLoop() {
        val modesToTry = AudioMode.fallbackChain()
        var success = false

        for (mode in modesToTry) {
            try {
                if (tryStartWithMode(mode)) {
                    activeMode = mode
                    success = true
                    Logger.i("Recorder", "Mode: ${mode.display}")
                    Handler(Looper.getMainLooper()).post {
                        try { onModeChanged?.invoke(mode, mode.description) } catch (_: Exception) {}
                    }
                    break
                }
            } catch (e: Throwable) {
                Logger.w("Recorder", "Mode ${mode.display} err: ${e.message}")
            }
        }

        if (!success) {
            isRecording.set(false)
            try { onError?.invoke("All modes failed") } catch (_: Exception) {}
            return
        }

        recordAudio()
    }

    private fun tryStartWithMode(mode: AudioMode): Boolean {
        return try {
            val minBuf = try {
                AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, FORMAT)
            } catch (_: Throwable) { 0 }.coerceAtLeast(BUFFER_SIZE * 2)

            val builder = AudioRecord.Builder()
                .setAudioSource(mode.audioSource)
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(FORMAT).setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNELS).build())
                .setBufferSizeInBytes(minBuf)

            if (mode == AudioMode.INTERNAL_APC
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && mediaProjection != null) {
                try {
                    val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()
                    builder.setAudioPlaybackCaptureConfig(config)
                } catch (_: Throwable) { return false }
            }

            audioRecord = builder.build()
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                try { audioRecord?.release() } catch (_: Throwable) {}
                audioRecord = null
                return false
            }

            if (mode != AudioMode.INTERNAL_APC) {
                setupEffects(audioRecord!!.audioSessionId)
            }

            try {
                audioRecord!!.startRecording()
            } catch (_: Throwable) {
                releaseEffects()
                try { audioRecord?.release() } catch (_: Throwable) {}
                audioRecord = null
                return false
            }

            if (audioRecord!!.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                releaseEffects()
                try { audioRecord?.release() } catch (_: Throwable) {}
                audioRecord = null
                return false
            }
            true
        } catch (e: Throwable) {
            Logger.e("Recorder", "tryStart ${mode.display}: ${e.message}")
            try { audioRecord?.release() } catch (_: Throwable) {}
            audioRecord = null
            false
        }
    }

    private fun setupEffects(sessionId: Int) {
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)
                noiseSuppressor?.enabled = true
            }
        } catch (_: Throwable) {}
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)
                echoCanceler?.enabled = true
            }
        } catch (_: Throwable) {}
        try {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId)
                agc?.enabled = true
            }
        } catch (_: Throwable) {}
    }

    private fun releaseEffects() {
        try { noiseSuppressor?.release() } catch (_: Throwable) {}
        try { echoCanceler?.release() } catch (_: Throwable) {}
        try { agc?.release() } catch (_: Throwable) {}
        noiseSuppressor = null; echoCanceler = null; agc = null
    }

    private fun recordAudio() {
        var fos: FileOutputStream? = null
        var outFile: File? = null

        try {
            outFile = createOutputFile()
            Logger.i("Recorder", "Output: ${outFile.absolutePath}")

            fos = FileOutputStream(outFile)
            val emptyHeader = ByteArray(44)
            fos.write(emptyHeader)

            val buffer = ShortArray(BUFFER_SIZE)
            val byteBuffer = ByteArray(BUFFER_SIZE * 2)
            val startTime = System.currentTimeMillis()
            var bytesWritten = 0L

            while (isRecording.get()) {
                val read = try {
                    audioRecord?.read(buffer, 0, buffer.size) ?: 0
                } catch (e: Throwable) {
                    Logger.e("Recorder", "read err: ${e.message}")
                    break
                }
                if (read <= 0) continue

                try {
                    processor.process(buffer)
                } catch (e: Throwable) {
                    Logger.w("Recorder", "process err: ${e.message}")
                }

                for (i in 0 until read) {
                    byteBuffer[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                    byteBuffer[i * 2 + 1] = ((buffer[i].toInt() shr 8) and 0xFF).toByte()
                }
                try {
                    fos.write(byteBuffer, 0, read * 2)
                    bytesWritten += read * 2
                } catch (e: Throwable) {
                    Logger.e("Recorder", "write err: ${e.message}")
                    break
                }

                try {
                    val amp = processor.rmsLevel(buffer)
                    Handler(Looper.getMainLooper()).post {
                        try { onAmplitude?.invoke(amp) } catch (_: Throwable) {}
                    }
                    val elapsed = System.currentTimeMillis() - startTime
                    Handler(Looper.getMainLooper()).post {
                        try { onTimeUpdate?.invoke(elapsed) } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}
            }
        } catch (e: Throwable) {
            Logger.e("Recorder", "recordAudio FATAL: ${e.message}")
        } finally {
            try { fos?.flush(); fos?.close() } catch (_: Throwable) {}

            // WAV header
            try {
                if (outFile != null && outFile.exists()) {
                    val size = outFile.length() - 44
                    val raf = RandomAccessFile(outFile, "rw")
                    val header = buildWavHeader(size.toInt())
                    raf.seek(0)
                    raf.write(header)
                    raf.close()
                    Logger.i("Recorder", "WAV header OK")
                }
            } catch (e: Throwable) {
                Logger.e("Recorder", "WAV header err: ${e.message}")
            }

            if (outFile != null) {
                Handler(Looper.getMainLooper()).post {
                    try { onSaved?.invoke(outFile) } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun buildWavHeader(pcmBytes: Int): ByteArray {
        val header = ByteArray(44)
        val byteRate = SAMPLE_RATE * NUM_CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = NUM_CHANNELS * BITS_PER_SAMPLE / 8

        fun putString(offset: Int, s: String) {
            for (i in s.indices) header[offset + i] = s[i].toByte()
        }
        fun putIntLE(offset: Int, v: Int) {
            header[offset] = (v and 0xFF).toByte()
            header[offset + 1] = ((v shr 8) and 0xFF).toByte()
            header[offset + 2] = ((v shr 16) and 0xFF).toByte()
            header[offset + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun putShortLE(offset: Int, v: Int) {
            header[offset] = (v and 0xFF).toByte()
            header[offset + 1] = ((v shr 8) and 0xFF).toByte()
        }

        putString(0, "RIFF")
        putIntLE(4, 36 + pcmBytes)
        putString(8, "WAVE")
        putString(12, "fmt ")
        putIntLE(16, 16)
        putShortLE(20, 1)
        putShortLE(22, NUM_CHANNELS)
        putIntLE(24, SAMPLE_RATE)
        putIntLE(28, byteRate)
        putShortLE(32, blockAlign)
        putShortLE(34, BITS_PER_SAMPLE)
        putString(36, "data")
        putIntLE(40, pcmBytes)

        return header
    }

    private fun createOutputFile(): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "FloatingRecorder")
        if (!dir.exists()) dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "REC_${ts}.wav")
    }
}
