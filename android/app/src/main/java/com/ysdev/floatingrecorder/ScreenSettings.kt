package com.ysdev.floatingrecorder

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager

object ScreenSettings {

    data class Resolution(val label: String, val scale: Float)

    val RESOLUTIONS = listOf(
        Resolution("480p",  0.33f),
        Resolution("720p",  0.50f),
        Resolution("1080p", 0.75f),
        Resolution("Native", 1.0f)
    )

    val FPS_OPTIONS = listOf(24, 30, 60)
    val BITRATE_OPTIONS = listOf(2, 4, 8, 12, 20)

    fun detectScreenSize(context: Context): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    fun buildConfig(context: Context, resScale: Float, fps: Int,
                    bitrateMbps: Int, format: ScreenFormat): ScreenConfig {
        val (w, h) = detectScreenSize(context)
        var mw = (w * resScale).toInt(); var mh = (h * resScale).toInt()
        if (mw % 2 == 1) mw -= 1
        if (mh % 2 == 1) mh -= 1
        return ScreenConfig(
            width = mw, height = mh,
            dpi = context.resources.displayMetrics.densityDpi,
            fps = fps, videoBitrate = bitrateMbps * 1_000_000, format = format
        )
    }
}
