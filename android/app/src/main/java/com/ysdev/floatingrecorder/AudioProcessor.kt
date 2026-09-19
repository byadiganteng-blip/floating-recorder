package com.ysdev.floatingrecorder

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.PI

class AudioProcessor(
    private val sampleRate: Int = 44100,
    private val amplifyGain: Float = 2.0f,
    private val limiterThreshold: Float = 0.92f,
    private val hpCutoffHz: Float = 60f,
    private val noiseGateThreshold: Float = 0.005f
) {
    private var hpPrevIn = 0f
    private var hpPrevOut = 0f
    private val hpAlpha: Float = run {
        val rc = 1.0f / (2.0f * PI.toFloat() * hpCutoffHz)
        val dt = 1.0f / sampleRate
        rc / (rc + dt)
    }

    fun process(buffer: ShortArray) {
        for (i in buffer.indices) {
            var s = buffer[i] / 32768.0f
            val hpOut = hpAlpha * (hpPrevOut + s - hpPrevIn)
            hpPrevIn = s
            hpPrevOut = hpOut
            s = hpOut
            if (abs(s) < noiseGateThreshold) s *= 0.3f
            s *= amplifyGain
            if (s > limiterThreshold) {
                val x = s - limiterThreshold
                s = limiterThreshold + (1 - limiterThreshold) * (1 - exp(-x * 2))
            } else if (s < -limiterThreshold) {
                val x = -s - limiterThreshold
                s = -(limiterThreshold + (1 - limiterThreshold) * (1 - exp(-x * 2)))
            }
            s = min(1.0f, max(-1.0f, s))
            buffer[i] = (s * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    fun rmsLevel(buffer: ShortArray): Float {
        var sum = 0.0
        for (s in buffer) sum += s.toDouble() * s.toDouble()
        return sqrt(sum / buffer.size).toFloat() / 32768.0f
    }
}
