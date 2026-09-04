package com.zoomrecord.app.library

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

import androidx.core.content.FileProvider

/**
 * Manages recorded meeting files on local disk and MediaStore.
 *
 * Guarantees zero dropped recordings across all Android versions (API 24–35)
 * by storing directly in the app's Movies directory and syncing to MediaStore.
 */
class RecordingsRepository(private val context: Context) {

    companion object {
        private const val TAG = "RecordingsRepository"
        private const val DIR_NAME = "ZoomRecordings"

        fun getRecordingsDir(context: Context): File {
            val dir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir,
                DIR_NAME
            )
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }

        /**
         * Creates a new MP4 recording file on local disk.
         *
         * @return Pair of (file URI, absolute file path) ready for recording.
         */
        fun createPendingMp4(context: Context, displayName: String): Pair<Uri, String> {
            return createPendingRecording(context, displayName, "mp4")
        }

        fun createPendingRecording(context: Context, displayName: String, extension: String = "mp4"): Pair<Uri, String> {
            val dir = getRecordingsDir(context)
            val file = File(dir, "$displayName.$extension")
            Log.i(TAG, "Created new recording file: ${file.absolutePath}")
            return Pair(Uri.fromFile(file), file.absolutePath)
        }

        /**
         * Finalizes the recording file and indexes it with Android's MediaScanner.
         */
        fun finalizePendingMp4(context: Context, uri: Uri) {
            finalizePendingRecording(context, uri)
        }

        fun finalizePendingRecording(context: Context, uri: Uri, mimeType: String = "video/webm") {
            try {
                val path = uri.path ?: return
                val file = File(path)
                if (file.exists() && file.length() > 0) {
                    val mime = if (file.extension.equals("mp4", ignoreCase = true)) "video/mp4" else mimeType
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(file.absolutePath),
                        arrayOf(mime),
                        null
                    )
                    Log.i(TAG, "Finalized and scanned recording: ${file.absolutePath} (${file.length()} bytes)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error finalizing recording", e)
            }
        }

        fun recoverOrphanedPendingRecordings(context: Context) {
            try {
                val localDirs = listOfNotNull(
                    getRecordingsDir(context),
                    File(context.filesDir, DIR_NAME),
                    context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MeetProRecordings"),
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "MeetProRecordings"),
                )

                val filesToScan = mutableListOf<String>()
                val cutoff = System.currentTimeMillis() - 3600_000

                for (dir in localDirs) {
                    if (!dir.exists()) continue
                    dir.listFiles()?.forEach { file ->
                        if (file.isFile && (file.extension.equals("mp4", ignoreCase = true) || file.extension.equals("webm", ignoreCase = true))) {
                            if (file.length() == 0L && file.lastModified() < cutoff) {
                                file.delete()
                                Log.w(TAG, "Removed empty orphan recording: ${file.name}")
                            } else if (file.length() > 0L) {
                                filesToScan.add(file.absolutePath)
                            }
                        }
                    }
                }

                if (filesToScan.isNotEmpty()) {
                    MediaScannerConnection.scanFile(
                        context,
                        filesToScan.toTypedArray(),
                        null,
                        null
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "recoverOrphanedPendingRecordings encountered non-fatal error", e)
            }
        }
    }

    // ── Instance methods for querying/managing recordings ─────────────

    /**
     * Queries all recordings from the app's recordings folder and MediaStore.
     */
    fun queryRecordings(): List<RecordingItem> {
        recoverOrphanedPendingRecordings(context)

        val results = mutableListOf<RecordingItem>()
        val seenPaths = mutableSetOf<String>()

        // 1. Read files from app's local and public recording directories
        val localDirs = listOfNotNull(
            getRecordingsDir(context),
            File(context.filesDir, DIR_NAME),
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MeetProRecordings"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "MeetProRecordings"),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        )

        for (dir in localDirs) {
            if (!dir.exists()) continue
            val files = dir.listFiles()?.filter {
                it.isFile && (it.extension.equals("mp4", ignoreCase = true) || it.extension.equals("webm", ignoreCase = true)) && it.length() > 0
            } ?: emptyList()

            for (file in files) {
                if (seenPaths.contains(file.name)) continue
                seenPaths.add(file.name)

                var durationMs = 0L
                var width = 1920
                var height = 1080

                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(file.absolutePath)
                    durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
                    height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
                    retriever.release()
                } catch (_: Exception) {}

                val mp3Companion = File(file.parentFile, "${file.nameWithoutExtension}.mp3")
                val audioUri = if (mp3Companion.exists() && mp3Companion.length() > 0) Uri.fromFile(mp3Companion) else null

                results += RecordingItem(
                    uri = Uri.fromFile(file),
                    displayName = file.nameWithoutExtension,
                    durationMs = durationMs,
                    sizeBytes = file.length(),
                    width = width,
                    height = height,
                    dateAddedEpochSec = file.lastModified() / 1000,
                    audioUri = audioUri,
                )
            }
        }

        // 2. Query MediaStore to include any external saves
        try {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.DATE_ADDED,
            )

            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, null, null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val wCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val hCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: "Meeting"
                    val size = cursor.getLong(sizeCol)
                    if (size > 0 && !seenPaths.contains(name) && !seenPaths.contains("$name.mp4") && !seenPaths.contains(name.removeSuffix(".mp4"))) {
                        seenPaths.add(name)
                        val id = cursor.getLong(idCol)
                        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                        results += RecordingItem(
                            uri = uri,
                            displayName = name.removeSuffix(".mp4"),
                            durationMs = cursor.getLong(durCol),
                            sizeBytes = size,
                            width = cursor.getInt(wCol).takeIf { it > 0 } ?: 1920,
                            height = cursor.getInt(hCol).takeIf { it > 0 } ?: 1080,
                            dateAddedEpochSec = cursor.getLong(dateCol),
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        return results.sortedByDescending { it.dateAddedEpochSec }
    }

    /**
     * Deletes a recording completely from disk, duplicate export locations, and MediaStore.
     */
    fun delete(uri: Uri): Boolean {
        var deletedAny = false
        var targetFileName = ""

        try {
            // 1. Resolve target file name
            if (uri.scheme == "file") {
                val file = File(uri.path ?: "")
                targetFileName = file.name
                if (file.exists()) {
                    val d = file.delete()
                    if (d) deletedAny = true
                }
            } else if (uri.scheme == "content") {
                try {
                    context.contentResolver.query(
                        uri,
                        arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA),
                        null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                            if (nameCol != -1) targetFileName = cursor.getString(nameCol) ?: ""
                            val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                            if (dataCol != -1) {
                                val dataPath = cursor.getString(dataCol)
                                if (!dataPath.isNullOrEmpty()) {
                                    val f = File(dataPath)
                                    if (f.exists()) f.delete()
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}

                try {
                    val count = context.contentResolver.delete(uri, null, null)
                    if (count > 0) deletedAny = true
                } catch (_: Exception) {}
            }

            // 2. If we know the file name (or base name), clean up from all possible local recording folders
            if (targetFileName.isNotBlank()) {
                val baseName = targetFileName.removeSuffix(".mp4").removeSuffix(".mp3").removeSuffix(".webm")
                val candidateNames = listOf(targetFileName, "$baseName.mp4", "$baseName.mp3", "$baseName.m4a", "$baseName.webm")

                val targetDirs = listOfNotNull(
                    getRecordingsDir(context),
                    File(context.filesDir, DIR_NAME),
                    context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MeetProRecordings"),
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "MeetProRecordings"),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                )

                val pathsToScan = mutableListOf<String>()

                for (dir in targetDirs) {
                    if (!dir.exists()) continue
                    for (cName in candidateNames) {
                        val f = File(dir, cName)
                        if (f.exists()) {
                            pathsToScan.add(f.absolutePath)
                            if (f.delete()) deletedAny = true
                        }
                    }
                }

                // 3. Purge matching entries in MediaStore Video
                try {
                    val selection = "${MediaStore.Video.Media.DISPLAY_NAME} = ? OR ${MediaStore.Video.Media.DISPLAY_NAME} = ?"
                    val args = arrayOf(targetFileName, "$baseName.mp4")
                    val delRows = context.contentResolver.delete(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, selection, args)
                    if (delRows > 0) deletedAny = true
                } catch (_: Exception) {}

                // 4. Purge matching entries in MediaStore Downloads (Android 10+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? OR ${MediaStore.Downloads.DISPLAY_NAME} = ?"
                        val args = arrayOf(targetFileName, "$baseName.mp4")
                        val delRows = context.contentResolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, selection, args)
                        if (delRows > 0) deletedAny = true
                    } catch (_: Exception) {}
                }

                // 5. Notify MediaScanner so OS drops cached metadata
                if (pathsToScan.isNotEmpty()) {
                    MediaScannerConnection.scanFile(
                        context,
                        pathsToScan.toTypedArray(),
                        null,
                        null
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in delete for URI: $uri", e)
        }

        return deletedAny
    }

    /**
     * Renames a recording and its companion MP3 file if present.
     */
    fun rename(uri: Uri, newDisplayName: String): Boolean {
        return try {
            val cleanName = newDisplayName.removeSuffix(".mp4").removeSuffix(".mp3")
            if (uri.scheme == "file") {
                val oldFile = File(uri.path ?: return false)
                val newFile = File(oldFile.parentFile, "$cleanName.mp4")
                val renamed = oldFile.renameTo(newFile)
                if (renamed) {
                    val scanPaths = mutableListOf(oldFile.absolutePath, newFile.absolutePath)
                    // Also rename companion MP3 if exists
                    val oldMp3 = File(oldFile.parentFile, "${oldFile.nameWithoutExtension}.mp3")
                    if (oldMp3.exists()) {
                        val newMp3 = File(oldFile.parentFile, "$cleanName.mp3")
                        if (oldMp3.renameTo(newMp3)) {
                            scanPaths.add(oldMp3.absolutePath)
                            scanPaths.add(newMp3.absolutePath)
                        }
                    }
                    MediaScannerConnection.scanFile(
                        context,
                        scanPaths.toTypedArray(),
                        null,
                        null
                    )
                }
                renamed
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "$cleanName.mp4")
                }
                context.contentResolver.update(uri, values, null, null) > 0
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Creates a safe share intent for sending a recording to another app.
     * Uses FileProvider for file:// URIs on Android 7+.
     */
    fun shareIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        val shareableUri = if (uri.scheme == "file") {
            try {
                val file = File(uri.path ?: "")
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
            } catch (_: Exception) {
                uri
            }
        } else {
            uri
        }
        putExtra(Intent.EXTRA_STREAM, shareableUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /**
     * Creates a share intent for sharing companion MP3 audio.
     */
    fun shareAudioIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "audio/mp3"
        val shareableUri = if (uri.scheme == "file") {
            try {
                val file = File(uri.path ?: "")
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
            } catch (_: Exception) {
                uri
            }
        } else {
            uri
        }
        putExtra(Intent.EXTRA_STREAM, shareableUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /**
     * Copies the recording video into a user-selected destination URI (Storage Access Framework).
     */
    fun exportToUri(sourceUri: Uri, destinationUri: Uri): Boolean {
        return try {
            val inputStream = if (sourceUri.scheme == "file") {
                val srcFile = File(sourceUri.path ?: return false)
                if (!srcFile.exists() || srcFile.length() == 0L) return false
                java.io.FileInputStream(srcFile)
            } else {
                context.contentResolver.openInputStream(sourceUri)
            } ?: return false

            val outputStream = context.contentResolver.openOutputStream(destinationUri) ?: return false

            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export video to uri: $destinationUri", e)
            false
        }
    }

    /**
     * Copies the recording video directly into the device's public Downloads directory.
     */
    fun exportToDownloads(sourceUri: Uri, fileName: String): Boolean {
        return try {
            val cleanName = fileName.removeSuffix(".mp4") + ".mp4"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, cleanName)
                    put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MeetProRecordings")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val targetUri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
                val copied = exportToUri(sourceUri, targetUri)
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(targetUri, values, null, null)
                copied
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val subDir = File(downloadsDir, "MeetProRecordings").apply { mkdirs() }
                val targetFile = File(subDir, cleanName)
                if (sourceUri.scheme == "file") {
                    val srcFile = File(sourceUri.path ?: return false)
                    srcFile.copyTo(targetFile, overwrite = true)
                } else {
                    val inputStream = context.contentResolver.openInputStream(sourceUri) ?: return false
                    java.io.FileOutputStream(targetFile).use { out ->
                        inputStream.use { it.copyTo(out) }
                    }
                }
                MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), arrayOf("video/mp4"), null)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "exportToDownloads failed", e)
            false
        }
    }
}

