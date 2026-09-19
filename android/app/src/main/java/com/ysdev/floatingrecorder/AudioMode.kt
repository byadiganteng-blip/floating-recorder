package com.ysdev.floatingrecorder

import android.media.MediaRecorder
import android.os.Build

enum class AudioMode(
    val display: String,
    val minSdk: Int,
    val audioSource: Int,
    val description: String
) {
    UNPROCESSED(
        "Unprocessed",
        Build.VERSION_CODES.N,
        MediaRecorder.AudioSource.UNPROCESSED,
        "Mic tanpa processing (Android 7+)"
    ),
    MIC_DIRECT(
        "MIC Direct",
        Build.VERSION_CODES.CUPCAKE,
        MediaRecorder.AudioSource.MIC,
        "Mic mentah, paling sensitif untuk vokal"
    ),
    VOICE_RECOGNITION(
        "Voice Recognition",
        Build.VERSION_CODES.CUPCAKE,
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        "Mic tanpa AGC bawaan"
    ),
    CAMCORDER(
        "Camcorder",
        Build.VERSION_CODES.CUPCAKE,
        MediaRecorder.AudioSource.CAMCORDER,
        "Mic dengan tuning kamera"
    ),
    DEFAULT(
        "Default",
        Build.VERSION_CODES.CUPCAKE,
        MediaRecorder.AudioSource.DEFAULT,
        "Default source"
    ),
    MIC(
        "MIC",
        Build.VERSION_CODES.CUPCAKE,
        MediaRecorder.AudioSource.MIC,
        "Mic standar"
    );

    companion object {
        fun availableModes(): List<AudioMode> =
            values().filter { Build.VERSION.SDK_INT >= it.minSdk }

        fun bestMode(): AudioMode = MIC_DIRECT

        fun fallbackChain(): List<AudioMode> {
            val modes = mutableListOf<AudioMode>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                modes.add(UNPROCESSED)
            }
            modes.add(MIC_DIRECT)
            modes.add(VOICE_RECOGNITION)
            modes.add(CAMCORDER)
            modes.add(DEFAULT)
            return modes
        }
    }
}
