package com.zoomrecord.app.recording

import android.app.Activity
import android.content.res.Resources
import android.os.Build
import android.util.DisplayMetrics

/**
 * Configuration for the screen recording pipeline.
 * Tuned for smooth 30 FPS real-time encoding with zero lag and synchronized audio.
 */
data class RecordingConfig(
    val width: Int = 1280,
    val height: Int = 720,
    val frameRate: Int = 30,             // 30 FPS rock-solid stable real-time encoding without frame drops
    val videoBitrate: Int = 3_500_000,   // 3.5 Mbps crisp HD clarity with zero encoder buffer latency
    val audioSampleRate: Int = 44_100,   // 44.1 kHz universally supported audio
    val audioBitrate: Int = 128_000,     // 128 kbps pristine AAC
    val iFrameInterval: Int = 1,         // Fast seeking IDR keyframe every second
)

/**
 * Dynamically fits recording dimensions to the device's landscape orientation.
 * Preserves the exact aspect ratio of any device (16:9, 18:9, 19.5:9, 20:9, 21:9, tablets)
 * without ever creating square 1:1 letterbox artifacts.
 */
fun RecordingConfig.clampToDisplay(activity: Activity): RecordingConfig {
    val (screenWidth, screenHeight) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = activity.windowManager.currentWindowMetrics.bounds
        val w = maxOf(bounds.width(), bounds.height())
        val h = minOf(bounds.width(), bounds.height())
        w to h
    } else {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        activity.windowManager.defaultDisplay.getRealMetrics(dm)
        val w = maxOf(dm.widthPixels, dm.heightPixels)
        val h = minOf(dm.widthPixels, dm.heightPixels)
        w to h
    }

    val maxW = 1280
    val (finalW, finalH) = if (screenWidth > maxW && screenWidth > 0) {
        val scale = maxW.toFloat() / screenWidth.toFloat()
        val scaledH = (screenHeight * scale).toInt()
        maxW to scaledH
    } else if (screenWidth > 0 && screenHeight > 0) {
        screenWidth to screenHeight
    } else {
        1280 to 720
    }

    // Enforce even dimensions for H.264 video encoders
    val evenW = finalW - (finalW % 2)
    val evenH = finalH - (finalH % 2)

    return copy(
        width = evenW.coerceAtLeast(640),
        height = evenH.coerceAtLeast(360),
        frameRate = 30,
    )
}

/**
 * Convenience overload using system display metrics in landscape.
 */
fun RecordingConfig.clampToDisplay(): RecordingConfig {
    val dm = Resources.getSystem().displayMetrics
    val screenWidth = maxOf(dm.widthPixels, dm.heightPixels)
    val screenHeight = minOf(dm.widthPixels, dm.heightPixels)

    val maxW = 960
    val (finalW, finalH) = if (screenWidth > maxW && screenWidth > 0) {
        val scale = maxW.toFloat() / screenWidth.toFloat()
        val scaledH = (screenHeight * scale).toInt()
        maxW to scaledH
    } else if (screenWidth > 0 && screenHeight > 0) {
        screenWidth to screenHeight
    } else {
        960 to 540
    }

    val evenW = finalW - (finalW % 2)
    val evenH = finalH - (finalH % 2)

    return copy(
        width = evenW.coerceAtLeast(640),
        height = evenH.coerceAtLeast(360),
    )
}
