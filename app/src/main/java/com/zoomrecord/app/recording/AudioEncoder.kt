package com.zoomrecord.app.recording

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Audio encoder for meeting recording.
 *
 * IMPORTANT: start() is called directly from ScreenRecordService.startRecording(), which runs
 * on the MAIN thread (invoked from onStartCommand). Nothing in start() may block for more than
 * a few milliseconds, or it risks an ANR. All potentially slow work (waiting for the codec's
 * first output, reading AudioRecord, mixing) happens on background threads spawned here; start()
 * itself only configures the codec, kicks off AudioRecord sources, and returns.
 *
 * Two audio sources are captured and mixed:
 *  1. Hardware microphone (AudioRecord, with cascading AudioSource fallbacks) - this is what
 *     actually picks up meeting audio in practice, because of (2) below.
 *  2. Internal audio playback capture (AudioPlaybackCaptureConfiguration, Android 10+) - grabs
 *     audio from apps tagged USAGE_MEDIA/GAME/UNKNOWN. IMPORTANT PLATFORM LIMITATION: Android
 *     deliberately EXCLUDES USAGE_VOICE_COMMUNICATION from this capture path, and that is the
 *     usage tag most meeting apps (Zoom, Meet, Teams) use for live call audio. This is
 *     intentional OS policy, not a bug - there is no code-level way around it. In practice this
 *     means source (1), with the phone's speaker forced on elsewhere in the app so the mic can
 *     pick up meeting audio acoustically, is the source that actually matters; AEC/NS/AGC are
 *     attached to the mic session below specifically to make that path usable.
 *
 * Sync design: every audio sample is timestamped with the same absolute wall clock
 * (System.nanoTime(), CLOCK_MONOTONIC) that the video pipeline's Surface-driven frames use.
 * MuxerController rebases BOTH tracks against one shared reference point (the video's first
 * keyframe), so this file must NOT zero its own timestamps or derive them from a sample
 * counter - either of those breaks that shared rebase and desyncs audio from video.
 *
 * Track registration: handled asynchronously by the single drain thread below, exactly like the
 * video pipeline's own drain thread - the first INFO_OUTPUT_FORMAT_CHANGED event registers the
 * track with the muxer. MuxerController has a 20s safety-net timeout that starts the recording
 * video-only if audio never registers in that window (see MuxerController.kt); if you ever see
 * "AUDIO TRACK NEVER REGISTERED within timeout" in logcat, that timeout is what fired, and the
 * logs above it will show why audio setup was slow or failed.
 */
class AudioEncoder(
    private val config: RecordingConfig,
    private val mediaProjection: MediaProjection?,
    private val captureMode: AudioCaptureMode = AudioCaptureMode.MIC_PLUS_PLAYBACK,
    private val audioBoostEnabled: Boolean = true,
    private val onAudioTrack: (MediaFormat) -> Int,
    private val onEncodedFrame: (Int, ByteBuffer, MediaCodec.BufferInfo) -> Unit,
    private val onAudioError: (() -> Unit)? = null,
) {
    companion object {
        private const val TAG = "AudioEncoder"
        private const val BUFFER_SIZE = 4096 // 2048 samples = ~42.6ms at 48kHz mono/16-bit
    }

    enum class AudioCaptureMode {
        MIC_ONLY,
        PLAYBACK_ONLY,
        MIC_PLUS_PLAYBACK,
    }

    private lateinit var codec: MediaCodec
    private var micRecord: AudioRecord? = null
    private var playbackRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null

    @Volatile private var running = true

    @Volatile
    var isPaused = false

    private var trackIndex = -1
    private var startNanoTime = 0L
    private var lastQueuedPtsUs = -1L

    private val directPcmQueue = LinkedBlockingQueue<ByteArray>(200)
    private val micPcmQueue = LinkedBlockingQueue<ByteArray>(100)
    private val playbackPcmQueue = LinkedBlockingQueue<ByteArray>(100)

    fun pause() {
        isPaused = true
        directPcmQueue.clear()
        micPcmQueue.clear()
        playbackPcmQueue.clear()
        Log.i(TAG, "AudioEncoder paused")
    }

    fun resume() {
        isPaused = false
        Log.i(TAG, "AudioEncoder resumed")
    }

    /** Feeds digital PCM audio chunks directly into the encoder (e.g. from an external bridge). */
    fun feedDirectPcm(pcmData: ByteArray) {
        if (!running || isPaused || pcmData.isEmpty()) return
        if (!directPcmQueue.offer(pcmData)) {
            directPcmQueue.poll()
            directPcmQueue.offer(pcmData)
        }
    }

    /**
     * Configures the AAC encoder, primes it so the format-change event fires almost immediately,
     * starts the AudioRecord sources, and launches the reader/drain background threads. Returns
     * quickly - does not block waiting for track registration (see class doc for why that
     * matters: this is called on the main thread).
     */
    fun start(startTimeNs: Long = System.nanoTime()) {
        this.startNanoTime = startTimeNs

        try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, config.audioSampleRate, 1
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, config.audioBitrate)
            }

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            // Priming buffer (1024 bytes = 512 silent samples). Forces the codec to emit
            // INFO_OUTPUT_FORMAT_CHANGED almost immediately on the drain thread below,
            // guaranteeing the audio track registers with the muxer within a few ms/frames
            // rather than waiting on real mic/playback data to start flowing.
            try {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inBuf = codec.getInputBuffer(inIndex)
                    if (inBuf != null) {
                        inBuf.clear()
                        inBuf.put(ByteArray(1024))
                        codec.queueInputBuffer(inIndex, 0, 1024, 0L, 0)
                        lastQueuedPtsUs = 0L
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Priming buffer notice", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "AUDIO ENCODER INIT FAILED: could not create/configure/start the AAC codec - " +
                "no audio will be recorded: ${e.message}", e)
            running = false
            onAudioError?.invoke()
            return
        }

        setUpAudioSources()

        if (micRecord == null && playbackRecord == null) {
            Log.e(TAG, "NEITHER mic NOR playback capture could be initialized - the audio track will " +
                "still register (from the priming buffer) but will contain only silence for the entire " +
                "recording. Check RECORD_AUDIO permission and MediaProjection audio-capture consent.")
        }

        startReaderThreads()

        Thread({ drainLoop() }, "audio-drain").apply {
            priority = Thread.NORM_PRIORITY + 1
            setUncaughtExceptionHandler { _, e -> Log.e(TAG, "Audio drain thread crashed", e) }
            start()
        }

        Thread({ readLoop() }, "audio-read").apply {
            priority = Thread.NORM_PRIORITY + 1
            setUncaughtExceptionHandler { _, e -> Log.e(TAG, "Audio read thread crashed", e) }
            start()
        }

        Log.i(TAG, "AudioEncoder started: sampleRate=${config.audioSampleRate}, " +
            "bitrate=${config.audioBitrate}, boost=$audioBoostEnabled")
    }

    private fun setUpAudioSources() {
        val minBuf = AudioRecord.getMinBufferSize(
            config.audioSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = if (minBuf > 0) minBuf * 4 else 16384
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(config.audioSampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        if (captureMode != AudioCaptureMode.PLAYBACK_ONLY) {
            micRecord = buildMicRecord(audioFormat, bufSize)
            try {
                if (micRecord?.state == AudioRecord.STATE_INITIALIZED) {
                    micRecord?.startRecording()
                    Log.i(TAG, "Mic AudioRecord started recording successfully")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Mic start recording error", e)
            }
        }

        if (captureMode != AudioCaptureMode.MIC_ONLY) {
            playbackRecord = buildPlaybackRecord(audioFormat, bufSize)
            try {
                if (playbackRecord?.state == AudioRecord.STATE_INITIALIZED) {
                    playbackRecord?.startRecording()
                    Log.i(TAG, "Playback AudioRecord started recording successfully")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Playback start recording error", e)
            }
        }
    }

    private fun startReaderThreads() {
        if (micRecord != null) {
            Thread({
                val buf = ByteArray(BUFFER_SIZE)
                while (running) {
                    if (isPaused) { Thread.sleep(50); continue }
                    val rec = micRecord
                    if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                        try {
                            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) rec.startRecording()
                            val readBytes = rec.read(buf, 0, BUFFER_SIZE)
                            if (readBytes > 0) {
                                val chunk = buf.copyOf(readBytes)
                                if (!micPcmQueue.offer(chunk)) { micPcmQueue.poll(); micPcmQueue.offer(chunk) }
                            } else {
                                Thread.sleep(10)
                            }
                        } catch (_: Exception) {
                            Thread.sleep(20)
                        }
                    } else {
                        Thread.sleep(50)
                    }
                }
            }, "mic-rec-worker").apply { priority = Thread.NORM_PRIORITY + 1; start() }
        }

        if (playbackRecord != null) {
            Thread({
                val buf = ByteArray(BUFFER_SIZE)
                while (running) {
                    if (isPaused) { Thread.sleep(50); continue }
                    val rec = playbackRecord
                    if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                        try {
                            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) rec.startRecording()
                            val readBytes = rec.read(buf, 0, BUFFER_SIZE)
                            if (readBytes > 0) {
                                val chunk = buf.copyOf(readBytes)
                                if (!playbackPcmQueue.offer(chunk)) { playbackPcmQueue.poll(); playbackPcmQueue.offer(chunk) }
                            } else {
                                Thread.sleep(10)
                            }
                        } catch (_: Exception) {
                            Thread.sleep(20)
                        }
                    } else {
                        Thread.sleep(50)
                    }
                }
            }, "playback-rec-worker").apply { priority = Thread.NORM_PRIORITY + 1; start() }
        }
    }

    /** Builds the hardware microphone AudioRecord with cascading source fallbacks.
     *
     * Priority order is deliberate:
     * 1. CAMCORDER — rawest mic signal, minimal platform processing, best for picking up speaker audio
     * 2. UNPROCESSED — explicitly raw on API 24+, no AEC/NS/AGC applied by platform
     * 3. MIC — default mic, some devices apply light processing
     * 4. DEFAULT — system default
     * 5. VOICE_RECOGNITION — has platform AEC which could cancel our speaker audio
     * 6. VOICE_COMMUNICATION — may conflict with Zoom's active audio session
     * 7. VOICE_CALL / VOICE_DOWNLINK — last resort, restricted on most devices
     */
    @Suppress("MissingPermission")
    private fun buildMicRecord(audioFormat: AudioFormat, bufSize: Int): AudioRecord? {
        val sources = mutableListOf<Int>().apply {
            add(MediaRecorder.AudioSource.CAMCORDER)
            add(MediaRecorder.AudioSource.MIC)
            add(MediaRecorder.AudioSource.DEFAULT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) add(MediaRecorder.AudioSource.UNPROCESSED)
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            add(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        }

        for (src in sources) {
            try {
                val record = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        AudioRecord.Builder()
                            .setAudioFormat(audioFormat)
                            .setBufferSizeInBytes(bufSize)
                            .setAudioSource(src)
                            .build()
                    } else {
                        AudioRecord(src, config.audioSampleRate, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, bufSize)
                    }
                } catch (_: Exception) {
                    AudioRecord(src, config.audioSampleRate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufSize)
                }

                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    Log.i(TAG, "Hardware microphone AudioRecord initialized with audio source=$src")
                    attachAudioEffects(record.audioSessionId)
                    return record
                } else {
                    record.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "AudioSource $src failed, trying next fallback: ${e.message}")
            }
        }
        Log.e(TAG, "Failed to initialize any hardware microphone AudioRecord")
        return null
    }

    /**
     * Attaches audio processing effects to the mic's audio session.
     *
     * CRITICAL: AcousticEchoCanceler (AEC) is deliberately DISABLED here!
     * AEC's purpose is to REMOVE the loudspeaker's sound from mic input, treating
     * it as an "echo". But for meeting recording, the loudspeaker's sound IS the
     * audio we want to capture (the host/teacher's voice playing through the speaker).
     * Enabling AEC would actively silence/cancel the meeting audio from our recording.
     *
     * NoiseSuppressor is also DISABLED because it can aggressively filter the
     * speaker audio as "background noise", especially at lower volumes.
     *
     * AutomaticGainControl (AGC) IS enabled because it only amplifies quiet audio,
     * which helps capture faint speaker output more clearly.
     */
    private fun attachAudioEffects(sessionId: Int) {
        // ── AEC: DELIBERATELY DISABLED ──
        // DO NOT enable AcousticEchoCanceler — it cancels the speaker audio
        // we are trying to record through the microphone!
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = false }
                Log.i(TAG, "AcousticEchoCanceler DISABLED (would cancel speaker audio we want to record)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "AcousticEchoCanceler unavailable", e)
        }

        // ── NoiseSuppressor: DELIBERATELY DISABLED ──
        // Can aggressively filter speaker audio as "noise"
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = false }
                Log.i(TAG, "NoiseSuppressor DISABLED (could filter speaker audio as noise)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "NoiseSuppressor unavailable", e)
        }

        // ── AGC: ENABLED ── boosts quiet audio (helpful for faint speaker output)
        try {
            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
                Log.i(TAG, "AutomaticGainControl attached: ${gainControl != null}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "AutomaticGainControl unavailable", e)
        }
    }

    /**
     * Builds internal audio playback capture via MediaProjection (Android 10+). Deliberately
     * omits USAGE_VOICE_COMMUNICATION - Android disallows it and it would throw/silently drop.
     */
    @Suppress("MissingPermission")
    private fun buildPlaybackRecord(audioFormat: AudioFormat, bufSize: Int): AudioRecord? {
        if (mediaProjection == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val record = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufSize)
                .setAudioPlaybackCaptureConfig(playbackConfig)
                .build()

            if (record.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "Internal AudioPlaybackCapture AudioRecord initialized successfully")
                record
            } else {
                record.release()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioPlaybackCapture setup notice: ${e.message}")
            null
        }
    }

    /**
     * Continuously mixes mic + playback + direct PCM, applies optional speech boost, and feeds
     * the result to the codec at a steady ~40ms cadence, synthesizing silence when nothing is
     * available so the audio timeline never has an unaccounted gap.
     */
    private fun readLoop() {
        val pcmChunkBuffer = ByteArray(BUFFER_SIZE)
        val silenceBuffer = ByteArray(BUFFER_SIZE)

        fun queueBufferToCodec(data: ByteArray, length: Int, isEos: Boolean = false): Boolean {
            if (length <= 0 && !isEos) return true
            try {
                var retries = 0
                while (running && retries < 20) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex) ?: return false
                        inputBuffer.clear()
                        if (length > 0) inputBuffer.put(data, 0, length)

                        // Real wall-clock PTS in the SAME absolute clock domain as the video
                        // pipeline's Surface-driven timestamps. MuxerController subtracts one
                        // shared reference point (the first video keyframe's raw PTS) from both
                        // tracks - do not zero or otherwise offset this value here, or that
                        // shared rebase breaks and audio/video desync again.
                        val rawPtsUs = System.nanoTime() / 1000L
                        val ptsUs = if (rawPtsUs > lastQueuedPtsUs) rawPtsUs else lastQueuedPtsUs + 1L
                        lastQueuedPtsUs = ptsUs

                        val flags = if (isEos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        codec.queueInputBuffer(inIndex, 0, length, ptsUs, flags)
                        return true
                    }
                    retries++
                    Thread.sleep(5)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Queue to codec error", e)
            }
            return false
        }

        while (running) {
            if (isPaused) {
                directPcmQueue.clear(); micPcmQueue.clear(); playbackPcmQueue.clear()
                Thread.sleep(30)
                continue
            }

            val micChunk = micPcmQueue.poll(40, TimeUnit.MILLISECONDS)
            val playbackChunk = playbackPcmQueue.poll()
            val directChunk = directPcmQueue.poll()

            val activeChunk = mixAudioChunks(micChunk, playbackChunk, directChunk)

            if (activeChunk != null && activeChunk.isNotEmpty()) {
                var offset = 0
                while (offset < activeChunk.size && running) {
                    val copyLen = minOf(activeChunk.size - offset, BUFFER_SIZE)
                    val numSamples = copyLen / 2

                    for (s in 0 until numSamples) {
                        val inIdx = offset + s * 2
                        val outIdx = s * 2
                        val low = activeChunk[inIdx].toInt() and 0xFF
                        val high = activeChunk[inIdx + 1].toInt()
                        val rawSample: Int = (high shl 8) or low

                        val sampleToEncode: Int = if (audioBoostEnabled) {
                            val boosted = rawSample * 6.0f
                            val limited = when {
                                boosted > 26000f -> 26000f + (boosted - 26000f) * 0.15f
                                boosted < -26000f -> -26000f + (boosted + 26000f) * 0.15f
                                else -> boosted
                            }
                            limited.toInt().coerceIn(-32768, 32767)
                        } else {
                            rawSample
                        }

                        pcmChunkBuffer[outIdx] = (sampleToEncode and 0xFF).toByte()
                        pcmChunkBuffer[outIdx + 1] = ((sampleToEncode shr 8) and 0xFF).toByte()
                    }

                    if (queueBufferToCodec(pcmChunkBuffer, numSamples * 2)) {
                        offset += copyLen
                    } else {
                        Thread.sleep(5)
                    }
                }
            } else {
                // Nothing arrived in this ~40ms window - synthesize silence so the audio
                // timeline never has an unaccounted gap relative to the video timeline.
                queueBufferToCodec(silenceBuffer, BUFFER_SIZE)
            }
        }

        try { queueBufferToCodec(silenceBuffer, 0, isEos = true) } catch (_: Exception) {}
    }

    /** Blends microphone, playback, and direct PCM chunks with 16-bit PCM saturation clamping. */
    private fun mixAudioChunks(mic: ByteArray?, playback: ByteArray?, direct: ByteArray?): ByteArray? {
        val nonNulls = listOfNotNull(mic, playback, direct)
        if (nonNulls.isEmpty()) return null
        if (nonNulls.size == 1) return nonNulls[0]

        val maxLen = nonNulls.maxOf { it.size }
        val mixed = ByteArray(maxLen)
        val numSamples = maxLen / 2

        for (s in 0 until numSamples) {
            var sum = 0
            for (chunk in nonNulls) {
                val idx = s * 2
                if (idx + 1 < chunk.size) {
                    val low = chunk[idx].toInt() and 0xFF
                    val high = chunk[idx + 1].toInt()
                    sum += (high shl 8) or low
                }
            }
            val clamped = sum.coerceIn(-32768, 32767)
            mixed[s * 2] = (clamped and 0xFF).toByte()
            mixed[s * 2 + 1] = ((clamped shr 8) and 0xFF).toByte()
        }
        return mixed
    }

    /**
     * Drain loop: continuously dequeues encoded output buffers from the codec and forwards them
     * to the muxer. The FIRST call handles INFO_OUTPUT_FORMAT_CHANGED, registering the audio
     * track with the muxer - this is the only place track registration happens.
     */
    private fun drainLoop() {
        val bufferInfo = MediaCodec.BufferInfo()

        while (running) {
            val outIndex = try {
                codec.dequeueOutputBuffer(bufferInfo, 10_000)
            } catch (e: Exception) {
                Log.w(TAG, "dequeueOutputBuffer error", e)
                break
            }

            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = onAudioTrack(codec.outputFormat)
                    Log.i(TAG, "Audio track registered: index=$trackIndex")
                }

                outIndex >= 0 -> {
                    val buf = codec.getOutputBuffer(outIndex)
                    if (buf == null) {
                        try { codec.releaseOutputBuffer(outIndex, false) } catch (_: Exception) {}
                    } else {
                        if (trackIndex < 0) {
                            try { trackIndex = onAudioTrack(codec.outputFormat) } catch (_: Exception) {}
                        }
                        if (!isPaused && bufferInfo.size > 0 && trackIndex >= 0) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                buf.position(bufferInfo.offset)
                                buf.limit(bufferInfo.offset + bufferInfo.size)
                                onEncodedFrame(trackIndex, buf, bufferInfo)
                            }
                        }
                        try { codec.releaseOutputBuffer(outIndex, false) } catch (_: Exception) {}
                    }

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        Log.d(TAG, "Audio EOS received")
                        break
                    }
                }

                else -> { /* INFO_TRY_AGAIN_LATER / INFO_OUTPUT_BUFFERS_CHANGED - keep polling */ }
            }
        }

        Log.d(TAG, "Audio drain loop exiting")
    }

    /** Stops the encoder and releases all resources safely. */
    fun stop() {
        running = false

        try { echoCanceler?.release() } catch (_: Exception) {}
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        try { gainControl?.release() } catch (_: Exception) {}
        echoCanceler = null
        noiseSuppressor = null
        gainControl = null

        try { micRecord?.stop(); micRecord?.release() } catch (e: Exception) { Log.w(TAG, "micRecord stop failed", e) }
        micRecord = null

        try { playbackRecord?.stop(); playbackRecord?.release() } catch (e: Exception) { Log.w(TAG, "playbackRecord stop failed", e) }
        playbackRecord = null

        Thread.sleep(80)

        try {
            if (::codec.isInitialized) { codec.stop(); codec.release() }
        } catch (e: Exception) {
            Log.w(TAG, "Codec stop/release failed", e)
        }

        Log.i(TAG, "AudioEncoder stopped")
    }
}
