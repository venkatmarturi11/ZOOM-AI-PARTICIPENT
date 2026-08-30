package com.zoomrecord.app.recording

import android.content.Context
import android.os.StatFs

/**
 * Checks available storage before and during recording to prevent
 * running out of space mid-write (which corrupts the MP4 file).
 */
object StorageGuard {

    /**
     * Returns true if there's enough free space for the estimated recording
     * plus a safety margin.
     *
     * @param estimatedBytes Expected recording size in bytes.
     * @param marginBytes    Extra headroom to keep free (default 500 MB).
     */
    fun hasEnoughSpace(
        context: Context,
        estimatedBytes: Long,
        marginBytes: Long = 500L * 1024 * 1024,
    ): Boolean {
        val path = context.getExternalFilesDir(null)?.path ?: return false
        val stat = StatFs(path)
        return stat.availableBytes > estimatedBytes + marginBytes
    }

    /**
     * Returns the number of bytes currently available on external storage.
     */
    fun availableBytes(context: Context): Long {
        val path = context.getExternalFilesDir(null)?.path ?: return 0L
        return StatFs(path).availableBytes
    }
}

/**
 * Estimates the file size for a recording of the given duration.
 *
 * @param config  Recording configuration (bitrate values).
 * @param minutes Duration in minutes.
 * @return Estimated file size in bytes.
 */
fun estimateBytesFor(config: RecordingConfig, minutes: Int = 60): Long =
    ((config.videoBitrate + config.audioBitrate) / 8L) * 60 * minutes
