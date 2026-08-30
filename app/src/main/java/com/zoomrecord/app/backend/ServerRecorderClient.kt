package com.zoomrecord.app.backend

import android.content.Context
import android.media.MediaScannerConnection
import android.util.Log
import com.zoomrecord.app.library.RecordingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class ServerRecordingResult(
    val success: Boolean,
    val meetingId: String?,
    val zoomMeetingId: String?,
    val status: String?,
    val liveUrl: String?,
    val liveMonitorUrl: String? = null,
    val lanUrl: String? = null,
    val message: String?
)

data class ActiveRecordingStatus(
    val active: Boolean,
    val meetingId: String?,
    val zoomMeetingId: String?,
    val status: String?,
    val displayName: String?,
    val frameCount: Int,
    val liveScreenUrl: String?
)

data class ServerRecordingItem(
    val id: String,
    val meetingId: String,
    val zoomMeetingId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val createdAt: String
)

object ServerRecorderClient {

    private const val TAG = "ServerRecorderClient"
    private const val PREFS_NAME = "server_recorder_config"
    private const val KEY_BASE_URL = "server_base_url"

    // Default backend URL: Auto-configured to your 24/7 Render Cloud Server
    const val DEFAULT_BASE_URL = "https://zoom-ai-participent.onrender.com"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun getBaseUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL)?.trimEnd('/') ?: DEFAULT_BASE_URL
    }

    fun setBaseUrl(context: Context, url: String) {
        val clean = url.trim().trimEnd('/')
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_BASE_URL, clean).apply()
    }

    /**
     * Sends invite link and optional Zoom account sign-in details to the Website / Backend
     * to launch headless recording with audio & video.
     */
    suspend fun startRecording(
        context: Context,
        meetingUrl: String,
        passcode: String? = null,
        displayName: String = "Meeting Assistant",
        zoomEmail: String? = null,
        zoomPassword: String? = null
    ): Result<ServerRecordingResult> = withContext(Dispatchers.IO) {
        try {
            val base = getBaseUrl(context)
            val json = JSONObject().apply {
                put("meetingUrl", meetingUrl)
                if (!passcode.isNullOrBlank()) put("passcode", passcode)
                put("displayName", displayName)
                if (!zoomEmail.isNullOrBlank()) put("zoomEmail", zoomEmail)
                if (!zoomPassword.isNullOrBlank()) put("zoomPassword", zoomPassword)
            }

            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("$base/api/bot/record")
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            val respBody = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Backend HTTP ${response.code}: $respBody"))
            }

            val obj = JSONObject(respBody)
            Result.success(
                ServerRecordingResult(
                    success = obj.optBoolean("success", true),
                    meetingId = obj.optString("meetingId"),
                    zoomMeetingId = obj.optString("zoomMeetingId"),
                    status = obj.optString("status"),
                    liveUrl = "$base/api/live/screen",
                    liveMonitorUrl = obj.optString("liveMonitorUrl", "$base/"),
                    lanUrl = obj.optString("lanUrl", base),
                    message = obj.optString("message")
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "startRecording error", e)
            Result.failure(e)
        }
    }

    /**
     * Uploads a local recording file from the phone to the Website's Cloud Storage Vault.
     */
    suspend fun uploadMobileRecordingToCloud(
        context: Context,
        file: File
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val base = getBaseUrl(context)
            val requestBody = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("source", "mobile")
                .addFormDataPart("category", "mobile_storage")
                .addFormDataPart(
                    "files",
                    file.name,
                    file.asRequestBody("video/mp4".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url("$base/api/storage/upload")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val respBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Upload failed HTTP ${response.code}: $respBody"))
            }
            Result.success("Successfully uploaded to Cloud Storage Vault")
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading to Cloud Vault", e)
            Result.failure(e)
        }
    }

    /**
     * Gets status of current active server recording.
     */
    suspend fun getActiveStatus(context: Context): Result<ActiveRecordingStatus> = withContext(Dispatchers.IO) {
        try {
            val base = getBaseUrl(context)
            val request = Request.Builder()
                .url("$base/api/bot/active")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val respBody = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}: $respBody"))
            }

            val obj = JSONObject(respBody)
            Result.success(
                ActiveRecordingStatus(
                    active = obj.optBoolean("active", false),
                    meetingId = if (obj.has("meetingId")) obj.getString("meetingId") else null,
                    zoomMeetingId = if (obj.has("zoomMeetingId")) obj.getString("zoomMeetingId") else null,
                    status = obj.optString("status", "IDLE"),
                    displayName = if (obj.has("displayName")) obj.getString("displayName") else null,
                    frameCount = obj.optInt("frameCount", 0),
                    liveScreenUrl = "$base/api/live/screen"
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Requests the Website backend to stop recording and finalize the MP4.
     */
    suspend fun stopRecording(context: Context, meetingId: String? = null): Result<String> = withContext(Dispatchers.IO) {
        try {
            val base = getBaseUrl(context)
            val json = JSONObject().apply {
                if (!meetingId.isNullOrBlank()) put("meetingId", meetingId)
            }

            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("$base/api/bot/stop")
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            val respBody = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}: $respBody"))
            }

            val obj = JSONObject(respBody)
            Result.success(obj.optString("message", "Recording finalized"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches list of all recordings saved on the Website server.
     */
    suspend fun fetchRecordings(context: Context): Result<List<ServerRecordingItem>> = withContext(Dispatchers.IO) {
        try {
            val base = getBaseUrl(context)
            val request = Request.Builder()
                .url("$base/api/recordings")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val respBody = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}: $respBody"))
            }

            val arr = JSONArray(respBody)
            val list = mutableListOf<ServerRecordingItem>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                list.add(
                    ServerRecordingItem(
                        id = item.getString("id"),
                        meetingId = item.optString("meetingId"),
                        zoomMeetingId = item.optString("zoomMeetingId"),
                        fileName = item.optString("fileName", "recording.mp4"),
                        fileSize = item.optLong("fileSize", 0L),
                        mimeType = item.optString("mimeType", "video/mp4"),
                        createdAt = item.optString("createdAt")
                    )
                )
            }
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Downloads an MP4 recording directly from the Website server into the App's storage
     * and registers it with Android MediaStore so it appears in "Saved Records".
     */
    suspend fun downloadRecording(
        context: Context,
        recordingId: String,
        fileName: String,
        onProgress: ((Float) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val base = getBaseUrl(context)
            val url = "$base/api/recordings/$recordingId/download"

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Download failed HTTP ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Empty body"))
            val targetDir = RecordingsRepository.getRecordingsDir(context)
            val cleanName = if (fileName.endsWith(".mp4")) fileName else "$fileName.mp4"
            val targetFile = File(targetDir, cleanName)

            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            body.byteStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0) {
                            onProgress?.invoke(downloadedBytes.toFloat() / totalBytes.toFloat())
                        }
                    }
                    output.flush()
                }
            }

            // Notify Android MediaScanner so file is instantly recognized
            MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf("video/mp4"),
                null
            )

            Log.i(TAG, "Successfully downloaded recording to ${targetFile.absolutePath} (${targetFile.length()} bytes)")
            Result.success(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading recording", e)
            Result.failure(e)
        }
    }
}
