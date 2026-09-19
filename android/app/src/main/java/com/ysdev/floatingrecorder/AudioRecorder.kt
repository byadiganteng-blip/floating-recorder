package com.ysdev.floatingrecorder

import android.content.Context
import android.media.*
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
        private val SAMPLE_RATES = intArrayOf(44100, 48000, 22050, 16000)
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
    private var activeSampleRate = 44100
    private var activeMode: AudioMode = AudioMode.MIC_DIRECT
    private var processor = AudioProcessor(44100, 2.0f, 0.92f)

    var onAmplitude: ((Float) -> Unit)? = null
    var onTimeUpdate: ((Long) -> Unit)? = null
    var onSaved: ((File) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onModeChanged: ((AudioMode, String) -> Unit)? = null

    fun setAmplify(gain: Float) {
        processor = AudioProcessor(activeSampleRate, gain.coerceIn(0.5f, 6f), 0.92f)
    }

    fun setMediaProjection(mp: MediaProjection?) { mediaProjection = mp }
    fun getActiveMode(): AudioMode = activeMode

    fun start() {
        try {
            if (isRecording.get()) return
            isRecording.set(true)
            recordThread = Thread {
                try { recordLoop() } catch (e: Throwable) {
                    Logger.e("Recorder", "Thread FATAL: " + e.message)
                    try { onError?.invoke(e.message ?: "thread crash") } catch (_: Exception) {}
                }
            }.also { it.start() }
        } catch (e: Throwable) {
            Logger.e("Recorder", "start err: " + e.message)
            isRecording.set(false)
            try { onError?.invoke(e.message ?: "start fail") } catch (_: Exception) {}
        }
    }

    fun stop() {
        if (!isRecording.get()) return
        isRecording.set(false)
        try { recordThread?.join(3000) } catch (_: Exception) {}
        recordThread = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    fun isRunning(): Boolean = isRecording.get()

    private fun recordLoop() {
        val modes = AudioMode.fallbackChain()
        var success = false

        for (mode in modes) {
            for (sr in SAMPLE_RATES) {
                Logger.i("Recorder", "Trying: " + mode.display + " @ " + sr + "Hz")
                if (tryStartWithMode(mode, sr)) {
                    activeMode = mode
                    activeSampleRate = sr
                    success = true
                    Logger.i("Recorder", "SUCCESS: " + mode.display + " @ " + sr + "Hz")
                    processor = AudioProcessor(sr, 2.0f, 0.92f)
                    Handler(Looper.getMainLooper()).post {
                        try { onModeChanged?.invoke(mode, mode.description + " @ " + sr + "Hz") } catch (_: Exception) {}
                    }
                    break
                }
            }
            if (success) break
        }

        if (!success) {
            isRecording.set(false)
            try { onError?.invoke("All modes failed") } catch (_: Exception) {}
            return
        }

        recordAudio()
    }

    private fun tryStartWithMode(mode: AudioMode, sampleRate: Int): Boolean {
        return try {
            val minBuf = try {
                AudioRecord.getMinBufferSize(sampleRate, CHANNELS, FORMAT)
            } catch (_: Throwable) { 0 }.coerceAtLeast(BUFFER_SIZE * 2)

            val builder = AudioRecord.Builder()
                .setAudioSource(mode.audioSource)
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(FORMAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(CHANNELS)
                    .build())
                .setBufferSizeInBytes(minBuf)

            audioRecord = builder.build()

            val state = audioRecord?.state
            Logger.i("Recorder", "AudioRecord state: " + state)

            if (state != AudioRecord.STATE_INITIALIZED) {
                try { audioRecord?.release() } catch (_: Throwable) {}
                audioRecord = null
                return false
            }

            try {
                audioRecord!!.startRecording()
            } catch (e: Throwable) {
                Logger.w("Recorder", "startRecording err: " + e.message)
                try { audioRecord?.release() } catch (_: Throwable) {}
                audioRecord = null
                return false
            }

            val recState = audioRecord!!.recordingState
            Logger.i("Recorder", "recordingState: " + recState)

            if (recState != AudioRecord.RECORDSTATE_RECORDING) {
                try { audioRecord?.release() } catch (_: Throwable) {}
                audioRecord = null
                return false
            }

            true
        } catch (e: Throwable) {
            Logger.w("Recorder", "tryStart err: " + e.message)
            try { audioRecord?.release() } catch (_: Throwable) {}
            audioRecord = null
            false
        }
    }

    private fun recordAudio() {
        var fos: FileOutputStream? = null
        var outFile: File? = null

        try {
            outFile = createOutputFile()
            Logger.i("Recorder", "Output: " + outFile.absolutePath)
            Logger.i("Recorder", "Sample rate: " + activeSampleRate + "Hz")

            fos = FileOutputStream(outFile)
            fos.write(ByteArray(44))

            val buffer = ShortArray(BUFFER_SIZE)
            val byteBuffer = ByteArray(BUFFER_SIZE * 2)
            val startTime = System.currentTimeMillis()
            var bytesWritten = 0L
            var firstSampleLogged = false
            var peakAmplitude = 0

            while (isRecording.get()) {
                val read = try {
                    audioRecord?.read(buffer, 0, buffer.size) ?: 0
                } catch (e: Throwable) {
                    Logger.e("Recorder", "read err: " + e.message)
                    break
                }
                if (read <= 0) continue

                if (!firstSampleLogged) {
                    var maxAbs = 0
                    for (i in 0 until read) {
                        val v = kotlin.math.abs(buffer[i].toInt())
                        if (v > maxAbs) maxAbs = v
                    }
                    Logger.i("Recorder", "First sample max amplitude: " + maxAbs + " / 32767")
                    firstSampleLogged = true
                }

                for (i in 0 until read) {
                    val v = kotlin.math.abs(buffer[i].toInt())
                    if (v > peakAmplitude) peakAmplitude = v
                }

                try { processor.process(buffer) } catch (e: Throwable) {
                    Logger.w("Recorder", "process err: " + e.message)
                }

                for (i in 0 until read) {
                    byteBuffer[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                    byteBuffer[i * 2 + 1] = ((buffer[i].toInt() shr 8) and 0xFF).toByte()
                }

                try {
                    fos.write(byteBuffer, 0, read * 2)
                    bytesWritten += read * 2
                } catch (e: Throwable) {
                    Logger.e("Recorder", "write err: " + e.message)
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

            Logger.i("Recorder", "Peak amplitude during record: " + peakAmplitude)
        } catch (e: Throwable) {
            Logger.e("Recorder", "recordAudio FATAL: " + e.message)
        } finally {
            try { fos?.flush(); fos?.close() } catch (_: Throwable) {}

            try {
                if (outFile != null && outFile.exists()) {
                    val size = outFile.length() - 44
                    val raf = RandomAccessFile(outFile, "rw")
                    raf.seek(0)
                    raf.write(buildWavHeader(size.toInt(), activeSampleRate))
                    raf.close()
                    Logger.i("Recorder", "WAV header OK (" + (size / 1024) + " KB)")
                }
            } catch (e: Throwable) {
                Logger.e("Recorder", "WAV header err: " + e.message)
            }

            if (outFile != null) {
                Handler(Looper.getMainLooper()).post {
                    try { onSaved?.invoke(outFile) } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun buildWavHeader(pcmBytes: Int, sampleRate: Int): ByteArray {
        val header = ByteArray(44)
        val byteRate = sampleRate * NUM_CHANNELS * BITS_PER_SAMPLE / 8
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
        putIntLE(24, sampleRate)
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
        return File(dir, "REC_" + ts + ".wav")
    }
}
