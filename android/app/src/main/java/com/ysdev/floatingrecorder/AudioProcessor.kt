package com.ysdev.floatingrecorder

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * AudioProcessor v2 — Fix suara gemericik + mic tidak terdengar.
 *
 * Prinsip:
 *  - Amplify LEMBUT (default 1.5x), tidak 3x — hindari clipping
 *  - Noise gate RENDAH (0.003) — suara user tetap lewat
 *  - Ducking LEMBUT (0.85) — suara sekitar tetap terdengar
 *  - Soft limiter TANPA distorsi
 *  - Auto-gain: normalisasi ke target level
 */
class AudioProcessor(
    private val sampleRate: Int = 44100,
    // AMPLIFY: LEMBUT — 1.5x default, max 4x
    private val amplifyGain: Float = 1.5f,
    // NOISE GATE: sangat rendah supaya suara user tidak terpotong
    private val noiseGateThreshold: Float = 0.003f,
    // DUCKING: hanya kurangi 15% (bukan 60%)
    private val duckingRatio: Float = 0.85f,
    // TARGET RMS untuk auto-gain
    private val targetRms: Float = 0.15f,
    // SOFT LIMIT — bukan hard clip
    private val limiterThreshold: Float = 0.95f
) {

    // High-pass filter (buang DC & hum < 80Hz)
    private var hpPrevIn = 0f
    private var hpPrevOut = 0f
    private val hpAlpha = 0.97f   // cutoff lebih tinggi (~100Hz) untuk buang gemericik

    // Auto gain smoothing
    private var currentGain = 1.0f
    private val gainSmoothing = 0.995f

    // Noise floor tracking
    private var noiseFloor = 0.001f

    fun process(buffer: ShortArray) {
        // ═══ 1. HITUNG AMPLITUDE RATA-RATA ═══
        var sum = 0.0
        var peak = 0.0
        for (s in buffer) {
            val a = abs(s.toDouble()) / 32768.0
            sum += a
            if (a > peak) peak = a
        }
        val avgAmp = (sum / buffer.size).toFloat()

        // Update noise floor (moving average)
        noiseFloor = noiseFloor * 0.98f + avgAmp * 0.02f

        // ═══ 2. AUTO GAIN berdasarkan RMS ═══
        val rms = rmsLevel(buffer)
        val targetGain = if (rms > 0.001f) {
            (targetRms / rms).coerceIn(0.5f, 4.0f)
        } else 1.0f

        // Smooth gain transition
        currentGain = currentGain * gainSmoothing + targetGain * (1 - gainSmoothing)

        // ═══ 3. NOISE GATE ADAPTIF ═══
        // Gate hanya aktif kalau sinyal di BAWAH noise floor * 2
        val dynamicGate = max(noiseGateThreshold, noiseFloor * 2.0f)

        for (i in buffer.indices) {
            var sample = buffer[i] / 32768.0f

            // High-pass filter — buang frekuensi rendah (hum, gemericik DC)
            val hpOut = hpAlpha * (hpPrevOut + sample - hpPrevIn)
            hpPrevIn = sample
            hpPrevOut = hpOut
            sample = hpOut

            // Noise gate — kurangi hanya sinyal SANGAT lemah
            val amp = abs(sample)
            if (amp < dynamicGate) {
                sample *= 0.3f   // Reduce 70% (bukan 90%)
            }

            // Ducking LEMBUT — kalau ada sinyal kuat (musik), suara sekitar tetap lewat
            if (amp > 0.05f) {
                sample *= duckingRatio
            }

            // ═══ 4. AMPLIFY (gain × auto-gain) ═══
            sample *= amplifyGain * currentGain

            // ═══ 5. SOFT LIMITER tanpa distorsi ═══
            if (sample > limiterThreshold) {
                // Tanh-style soft limit
                val excess = sample - limiterThreshold
                sample = limiterThreshold + (1 - limiterThreshold) * (1 - kotlin.math.exp(-excess * 3))
            } else if (sample < -limiterThreshold) {
                val excess = -sample - limiterThreshold
                sample = -(limiterThreshold + (1 - limiterThreshold) * (1 - kotlin.math.exp(-excess * 3)))
            }

            // Clamp final
            sample = min(1.0f, max(-1.0f, sample))

            // Convert ke PCM 16-bit
            buffer[i] = (sample * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    fun rmsLevel(buffer: ShortArray): Float {
        var sum = 0.0
        for (s in buffer) sum += s.toDouble() * s.toDouble()
        return sqrt(sum / buffer.size).toFloat() / 32768.0f
    }
}
