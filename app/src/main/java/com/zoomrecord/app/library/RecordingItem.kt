package com.zoomrecord.app.library

import android.net.Uri

/**
 * Represents a single recorded meeting video stored in MediaStore.
 */
data class RecordingItem(
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val dateAddedEpochSec: Long,
)
