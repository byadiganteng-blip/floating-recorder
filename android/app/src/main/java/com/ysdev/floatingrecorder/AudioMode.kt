package com.ysdev.floatingrecorder

import android.media.MediaRecorder
import android.os.Build

enum class AudioMode(
    val display: String,
    val minSdk: Int,
    val audioSource: Int,
    val description: String
) {
    UNPROCESSED("Unprocessed", Build.VERSION_CODES.N,
        MediaRecorder.AudioSource.UNPROCESSED, "Mic tanpa processing"),
    MIC_DIRECT("MIC Direct", Build.VERSION_CODES.CUPCAKE,
        MediaRecorder.AudioSource.MIC, "Mic mentah, sensitif vokal"),
    VOICE_RECOGNITION("Voice Recog", Build.VERSION_CODES.CUPCAKE,
        MediaRecorder.AudioSource.VOICE_RECOGNITION, "Mic tanpa AGC"),
    CAMCORDER("Camcorder", Build.VERSION_CODES.CUPCAKE,
        MediaRecorder.AudioSource.CAMCORDER, "Mic tuning kamera"),
    DEFAULT("Default", Build.VERSION_CODES.CUPCAKE,
        MediaRecorder.AudioSource.DEFAULT, "Source default");

    companion object {
        fun availableModes(): List<AudioMode> =
            values().filter { Build.VERSION.SDK_INT >= it.minSdk }

        fun bestMode(): AudioMode = MIC_DIRECT

        fun fallbackChain(): List<AudioMode> {
            val m = mutableListOf<AudioMode>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) m.add(UNPROCESSED)
            m.add(MIC_DIRECT); m.add(VOICE_RECOGNITION)
            m.add(CAMCORDER); m.add(DEFAULT)
            return m
        }
    }
}
