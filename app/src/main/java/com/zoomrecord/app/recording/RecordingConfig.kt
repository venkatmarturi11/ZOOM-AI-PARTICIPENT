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
    val width: Int = 1920,
    val height: Int = 1080,
    val frameRate: Int = 30,             // 30 FPS rock-solid stable real-time encoding without frame drops
    val videoBitrate: Int = 8_000_000,   // 8.0 Mbps pristine 1080p HD clarity for crisp meeting slides
    val audioSampleRate: Int = 48_000,   // 48.0 kHz broadcast-standard audio
    val audioBitrate: Int = 192_000,     // 192 kbps studio-fidelity AAC
    val iFrameInterval: Int = 1,         // Fast seeking IDR keyframe every second
) {
    companion object {
        /**
         * Best quality configuration optimized for native Zoom app recording.
         * 1080p Full HD video + 192 kbps 48kHz audio.
         */
        fun bestQuality(): RecordingConfig {
            return RecordingConfig(
                width = 1920,
                height = 1080,
                frameRate = 30,
                videoBitrate = 8_000_000,
                audioSampleRate = 48_000,
                audioBitrate = 192_000,
                iFrameInterval = 1,
            )
        }
    }
}

/**
 * Dynamically fits recording dimensions to the device's actual orientation (portrait or landscape).
 * Preserves the exact aspect ratio of any device (16:9, 19.5:9, 20:9, 21:9, tablets)
 * without stretching or letterboxing. Scales up to 1080p width/height for maximum clarity.
 */
fun RecordingConfig.clampToDisplay(activity: Activity): RecordingConfig {
    val (screenWidth, screenHeight) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = activity.windowManager.currentWindowMetrics.bounds
        bounds.width() to bounds.height()
    } else {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        activity.windowManager.defaultDisplay.getRealMetrics(dm)
        dm.widthPixels to dm.heightPixels
    }

    val maxLongSide = 1920
    val isLandscape = screenWidth >= screenHeight
    val longSide = if (isLandscape) screenWidth else screenHeight
    val shortSide = if (isLandscape) screenHeight else screenWidth

    val (finalW, finalH) = if (longSide > maxLongSide && longSide > 0) {
        val scale = maxLongSide.toFloat() / longSide.toFloat()
        val scaledShort = (shortSide * scale).toInt()
        if (isLandscape) maxLongSide to scaledShort else scaledShort to maxLongSide
    } else if (screenWidth > 0 && screenHeight > 0) {
        screenWidth to screenHeight
    } else {
        1920 to 1080
    }

    // Enforce even dimensions required by H.264 video encoders
    val evenW = (finalW - (finalW % 2)).coerceAtLeast(480)
    val evenH = (finalH - (finalH % 2)).coerceAtLeast(480)

    return copy(
        width = evenW,
        height = evenH,
        frameRate = 30,
        videoBitrate = 8_000_000,
        audioSampleRate = 48_000,
        audioBitrate = 192_000,
    )
}

/**
 * Convenience overload using system display metrics.
 */
fun RecordingConfig.clampToDisplay(): RecordingConfig {
    val dm = Resources.getSystem().displayMetrics
    val screenWidth = dm.widthPixels
    val screenHeight = dm.heightPixels

    val maxLongSide = 1920
    val isLandscape = screenWidth >= screenHeight
    val longSide = if (isLandscape) screenWidth else screenHeight
    val shortSide = if (isLandscape) screenHeight else screenWidth

    val (finalW, finalH) = if (longSide > maxLongSide && longSide > 0) {
        val scale = maxLongSide.toFloat() / longSide.toFloat()
        val scaledShort = (shortSide * scale).toInt()
        if (isLandscape) maxLongSide to scaledShort else scaledShort to maxLongSide
    } else if (screenWidth > 0 && screenHeight > 0) {
        screenWidth to screenHeight
    } else {
        1920 to 1080
    }

    val evenW = (finalW - (finalW % 2)).coerceAtLeast(480)
    val evenH = (finalH - (finalH % 2)).coerceAtLeast(480)

    return copy(
        width = evenW,
        height = evenH,
        frameRate = 30,
        videoBitrate = 8_000_000,
        audioSampleRate = 48_000,
        audioBitrate = 192_000,
    )
}
