package com.ysdev.floatingrecorder

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class AudioProcessor(
    private val sampleRate: Int = 44100,
    private val amplifyGain: Float = 2.0f,
    private val limiterThreshold: Float = 0.92f
) {
    private var hpPrevIn = 0f
    private var hpPrevOut = 0f
    private val hpAlpha = 0.98f

    fun process(buffer: ShortArray) {
        for (i in buffer.indices) {
            var sample = buffer[i] / 32768.0f

            // High-pass filter (buang hum < 60Hz)
            val hpOut = hpAlpha * (hpPrevOut + sample - hpPrevIn)
            hpPrevIn = sample
            hpPrevOut = hpOut
            sample = hpOut

            // Amplify
            sample *= amplifyGain

            // Soft limiter
            if (sample > limiterThreshold) {
                val x = sample - limiterThreshold
                sample = limiterThreshold + (1 - limiterThreshold) * (1 - exp(-x * 2))
            } else if (sample < -limiterThreshold) {
                val x = -sample - limiterThreshold
                sample = -(limiterThreshold + (1 - limiterThreshold) * (1 - exp(-x * 2)))
            }

            sample = min(1.0f, max(-1.0f, sample))
            buffer[i] = (sample * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    fun rmsLevel(buffer: ShortArray): Float {
        var sum = 0.0
        for (s in buffer) sum += s.toDouble() * s.toDouble()
        return sqrt(sum / buffer.size).toFloat() / 32768.0f
    }
}
