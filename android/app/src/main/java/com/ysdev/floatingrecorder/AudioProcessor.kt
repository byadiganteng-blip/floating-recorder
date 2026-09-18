package com.ysdev.floatingrecorder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class AudioProcessor(
    private val sampleRate: Int = 44100,
    private val amplifyGain: Float = 3.0f,
    private val noiseGateThreshold: Float = 0.015f,
    private val duckingRatio: Float = 0.4f
) {
    private var hpPrevIn = 0f
    private var hpPrevOut = 0f
    private val hpAlpha = 0.95f
    private var noiseFloor = 0.01f

    fun process(buffer: ShortArray) {
        var sum = 0.0
        for (s in buffer) sum += abs(s.toDouble()) / 32768.0
        val avgAmp = (sum / buffer.size).toFloat()
        noiseFloor = noiseFloor * 0.95f + avgAmp * 0.05f
        val dynamicGate = max(noiseGateThreshold, noiseFloor * 1.5f)

        for (i in buffer.indices) {
            var sample = buffer[i] / 32768.0f
            val hpOut = hpAlpha * (hpPrevOut + sample - hpPrevIn)
            hpPrevIn = sample
            hpPrevOut = hpOut
            sample = hpOut
            if (abs(sample) < dynamicGate) {
                sample *= 0.1f
            } else {
                sample *= duckingRatio
            }
            sample *= amplifyGain
            if (sample > 1.0f) sample = 1.0f - (1.0f - sample) * 0.5f
            if (sample < -1.0f) sample = -1.0f - (-1.0f - sample) * 0.5f
            buffer[i] = (sample * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    fun rmsLevel(buffer: ShortArray): Float {
        var sum = 0.0
        for (s in buffer) sum += s.toDouble() * s.toDouble()
        return sqrt(sum / buffer.size).toFloat() / 32768.0f
    }
}
