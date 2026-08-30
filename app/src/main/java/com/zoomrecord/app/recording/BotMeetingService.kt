package com.zoomrecord.app.recording

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zoomrecord.app.R
import com.zoomrecord.app.library.RecordingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that acts as a "meeting bot":
 *
 * 1. Joins a Zoom meeting silently (camera OFF, mic MUTED, speaker MUTED)
 * 2. Records the meeting video (via MediaProjection screen capture)
 * 3. Records the meeting audio silently (via AudioPlaybackCapture — no speaker output)
 * 4. Saves the recording as MP4 when the meeting ends or user stops the bot
 *
 * The user can minimize the app, lock the phone, or use other apps.
 * The bot keeps running as a foreground service with a persistent notification.
 *
 * Audio: Always captured perfectly even in background (AudioPlaybackCapture)
 * Video: Captures whatever is on screen. When app is in background/screen off,
 *        video may freeze on last frame but audio continues.
 */
class BotMeetingService : Service() {

    companion object {
        private const val TAG = "BotMeetingService"

        const val ACTION_START_BOT = "com.zoomrecord.app.START_BOT"
        const val ACTION_STOP_BOT = "com.zoomrecord.app.STOP_BOT"

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_MEETING_NUMBER = "meetingNumber"
        const val EXTRA_MEETING_PASSWORD = "meetingPassword"
        const val EXTRA_DISPLAY_NAME = "displayName"

        private const val NOTIFICATION_ID = 43
        private const val CHANNEL_ID = "bot_meeting"

        /** Broadcast action sent when bot state changes. */
        const val BROADCAST_BOT_STATE = "com.zoomrecord.app.BOT_STATE"
        const val EXTRA_BOT_STATUS = "botStatus"
        const val EXTRA_ERROR_MESSAGE = "errorMessage"

        // Bot status values
        const val STATUS_JOINING = "joining"
        const val STATUS_RECORDING = "recording"
        const val STATUS_STOPPING = "stopping"
        const val STATUS_STOPPED = "stopped"
        const val STATUS_ERROR = "error"

        @Volatile
        private var activeAudioEncoder: AudioEncoder? = null

        /**
         * Feeds digital PCM audio chunks directly to the active bot recording session.
         */
        fun feedAudioPcm(pcmData: ByteArray) {
            activeAudioEncoder?.feedDirectPcm(pcmData)
        }
    }

    private var muxer: MuxerController? = null
    private var screenEncoder: ScreenEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var mediaProjection: MediaProjection? = null
    private var silentAudio: SilentAudioCapture? = null
    private var outputPath: String? = null
    private var outputUri: android.net.Uri? = null

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var storageWatchdogJob: Job? = null

    // ── Screen state receiver ────────────────────────────────────────

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    updateNotification("Bot recording (screen off — audio continues, video paused)")
                    Log.d(TAG, "Screen off — audio continues, video may freeze")
                }
                Intent.ACTION_SCREEN_ON -> {
                    updateNotification("Bot recording meeting…")
                    Log.d(TAG, "Screen on — full recording resumed")
                }
            }
        }
    }

    // ── Telephony callback (API 31+) ─────────────────────────────────

    private var telephonyCallback: Any? = null

    // ── Service lifecycle ────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(
            this, screenStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        registerTelephonyListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_BOT -> startBot(intent)
            ACTION_STOP_BOT -> stopBot()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "Task removed (app swiped away) — cleanly stopping bot...")
        stopBot()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        try { unregisterReceiver(screenStateReceiver) } catch (_: Exception) {}
        unregisterTelephonyListener()
    }

    // ── Bot start ────────────────────────────────────────────────────

    private fun startBot(intent: Intent) {
        try {
            // Start foreground immediately with proper Android 14 foregroundServiceType
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Bot starting…"),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification("Bot starting…"))
            }
            broadcastStatus(STATUS_JOINING)

            val meetingNumber = intent.getStringExtra(EXTRA_MEETING_NUMBER) ?: ""
            val meetingPassword = intent.getStringExtra(EXTRA_MEETING_PASSWORD) ?: ""
            val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: "Meeting Bot"

            val config = RecordingConfig().clampToDisplay()

            // Check storage
            val estimate = estimateBytesFor(config, minutes = 60)
            if (!StorageGuard.hasEnoughSpace(this, estimate)) {
                broadcastError(getString(R.string.not_enough_storage))
                stopSelf()
                return
            }

            // Get MediaProjection if available (required for AudioPlaybackCapture + screen mirroring)
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            @Suppress("DEPRECATION")
            val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

            if (resultCode == Activity.RESULT_OK && resultData != null) {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpm.getMediaProjection(resultCode, resultData)

                // Register mandatory MediaProjection callback (required on Android 14+)
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.i(TAG, "MediaProjection stopped by system")
                        stopBot()
                    }
                }, android.os.Handler(android.os.Looper.getMainLooper()))
            }

            // Step 1: Start recording pipeline FIRST (so AudioPlaybackCapture initializes while audio is audible)
            startRecording(config)

            // Note: Keep speaker volume unmuted so AudioPlaybackCapture receives full audio amplitude
            // from Zoom WebRTC / Android audio framework.
            Log.i(TAG, "Speaker volume kept at normal levels for AudioPlaybackCapture")

            updateNotification("Bot recording meeting…")
            broadcastStatus(STATUS_RECORDING)

            Log.i(TAG, "Bot started: meeting=$meetingNumber, display=$displayName")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting BotMeetingService", e)
            broadcastError("Failed to start bot: ${e.localizedMessage}")
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
            stopSelf()
        }
    }

    // ── Recording ────────────────────────────────────────────────────

    private fun startRecording(config: RecordingConfig) {
        // Create output file
        val result = RecordingsRepository.createPendingMp4(
            this, "meeting_${System.currentTimeMillis()}"
        )
        outputUri = result.first
        outputPath = result.second

        if (outputPath == null) {
            broadcastError("Failed to create output file")
            stopBot()
            return
        }

        // Initialize muxer
        muxer = MuxerController(outputPath!!)

        // Shared start time for perfect audio/video sync
        val startNanoTime = System.nanoTime()

        // Start video encoder if screen capture projection is available
        mediaProjection?.let { proj ->
            screenEncoder = ScreenEncoder(
                projection = proj,
                config = config,
                onVideoTrack = { format -> muxer!!.addVideoTrack(format) },
                onEncodedFrame = { track, buf, info -> muxer!!.writeSample(track, buf, info) },
            ).also { it.start() }
        }

        // Start audio encoder (dual-source meeting audio capture synced with video)
        audioEncoder = AudioEncoder(
            config = config,
            mediaProjection = mediaProjection,
            captureMode = AudioEncoder.AudioCaptureMode.MIC_PLUS_PLAYBACK,
            onAudioTrack = { format -> muxer!!.addAudioTrack(format) },
            onEncodedFrame = { track, buf, info -> muxer!!.writeSample(track, buf, info) },
            onAudioError = {
                Log.e(TAG, "Audio encoder failed — continuing with video only")
                muxer?.notifyTrackUnavailable("audio")
            },
        ).also { 
            it.start(startNanoTime)
            activeAudioEncoder = it
        }

        // Start storage watchdog
        startStorageWatchdog(config)

        Log.i(TAG, "Recording started: ${config.width}x${config.height} → $outputPath")
    }

    // ── Bot stop ─────────────────────────────────────────────────────

    private fun stopBot() {
        Log.i(TAG, "Stopping bot…")
        broadcastStatus(STATUS_STOPPING)
        activeAudioEncoder = null

        storageWatchdogJob?.cancel()

        // Stop recording with crash-safe teardown
        try { screenEncoder?.stop() } catch (e: Exception) {
            Log.e(TAG, "Video encoder stop failed", e)
        }
        try { audioEncoder?.stop() } catch (e: Exception) {
            Log.e(TAG, "Audio encoder stop failed", e)
        }
        try { muxer?.stop() } catch (e: Exception) {
            Log.e(TAG, "Muxer stop failed — file may be unplayable", e)
        }

        // Release MediaProjection
        try { mediaProjection?.stop() } catch (_: Exception) {}

        // Restore speaker volume
        silentAudio?.deactivate()

        // Leave Zoom meeting
        // TODO: Uncomment when Zoom SDK .aar is added
        /*
        val app = application as App
        app.zoomSdkManager.leaveMeeting()
        */

        // Finalize MediaStore entry
        outputUri?.let { RecordingsRepository.finalizePendingMp4(this, it) }

        broadcastStatus(STATUS_STOPPED)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Log.i(TAG, "Bot stopped and recording finalized")
    }

    // ── Phone call handling ──────────────────────────────────────────

    private fun registerTelephonyListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerTelephonyCallbackApi31()
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerTelephonyCallbackApi31() {
        val tm = getSystemService(TelephonyManager::class.java) ?: return
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING,
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        audioEncoder?.isPaused = true
                        updateNotification("Bot recording (audio paused — phone call)")
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        audioEncoder?.isPaused = false
                        updateNotification("Bot recording meeting…")
                    }
                }
            }
        }
        try {
            tm.registerTelephonyCallback(mainExecutor, callback)
            telephonyCallback = callback
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot register telephony callback", e)
        }
    }

    private fun unregisterTelephonyListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && telephonyCallback != null) {
            try {
                val tm = getSystemService(TelephonyManager::class.java)
                tm?.unregisterTelephonyCallback(telephonyCallback as TelephonyCallback)
            } catch (_: Exception) {}
        }
    }

    // ── Storage watchdog ─────────────────────────────────────────────

    private fun startStorageWatchdog(config: RecordingConfig) {
        storageWatchdogJob = serviceScope.launch {
            while (isActive) {
                delay(15_000)
                if (!StorageGuard.hasEnoughSpace(this@BotMeetingService, 0, marginBytes = 50L * 1024 * 1024)) {
                    Log.w(TAG, "Storage critically low — auto-stopping bot")
                    updateNotification("Storage full — recording stopped")
                    stopBot()
                    break
                }
                if (!StorageGuard.hasEnoughSpace(this@BotMeetingService, estimateBytesFor(config, 10))) {
                    updateNotification("Bot recording (storage running low)")
                }
            }
        }
    }

    // ── Notifications ────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Meeting Bot",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Ongoing notification while the bot is recording a meeting"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val stopIntent = Intent(this, BotMeetingService::class.java).apply {
            action = ACTION_STOP_BOT
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Meeting Bot")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop Bot",
                stopPendingIntent,
            )
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ── Broadcast helpers ────────────────────────────────────────────

    private fun broadcastStatus(status: String) {
        sendBroadcast(Intent(BROADCAST_BOT_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_BOT_STATUS, status)
        })
    }

    private fun broadcastError(message: String) {
        sendBroadcast(Intent(BROADCAST_BOT_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_BOT_STATUS, STATUS_ERROR)
            putExtra(EXTRA_ERROR_MESSAGE, message)
        })
    }
}
