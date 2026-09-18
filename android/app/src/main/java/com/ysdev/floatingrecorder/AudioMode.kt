package com.ysdev.floatingrecorder
import android.media.MediaRecorder
import android.os.Build

enum class AudioMode(
    val display: String,
    val minSdk: Int,
    val audioSource: Int,
    val description: String
) {
    INTERNAL_APC(
        "Internal (APC)",
        Build.VERSION_CODES.Q,
        MediaRecorder.AudioSource.MIC,
        "Rekam audio internal (Android 10+)"
    ),
    VOICE_RECOGNITION(
        "Voice Recognition",
        Build.VERSION_CODES.CUPCAKE,
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        "Mic tanpa AGC"
    ),
    MIC_ENHANCED(
        "MIC + Effects",
        Build.VERSION_CODES.CUPCAKE,
        MediaRecorder.AudioSource.MIC,
        "Mic + AEC + NS + AGC"
    ),
    CAMCORDER(
        "Camcorder",
        Build.VERSION_CODES.CUPCAKE,
        MediaRecorder.AudioSource.CAMCORDER,
        "Mic dengan tuning kamera"
    );

    companion object {
        fun availableModes(): List<AudioMode> =
            values().filter { Build.VERSION.SDK_INT >= it.minSdk }

        fun bestMode(): AudioMode {
            return when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> INTERNAL_APC
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> MIC_ENHANCED
                else -> VOICE_RECOGNITION
            }
        }

        fun fallbackChain(): List<AudioMode> {
            val modes = mutableListOf<AudioMode>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                modes.add(INTERNAL_APC)
            }
            modes.add(VOICE_RECOGNITION)
            modes.add(MIC_ENHANCED)
            modes.add(CAMCORDER)
            return modes
        }
    }
}
