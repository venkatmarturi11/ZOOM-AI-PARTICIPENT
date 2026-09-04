package com.zoomrecord.app.library

import android.net.Uri

/**
 * Represents a single recorded meeting video stored in MediaStore,
 * with optional companion audio (MP3).
 */
data class RecordingItem(
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val dateAddedEpochSec: Long,
    val isAudio: Boolean = false,
    val audioUri: Uri? = null,
)
