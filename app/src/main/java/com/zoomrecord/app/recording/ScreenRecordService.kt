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
import android.media.AudioManager
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
 * Foreground service that orchestrates the screen recording pipeline.
 *
 * Lifecycle:
 * 1. Activity requests screen capture permission → receives resultCode + resultData
 * 2. Activity starts this service with ACTION_START, passing the result
 * 3. Service creates MediaProjection, configures encoders, starts recording
 * 4. Service runs as foreground with a persistent notification
 * 5. ACTION_STOP (or notification action) stops recording and finalizes the MP4
 *
 * Reliability features:
 * - Screen lock detection (pauses notification text)
 * - Phone call pause (mutes audio encoder)
 * - Storage watchdog (auto-stops before running out of space)
 * - Crash-safe teardown (try/catch around every stop call)
 */
class ScreenRecordService : Service() {

    companion object {
        private const val TAG = "ScreenRecordService"
        const val ACTION_START = "com.zoomrecord.app.START_RECORD"
        const val ACTION_STOP = "com.zoomrecord.app.STOP_RECORD"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val ACTION_PAUSE = "com.zoomrecord.app.PAUSE_RECORD"
        const val ACTION_RESUME = "com.zoomrecord.app.RESUME_RECORD"
        const val EXTRA_IS_PAUSED = "isPaused"

        private const val NOTIFICATION_ID = 42
        private const val CHANNEL_ID = "screen_recording"

        /** Broadcast action sent when recording state changes or ticks. */
        const val BROADCAST_RECORDING_STATE = "com.zoomrecord.app.RECORDING_STATE"
        const val EXTRA_IS_RECORDING = "isRecording"
        const val EXTRA_ELAPSED_SECONDS = "elapsedSeconds"
        const val EXTRA_START_TIME_MS = "startTimeMs"
        const val EXTRA_OUTPUT_PATH = "outputPath"
        const val EXTRA_ERROR_MESSAGE = "errorMessage"
        const val EXTRA_SHOW_FLOATING_OVERLAY = "extra_show_floating_overlay"
        const val EXTRA_AUDIO_BOOST = "extra_audio_boost"
        const val EXTRA_SPEAKER_OUTPUT_ENABLED = "extra_speaker_output_enabled"
        const val EXTRA_SPEAKER_STATE = "speakerState"
        const val ACTION_TOGGLE_SPEAKER = "com.zoomrecord.app.action.TOGGLE_SPEAKER"
        const val ACTION_SET_SPEAKER = "com.zoomrecord.app.action.SET_SPEAKER"
        const val BROADCAST_SPEAKER_STATE = "com.zoomrecord.app.SPEAKER_STATE"

        /** Globally observable status for in-app UI checks */
        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isPaused: Boolean = false
            private set

        @Volatile
        var isSpeakerOutputActive: Boolean = false
            private set

        @Volatile
        var activeStartTimeMs: Long = 0L
            private set

        // Live reference to whichever ScreenRecordService instance is currently recording, so
        // in-process callers (like the WebView's WebRTC audio tap in MeetingActiveScreen) can
        // feed digital PCM straight into the running AudioEncoder. This is what makes the
        // JS-side "WebAudioBridge" tap actually reach the recording pipeline — without this,
        // sendAudioChunk() had nowhere to deliver the audio it was capturing.
        @Volatile
        private var activeInstance: ScreenRecordService? = null

        /**
         * Feeds a chunk of 16-bit PCM mono audio (matching [RecordingConfig.audioSampleRate])
         * directly into the currently-running recording session's audio encoder, bypassing the
         * OS-level AudioRecord/AudioPlaybackCapture path entirely. Safe to call even if no
         * recording is active — it's a no-op in that case.
         */
        fun feedDirectAudioPcm(pcm: ByteArray) {
            activeInstance?.audioEncoder?.feedDirectPcm(pcm)
        }
    }

    private var muxer: MuxerController? = null
    private var screenEncoder: ScreenEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var mediaProjection: MediaProjection? = null
    private var outputPath: String? = null
    private var outputUri: android.net.Uri? = null

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var isPausedForLock = false
    private var storageWatchdogJob: Job? = null
    private var tickerJob: Job? = null
    private var recordingStartTime = 0L
    private var totalPausedDurationMs = 0L
    private var pauseStartTimeMs = 0L
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var floatingOverlay: FloatingRecordingOverlay? = null

    // ── Screen state receiver ────────────────────────────────────────

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> handleScreenOff()
                Intent.ACTION_SCREEN_ON -> handleScreenOn()
            }
        }
    }

    // ── Telephony callback (API 31+) ─────────────────────────────────

    private var telephonyCallback: Any? = null // stored as Any to avoid API lint on < 31

    // ── Service lifecycle ────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        activeInstance = this

        // Register screen lock listener
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(
            this, screenStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Register phone call listener
        registerTelephonyListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> stopRecording()
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_TOGGLE_SPEAKER -> toggleSpeakerOutput()
            ACTION_SET_SPEAKER -> {
                val enabled = intent.getBooleanExtra(EXTRA_SPEAKER_STATE, false)
                setSpeakerOutput(enabled)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "Task removed (app swiped away) — cleanly stopping and finalizing recording...")
        if (isRunning) {
            stopRecording()
        }
    }

    override fun onDestroy() {
        if (isRunning) {
            Log.w(TAG, "onDestroy called while still recording — forcing clean stop")
            stopRecording()
        }
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
        serviceScope.cancel()
        try { unregisterReceiver(screenStateReceiver) } catch (_: Exception) {}
        unregisterTelephonyListener()
    }

    // ── Recording start ──────────────────────────────────────────────

    private fun startRecording(intent: Intent) {
        // Start as foreground immediately with proper Android 14 foregroundServiceType
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(getString(R.string.recording_in_progress)),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.recording_in_progress)))
        }

        val config = RecordingConfig().clampToDisplay()

        // Check storage before starting
        val estimate = estimateBytesFor(config, minutes = 60)
        if (!StorageGuard.hasEnoughSpace(this, estimate)) {
            broadcastError(getString(R.string.not_enough_storage))
            stopSelf()
            return
        }

        // Get MediaProjection from the activity result
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            broadcastError("Screen capture permission denied")
            stopSelf()
            return
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, resultData)

        if (mediaProjection == null) {
            broadcastError("Failed to create MediaProjection")
            stopSelf()
            return
        }

        // Register mandatory MediaProjection callback (required on Android 14+ prior to virtual display creation)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped by system")
                stopRecording()
            }
        }, android.os.Handler(android.os.Looper.getMainLooper()))

        // Create output file via MediaStore & local disk
        val result = RecordingsRepository.createPendingMp4(
            this, "zoom_meeting_${System.currentTimeMillis()}"
        )
        outputUri = result.first
        outputPath = result.second

        if (outputPath == null) {
            broadcastError("Failed to create output file")
            stopSelf()
            return
        }

        // Initialize muxer
        muxer = MuxerController(outputPath!!)

        // Shared start time for perfect audio/video sync
        val startNanoTime = System.nanoTime()

        val showOverlay = intent.getBooleanExtra(EXTRA_SHOW_FLOATING_OVERLAY, true)
        val audioBoost = intent.getBooleanExtra(EXTRA_AUDIO_BOOST, true)

        // Start audio encoder with dual capture:
        // 1. AudioPlaybackCapture captures internal media/games
        // 2. Hardware Microphone (CAMCORDER, AEC OFF) captures Zoom/VoIP call audio
        // Android blocks AudioPlaybackCapture for USAGE_VOICE_COMMUNICATION (Zoom/Meet calls),
        // so mixing mic + playback is the only way to record Zoom meeting audio reliably on Android.
        audioEncoder = AudioEncoder(
            config = config,
            mediaProjection = mediaProjection,
            captureMode = AudioEncoder.AudioCaptureMode.MIC_PLUS_PLAYBACK,
            audioBoostEnabled = true,
            onAudioTrack = { format -> muxer!!.addAudioTrack(format) },
            onEncodedFrame = { track, buf, info -> muxer!!.writeSample(track, buf, info) },
            onAudioError = { muxer?.notifyTrackUnavailable("audio") },
        ).also { it.start(startNanoTime) }

        // Start video encoder
        screenEncoder = ScreenEncoder(
            projection = mediaProjection!!,
            config = config,
            onVideoTrack = { format -> muxer!!.addVideoTrack(format) },
            onEncodedFrame = { track, buf, info -> muxer!!.writeSample(track, buf, info) },
        ).also { it.start() }

        // Start storage watchdog
        startStorageWatchdog(config)

        // Acquire WakeLock so recording and audio capture never sleep when screen is locked
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            wakeLock = pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "ZoomRecord::RecordWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L) // 4 hours
            }
        } catch (_: Exception) {}

        // Mark running and set start timestamp
        recordingStartTime = System.currentTimeMillis()
        activeStartTimeMs = recordingStartTime
        isRunning = true

        // Initialize speaker state: Default true (loudspeaker ON) so mic captures Zoom meeting audio
        val speakerEnabled = intent.getBooleanExtra(EXTRA_SPEAKER_OUTPUT_ENABLED, true)
        setSpeakerOutput(speakerEnabled)
        com.zoomrecord.app.zoom.ZoomBotAccessibilityService.ensureWatchdogRunning()

        // Show floating overlay controller over Zoom if enabled and permission granted
        if (showOverlay) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    floatingOverlay = FloatingRecordingOverlay(applicationContext) {
                        stopRecording()
                    }.apply {
                        show()
                        updateSpeakerState(isSpeakerOutputActive)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Notice: could not show floating overlay", e)
                }
            }
        }

        // Broadcast initial recording started
        broadcastState(isRecording = true, elapsedSeconds = 0, startTimeMs = recordingStartTime)

        // Periodic ticker broadcasting elapsed seconds to in-meeting UI and floating overlay
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            while (isActive && isRunning) {
                delay(1000)
                val now = if (isPaused && pauseStartTimeMs > 0L) pauseStartTimeMs else System.currentTimeMillis()
                val elapsed = maxOf(0, ((now - recordingStartTime - totalPausedDurationMs) / 1000).toInt())
                broadcastState(isRecording = true, isPausedState = isPaused, elapsedSeconds = elapsed, startTimeMs = recordingStartTime)
                floatingOverlay?.updateElapsedSeconds(elapsed, isPaused)
            }
        }

        Log.i(TAG, "Recording started: ${config.width}x${config.height} → $outputPath")
    }

    // ── Recording pause & resume ─────────────────────────────────────

    private fun pauseRecording() {
        if (!isRunning || isPaused) return
        Log.i(TAG, "Pausing screen recording…")
        isPaused = true
        pauseStartTimeMs = System.currentTimeMillis()

        try { screenEncoder?.pause() } catch (_: Exception) {}
        try { audioEncoder?.pause() } catch (_: Exception) {}
        try { muxer?.pause() } catch (_: Exception) {}

        updateNotification("Recording paused")
        floatingOverlay?.updatePausedState(true)

        val elapsed = maxOf(0, ((pauseStartTimeMs - recordingStartTime - totalPausedDurationMs) / 1000).toInt())
        broadcastState(isRecording = true, isPausedState = true, elapsedSeconds = elapsed, startTimeMs = recordingStartTime)
    }

    private fun resumeRecording() {
        if (!isRunning || !isPaused) return
        Log.i(TAG, "Resuming screen recording…")
        if (pauseStartTimeMs > 0L) {
            totalPausedDurationMs += (System.currentTimeMillis() - pauseStartTimeMs)
            pauseStartTimeMs = 0L
        }
        isPaused = false

        try { screenEncoder?.resume() } catch (_: Exception) {}
        try { audioEncoder?.resume() } catch (_: Exception) {}
        try { muxer?.resume() } catch (_: Exception) {}

        updateNotification(getString(R.string.recording_in_progress))
        floatingOverlay?.updatePausedState(false)

        val elapsed = maxOf(0, ((System.currentTimeMillis() - recordingStartTime - totalPausedDurationMs) / 1000).toInt())
        broadcastState(isRecording = true, isPausedState = false, elapsedSeconds = elapsed, startTimeMs = recordingStartTime)
    }

    // ── Recording stop ───────────────────────────────────────────────

    private fun stopRecording() {
        Log.i(TAG, "Stopping recording…")
        isRunning = false
        isPaused = false
        activeStartTimeMs = 0L
        pauseStartTimeMs = 0L
        totalPausedDurationMs = 0L
        tickerJob?.cancel()
        storageWatchdogJob?.cancel()

        // Restore volume if it was muted during recording
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null && !isSpeakerOutputActive && savedVolumes.isNotEmpty()) {
                for (stream in controlledStreams) {
                    val target = savedVolumes[stream] ?: continue
                    am.setStreamVolume(stream, target, 0)
                }
            }
        } catch (_: Exception) {}

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null

        // Stop encoders with crash-safe teardown
        try { screenEncoder?.stop() } catch (e: Exception) {
            Log.e(TAG, "Video encoder stop failed", e)
        }
        try { audioEncoder?.stop() } catch (e: Exception) {
            Log.e(TAG, "Audio encoder stop failed", e)
        }
        try { muxer?.stop() } catch (e: Exception) {
            Log.e(TAG, "Muxer stop failed", e)
        }
        try { mediaProjection?.stop() } catch (e: Exception) {
            Log.e(TAG, "MediaProjection stop failed", e)
        }

        // Finalize MediaStore entry or file repository entry
        if (outputPath != null) {
            val file = java.io.File(outputPath!!)
            if (file.exists() && file.length() > 0) {
                if (outputUri != null) {
                    RecordingsRepository.finalizePendingMp4(this, outputUri!!)
                } else {
                    RecordingsRepository.finalizePendingRecording(this, android.net.Uri.fromFile(file), "video/mp4")
                }
                // Automatically generate synchronized companion MP3 file matching meeting timings
                val path = outputPath!!
                Thread({
                    try {
                        AudioExtractor.extractAudioFromMp4(applicationContext, path)
                    } catch (e: Exception) {
                        Log.w(TAG, "Companion MP3 generation notice", e)
                    }
                }, "screen-mp3-extractor").start()
            }
        }

        // Restore normal audio routing on stop
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.mode = AudioManager.MODE_NORMAL
            am?.isSpeakerphoneOn = false
            val maxVol = am?.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) ?: 0
            if (maxVol > 0) {
                am?.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maxVol * 0.7).toInt(), 0)
            }
        } catch (_: Exception) {}

        // Dismiss floating overlay
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                floatingOverlay?.dismiss()
                floatingOverlay = null
            } catch (_: Exception) {}
        }

        // Request Zoom bot to automatically exit/leave the meeting
        try {
            com.zoomrecord.app.zoom.ZoomBotAccessibilityService.requestExitMeeting()
            val exitIntent = Intent(com.zoomrecord.app.zoom.ZoomBotAccessibilityService.ACTION_EXIT_ZOOM_MEETING).apply {
                setPackage(packageName)
            }
            sendBroadcast(exitIntent)
            Log.i(TAG, "Recording stopped: sent exit meeting request to Zoom bot")
        } catch (e: Exception) {
            Log.w(TAG, "Notice: could not request exit meeting", e)
        }

        // Notify UI that recording has ended
        broadcastState(isRecording = false, elapsedSeconds = 0, startTimeMs = 0L)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Log.i(TAG, "Recording stopped and finalized")
    }

    // ── Speaker Sound Output Control ──────────────────────────────────

    private fun toggleSpeakerOutput() {
        setSpeakerOutput(!isSpeakerOutputActive)
    }

    private fun setSpeakerOutput(enabled: Boolean) {
        isSpeakerOutputActive = enabled
        applySpeakerSetting(enabled)
        floatingOverlay?.updateSpeakerState(enabled)
        broadcastSpeakerState(enabled)
    }

    private val controlledStreams = intArrayOf(
        AudioManager.STREAM_VOICE_CALL,
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_RING,
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_SYSTEM
    )
    private val savedVolumes = mutableMapOf<Int, Int>()

    private fun applySpeakerSetting(enabled: Boolean) {
        isSpeakerOutputActive = enabled
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

            if (enabled) {
                // UNMUTE: Restore device volume so speaker audio plays and is recorded
                for (stream in controlledStreams) {
                    val max = am.getStreamMaxVolume(stream)
                    val targetVol = savedVolumes[stream]?.takeIf { it > 0 } ?: (max * 0.70).toInt().coerceAtLeast(1)
                    try {
                        am.setStreamVolume(stream, targetVol, 0)
                    } catch (_: Exception) {}
                }
                Log.i(TAG, "Device Volume UNMUTED: Speaker sound restored for recording")
            } else {
                // MUTE: Save current volume and silence device volume
                for (stream in controlledStreams) {
                    val cur = am.getStreamVolume(stream)
                    if (cur > 0) savedVolumes[stream] = cur
                    try {
                        am.setStreamVolume(stream, 0, 0)
                    } catch (_: Exception) {}
                }
                Log.i(TAG, "Device Volume MUTED: Speaker silenced")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply volume mute/unmute setting: ${e.message}")
        }
    }

    private fun broadcastSpeakerState(enabled: Boolean) {
        val intent = Intent(BROADCAST_SPEAKER_STATE).apply {
            putExtra(EXTRA_SPEAKER_STATE, enabled)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    // ── Screen lock handling ─────────────────────────────────────────

    private fun handleScreenOff() {
        Log.d(TAG, "Screen off / locked — background recording and audio capture active")
    }

    private fun handleScreenOn() {
        isPausedForLock = false
        updateNotification(getString(R.string.recording_in_progress))
        Log.d(TAG, "Screen on — recording resumed")
    }

    // ── Phone call handling ──────────────────────────────────────────

    private fun registerTelephonyListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerTelephonyCallbackApi31()
        }
        // For API < 31, PhoneStateListener is deprecated and requires
        // READ_PHONE_STATE which we have in the manifest
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
                        Log.d(TAG, "Phone call active — audio paused")
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        audioEncoder?.isPaused = false
                        Log.d(TAG, "Phone call ended — audio resumed")
                    }
                }
            }
        }
        try {
            tm.registerTelephonyCallback(mainExecutor, callback)
            telephonyCallback = callback
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot register telephony callback (missing permission?)", e)
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
                delay(15_000) // Check every 15 seconds

                // Critical threshold — stop immediately to save the file
                if (!StorageGuard.hasEnoughSpace(this@ScreenRecordService, 0, marginBytes = 50L * 1024 * 1024)) {
                    Log.w(TAG, "Storage critically low — auto-stopping recording")
                    updateNotification(getString(R.string.recording_stopped_low_storage))
                    stopRecording()
                    break
                }

                // Warning threshold
                if (!StorageGuard.hasEnoughSpace(this@ScreenRecordService, estimateBytesFor(config, 10))) {
                    updateNotification(getString(R.string.storage_low_warning))
                }
            }
        }
    }

    // ── Notifications ────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.recording_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Ongoing notification while recording the screen"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        // Stop action for the notification
        val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Pause / Resume action
        val toggleActionIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        }
        val togglePendingIntent = PendingIntent.getService(
            this, 1, toggleActionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val toggleIcon = if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        val toggleTitle = if (isPaused) "Resume" else "Pause"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                toggleIcon,
                toggleTitle,
                togglePendingIntent,
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.recording_notification_stop),
                stopPendingIntent,
            )
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ── Broadcast helpers ────────────────────────────────────────────

    private fun broadcastState(
        isRecording: Boolean,
        isPausedState: Boolean = isPaused,
        elapsedSeconds: Int = 0,
        startTimeMs: Long = 0L,
    ) {
        sendBroadcast(Intent(BROADCAST_RECORDING_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_IS_RECORDING, isRecording)
            putExtra(EXTRA_IS_PAUSED, isPausedState)
            putExtra(EXTRA_ELAPSED_SECONDS, elapsedSeconds)
            putExtra(EXTRA_START_TIME_MS, startTimeMs)
            outputPath?.let { putExtra(EXTRA_OUTPUT_PATH, it) }
        })
    }

    private fun broadcastError(message: String) {
        sendBroadcast(Intent(BROADCAST_RECORDING_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_IS_RECORDING, false)
            putExtra(EXTRA_ERROR_MESSAGE, message)
        })
    }
}
