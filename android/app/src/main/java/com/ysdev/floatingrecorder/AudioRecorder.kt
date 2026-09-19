package com.ysdev.floatingrecorder

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecorder(private val context: Context) {

    companion object {
        private val SAMPLE_RATES = intArrayOf(44100, 48000, 22050, 16000)
        private const val CHANNELS = AudioFormat.CHANNEL_IN_STEREO
        private const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE = 8192
        private const val NUM_CHANNELS = 2
        private const val BITS_PER_SAMPLE = 16
        private const val MAX_FILE_BYTES = 50L * 1024 * 1024
    }

    private val isRecording = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val pauseLock = Object()
    private var recordThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var mediaProjection: MediaProjection? = null
    private var activeSampleRate = 44100
    private var activeMode: AudioMode = AudioMode.MIC_DIRECT
    private var processor = AudioProcessor(44100, 2.0f)
    private var amplifyGain = 2.0f
    private var noiseGate = 0.005f

    var onAmplitude: ((Float) -> Unit)? = null
    var onTimeUpdate: ((Long) -> Unit)? = null
    var onSaved: ((File) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onModeChanged: ((AudioMode, String) -> Unit)? = null
    var onPauseChanged: ((Boolean) -> Unit)? = null

    fun setAmplify(gain: Float) {
        amplifyGain = gain.coerceIn(0.5f, 10f)
        processor = AudioProcessor(activeSampleRate, amplifyGain, 0.92f, 60f, noiseGate)
    }

    fun setNoiseGate(g: Float) { noiseGate = g.coerceIn(0f, 0.05f) }
    fun setMediaProjection(mp: MediaProjection?) { mediaProjection = mp }
    fun getActiveMode(): AudioMode = activeMode
    fun isRunning(): Boolean = isRecording.get()
    fun isPaused(): Boolean = isPaused.get()

    fun start() {
        try {
            if (isRecording.get()) return
            isRecording.set(true)
            isPaused.set(false)
            recordThread = Thread {
                try { recordLoop() } catch (e: Throwable) {
                    Logger.e("Recorder", "FATAL: " + e.message)
                    onError?.invoke(e.message ?: "thread crash")
                }
            }.also { it.start() }
        } catch (e: Throwable) {
            Logger.e("Recorder", "start err: " + e.message)
            isRecording.set(false)
            onError?.invoke(e.message ?: "start fail")
        }
    }

    fun pause() {
        if (!isRecording.get() || isPaused.get()) return
        isPaused.set(true)
        onPauseChanged?.invoke(true)
        Logger.i("Recorder", "Paused")
    }

    fun resume() {
        if (!isRecording.get() || !isPaused.get()) return
        isPaused.set(false)
        synchronized(pauseLock) { pauseLock.notifyAll() }
        onPauseChanged?.invoke(false)
        Logger.i("Recorder", "Resumed")
    }

    fun stop() {
        if (!isRecording.get()) return
        isRecording.set(false)
        isPaused.set(false)
        synchronized(pauseLock) { pauseLock.notifyAll() }
        try { recordThread?.join(3000) } catch (_: Exception) {}
        recordThread = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    private fun recordLoop() {
        var success = false
        for (mode in AudioMode.fallbackChain()) {
            for (sr in SAMPLE_RATES) {
                Logger.i("Recorder", "Try: " + mode.display + " @ " + sr)
                if (tryStart(mode, sr)) {
                    activeMode = mode
                    activeSampleRate = sr
                    success = true
                    Logger.i("Recorder", "OK: " + mode.display + " @ " + sr)
                    processor = AudioProcessor(sr, amplifyGain, 0.92f, 60f, noiseGate)
                    Handler(Looper.getMainLooper()).post {
                        onModeChanged?.invoke(mode, mode.description + " @ " + sr + "Hz")
                    }
                    break
                }
            }
            if (success) break
        }
        if (!success) {
            isRecording.set(false)
            onError?.invoke("All modes failed")
            return
        }
        recordAudio()
    }

    private fun tryStart(mode: AudioMode, sampleRate: Int): Boolean {
        return try {
            val minBuf = try {
                AudioRecord.getMinBufferSize(sampleRate, CHANNELS, FORMAT)
            } catch (_: Throwable) { 0 }.coerceAtLeast(BUFFER_SIZE * 2)

            audioRecord = AudioRecord.Builder()
                .setAudioSource(mode.audioSource)
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(FORMAT).setSampleRate(sampleRate)
                    .setChannelMask(CHANNELS).build())
                .setBufferSizeInBytes(minBuf)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release(); audioRecord = null; return false
            }
            audioRecord!!.startRecording()
            if (audioRecord!!.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.release(); audioRecord = null; return false
            }
            true
        } catch (e: Throwable) {
            Logger.w("Recorder", "try err: " + e.message)
            try { audioRecord?.release() } catch (_: Throwable) {}
            audioRecord = null
            false
        }
    }

    private fun recordAudio() {
        var fos: OutputStream? = null
        var outFile: File? = null
        var pendingUri: Uri? = null
        var fileBytes = 0L
        val startTime = System.currentTimeMillis()

        try {
            val triple = createOutput()
            fos = triple.first
            outFile = triple.second
            pendingUri = triple.third
            val localFos = fos ?: throw RuntimeException("output stream null")

            Logger.i("Recorder", "Output: " + (outFile?.absolutePath ?: pendingUri.toString()))
            localFos.write(ByteArray(44))

            val buffer = ShortArray(BUFFER_SIZE)
            val byteBuffer = ByteArray(BUFFER_SIZE * 2)
            var peak = 0

            while (isRecording.get()) {
                synchronized(pauseLock) {
                    while (isPaused.get() && isRecording.get()) {
                        try { pauseLock.wait() } catch (_: InterruptedException) {}
                    }
                }

                val read = try { audioRecord?.read(buffer, 0, buffer.size) ?: 0 }
                    catch (e: Throwable) { Logger.e("Recorder", "read: " + e.message); break }
                if (read <= 0) continue

                for (i in 0 until read) {
                    val v = kotlin.math.abs(buffer[i].toInt())
                    if (v > peak) peak = v
                }

                try { processor.process(buffer) } catch (_: Throwable) {}

                for (i in 0 until read) {
                    byteBuffer[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                    byteBuffer[i * 2 + 1] = ((buffer[i].toInt() shr 8) and 0xFF).toByte()
                }

                try {
                    localFos.write(byteBuffer, 0, read * 2)
                    fileBytes += read * 2
                } catch (e: Throwable) { Logger.e("Recorder", "write: " + e.message); break }

                if (fileBytes > MAX_FILE_BYTES) {
                    Logger.i("Recorder", "Auto-split at " + (fileBytes / 1024 / 1024) + " MB")
                    try { localFos.flush(); localFos.close() } catch (_: Throwable) {}
                    finalizeWav(outFile, pendingUri, fileBytes, activeSampleRate)
                    outFile?.let { onSaved?.invoke(it) }

                    val np = createOutput()
                    fos = np.first
                    outFile = np.second
                    pendingUri = np.third
                    fos?.write(ByteArray(44))
                    fileBytes = 0L
                }

                try {
                    val amp = processor.rmsLevel(buffer)
                    Handler(Looper.getMainLooper()).post {
                        onAmplitude?.invoke(amp)
                        onTimeUpdate?.invoke(System.currentTimeMillis() - startTime)
                    }
                } catch (_: Throwable) {}
            }

            Logger.i("Recorder", "Done. Peak: " + peak + " / 32767")
        } catch (e: Throwable) {
            Logger.e("Recorder", "recordAudio FATAL: " + e.message)
        } finally {
            try { fos?.flush(); fos?.close() } catch (_: Throwable) {}
            finalizeWav(outFile, pendingUri, fileBytes, activeSampleRate)
            outFile?.let { f ->
                Handler(Looper.getMainLooper()).post { onSaved?.invoke(f) }
            }
        }
    }

    private fun finalizeWav(file: File?, uri: Uri?, pcmBytes: Long, sr: Int) {
        if (pcmBytes <= 0) return
        try {
            if (file != null && file.exists()) {
                val raf = RandomAccessFile(file, "rw")
                raf.seek(0)
                raf.write(buildWavHeader(pcmBytes.toInt(), sr))
                raf.close()
                Logger.i("Recorder", "WAV OK (" + (pcmBytes / 1024) + " KB)")
            }
            if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                    put(MediaStore.Audio.Media.SIZE, pcmBytes + 44)
                }
                context.contentResolver.update(uri, values, null, null)
            }
        } catch (e: Throwable) {
            Logger.e("Recorder", "finalizeWav: " + e.message)
        }
    }

    private fun createOutput(): Triple<OutputStream?, File?, Uri?> {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "REC_" + ts + ".wav"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/FloatingRecorder")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            val os: OutputStream? = if (uri != null) {
                context.contentResolver.openOutputStream(uri)
            } else null
            Triple(os, null, uri)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MUSIC), "FloatingRecorder")
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, name)
            Triple(FileOutputStream(f), f, null)
        }
    }

    private fun buildWavHeader(pcmBytes: Int, sampleRate: Int): ByteArray {
        val header = ByteArray(44)
        val byteRate = sampleRate * NUM_CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = NUM_CHANNELS * BITS_PER_SAMPLE / 8

        fun putStr(o: Int, s: String) { for (i in s.indices) header[o + i] = s[i].toByte() }
        fun putI(o: Int, v: Int) {
            header[o] = (v and 0xFF).toByte()
            header[o+1] = ((v shr 8) and 0xFF).toByte()
            header[o+2] = ((v shr 16) and 0xFF).toByte()
            header[o+3] = ((v shr 24) and 0xFF).toByte()
        }
        fun putS(o: Int, v: Int) {
            header[o] = (v and 0xFF).toByte()
            header[o+1] = ((v shr 8) and 0xFF).toByte()
        }

        putStr(0, "RIFF"); putI(4, 36 + pcmBytes); putStr(8, "WAVE")
        putStr(12, "fmt "); putI(16, 16); putS(20, 1); putS(22, NUM_CHANNELS)
        putI(24, sampleRate); putI(28, byteRate); putS(32, blockAlign)
        putS(34, BITS_PER_SAMPLE); putStr(36, "data"); putI(40, pcmBytes)
        return header
    }
}
