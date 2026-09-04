package com.zoomrecord.recorder

import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*
import com.zoomrecord.app.R

class RecordingService : Service() {
    companion object {
        const val ACTION_START = "com.zoomrecord.recorder.START"
        const val ACTION_STOP = "com.zoomrecord.recorder.STOP"
        const val ACTION_STATUS = "com.zoomrecord.recorder.STATUS"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_STATUS = "status"
        const val EXTRA_URI = "uri"
        const val EXTRA_ERROR = "error"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "RecordingService"
        @Volatile var running = false
            private set
    }

    private var projection: MediaProjection? = null
    private var muxer: MuxerController? = null
    private var video: ScreenCaptureEncoder? = null
    private var audio: AudioCaptureEncoder? = null
    private var outputUri: Uri? = null
    private var outputPfd: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        if (running) return
        try {
            startForegroundCompat()

            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            @Suppress("DEPRECATION")
            val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            if (resultCode != Activity.RESULT_OK || data == null) {
                fail("Screen-capture permission was not granted")
                return
            }

            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("MediaProjection unavailable")

            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopRecording()
                }
            }, Handler(Looper.getMainLooper()))

            val (uri, pfd) = createOutput()
            outputUri = uri
            outputPfd = pfd
            muxer = MuxerController(pfd)

            val metrics = resources.displayMetrics
            val width = metrics.widthPixels and 1.inv()
            val height = metrics.heightPixels and 1.inv()
            if (width < 320 || height < 320) throw IllegalStateException("Display is too small")

            running = true
            sendStatus("Starting")

            video = ScreenCaptureEncoder(this, projection!!, width, height, muxer!!)
            video!!.start()

            audio = AudioCaptureEncoder(projection!!, muxer!!) { state ->
                sendStatus(state)
            }
            val audioStarted = audio!!.start()
            if (!audioStarted) {
                // Do not throw away the screen recording if playback capture is unavailable.
                muxer!!.allowVideoOnly()
                sendStatus("Video recording continues; internal audio is unavailable")
            }
            sendStatus(if (audioStarted) "Recording screen + internal audio" else "Recording screen only")
            updateNotification("Recording in progress")
        } catch (e: Exception) {
            Log.e(TAG, "Recording start failed", e)
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun stopRecording() {
        if (!running) {
            stopSelf()
            return
        }
        running = false
        sendStatus("Finalizing")

        try { audio?.stop() } catch (e: Exception) { Log.w(TAG, "Audio stop", e) }
        try { video?.stop() } catch (e: Exception) { Log.w(TAG, "Video stop", e) }

        val uri = outputUri
        try {
            muxer?.close()
            if (uri != null) {
                contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }, null, null)
            }
            sendStatus("Completed", uri)
        } catch (e: Exception) {
            Log.e(TAG, "Finalize failed", e)
            if (uri != null) contentResolver.delete(uri, null, null)
            sendStatus("Error", error = e.message ?: "Finalize failed")
        } finally {
            audio = null
            video = null
            muxer = null
            outputPfd = null
            outputUri = null
            try { projection?.stop() } catch (_: Exception) {}
            projection = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        val uri = outputUri
        if (uri != null) contentResolver.delete(uri, null, null)
        try { muxer?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        running = false
        sendStatus("Error", error = message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createOutput(): Pair<Uri, ParcelFileDescriptor> {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "ScreenRecording_$stamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/InternalScreenRecorder")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create output video")
        val pfd = contentResolver.openFileDescriptor(uri, "w")
            ?: throw IllegalStateException("Could not open output video")
        return uri to pfd
    }

    private fun startForegroundCompat() {
        val notification = buildNotification("Preparing recording")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Internal Screen Recorder")
            .setContentText(text)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun sendStatus(status: String, uri: Uri? = null, error: String? = null) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).apply {
            putExtra(EXTRA_STATUS, status)
            uri?.let { putExtra(EXTRA_URI, it.toString()) }
            error?.let { putExtra(EXTRA_ERROR, it) }
        })
    }

    override fun onBind(intent: Intent?) = null
}
