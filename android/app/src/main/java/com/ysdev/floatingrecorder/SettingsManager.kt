package com.ysdev.floatingrecorder

import android.content.Context

object SettingsManager {
    private const val PREF = "floating_recorder_prefs"
    private const val K_AMPLIFY = "amplify"
    private const val K_NOISE_GATE = "noise_gate"
    private const val K_MODE = "audio_mode"
    private const val K_SCR_RES = "scr_resolution"
    private const val K_SCR_FPS = "scr_fps"
    private const val K_SCR_BITRATE = "scr_bitrate"

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun getAmplify(c: Context) = prefs(c).getFloat(K_AMPLIFY, 2.0f)
    fun setAmplify(c: Context, v: Float) = prefs(c).edit().putFloat(K_AMPLIFY, v).apply()
    fun getNoiseGate(c: Context) = prefs(c).getFloat(K_NOISE_GATE, 0.005f)
    fun setNoiseGate(c: Context, v: Float) = prefs(c).edit().putFloat(K_NOISE_GATE, v).apply()
    fun getMode(c: Context) = prefs(c).getString(K_MODE, AudioMode.MIC_DIRECT.name) ?: "MIC_DIRECT"
    fun setMode(c: Context, name: String) = prefs(c).edit().putString(K_MODE, name).apply()
    fun getScrResIndex(c: Context) = prefs(c).getInt(K_SCR_RES, 1)
    fun setScrResIndex(c: Context, i: Int) = prefs(c).edit().putInt(K_SCR_RES, i).apply()
    fun getScrFpsIndex(c: Context) = prefs(c).getInt(K_SCR_FPS, 1)
    fun setScrFpsIndex(c: Context, i: Int) = prefs(c).edit().putInt(K_SCR_FPS, i).apply()
    fun getScrBitrateIndex(c: Context) = prefs(c).getInt(K_SCR_BITRATE, 2)
    fun setScrBitrateIndex(c: Context, i: Int) = prefs(c).edit().putInt(K_SCR_BITRATE, i).apply()
}
