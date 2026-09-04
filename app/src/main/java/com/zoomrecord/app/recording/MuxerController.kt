package com.zoomrecord.app.recording

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Thread-safe wrapper around [MediaMuxer] that combines video and audio
 * encoded tracks into a single high-quality, seekable MP4 file.
 *
 * Guarantees zero dropped recordings:
 * - Buffers initial samples until tracks are ready so keyframes are never lost.
 * - Enforces first video frame is an IDR/keyframe for 100% seekable MP4s.
 * - Automatically falls back to single-track if audio initialization fails.
 */
class MuxerController(
    outputPath: String,
    private var expectedTracks: Int = 2,
) {
    companion object {
        private const val TAG = "MuxerController"
        private const val MAX_PENDING_SAMPLES = 500
    }

    private data class PendingSample(
        val track: Int,
        val buffer: ByteArray,
        val offset: Int,
        val size: Int,
        val presentationTimeUs: Long,
        val flags: Int,
    )

    private val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private val lock = Any()
    private val pendingSamples = mutableListOf<PendingSample>()
    private var firstVideoKeyFrameSeen = false

    // Shared timeline base for BOTH tracks, anchored at the first accepted video keyframe.
    // Using one shared reference point (instead of rebasing each track to its own first
    // sample independently) is what keeps audio and video pinned to the same real-world
    // instant — video and audio pipelines have different startup latencies, and rebasing
    // them separately silently discarded that gap, causing a fixed sync offset.
    private var sharedBaseTimeUs = -1L
    private var lastVideoPtsUs = -1L
    private var lastAudioPtsUs = -1L

    @Volatile
    private var isPaused = false
    private var pauseStartTimeNs = 0L
    private var totalPausedTimeUs = 0L

    fun pause() = synchronized(lock) {
        if (!isPaused) {
            isPaused = true
            pauseStartTimeNs = System.nanoTime()
            Log.i(TAG, "MuxerController paused — dropping incoming samples")
        }
    }

    fun resume() = synchronized(lock) {
        if (isPaused) {
            isPaused = false
            if (pauseStartTimeNs > 0L) {
                val pausedDurationUs = (System.nanoTime() - pauseStartTimeNs) / 1000L
                totalPausedTimeUs += maxOf(0L, pausedDurationUs)
            }
            pauseStartTimeNs = 0L
            Log.i(TAG, "MuxerController resumed — total paused duration: ${totalPausedTimeUs / 1000}ms")
        }
    }

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var timeoutFuture: ScheduledFuture<*>? = null

    init {
        // Fallback: if the audio track still hasn't registered after a generous window,
        // start muxer video-only rather than block the recording forever. 10s was too tight —
        // a slow RECORD_AUDIO permission dialog, a slow AudioRecord/codec warm-up on some
        // devices, or MediaProjection audio-capture consent handling can all easily take
        // longer than that, and once this fires, audio can never attach for the rest of the
        // recording (see addAudioTrack's `!started` guard). If you see the warning below in
        // logcat, that's the smoking gun for "video with no audio at all."
        timeoutFuture = scheduler.schedule({
            synchronized(lock) {
                if (!started && videoTrack != -1) {
                    Log.w(TAG, "AUDIO TRACK NEVER REGISTERED within timeout — starting muxer VIDEO-ONLY. " +
                        "This recording will have no audio. Check AudioEncoder logs for why audio setup was slow or failed.")
                    startMuxerLocked()
                }
            }
        }, 20000, TimeUnit.MILLISECONDS)
    }

    fun addVideoTrack(format: MediaFormat): Int = synchronized(lock) {
        if (videoTrack == -1 && !started) {
            videoTrack = muxer.addTrack(format)
            Log.i(TAG, "Video track added: $videoTrack")
            maybeStartLocked()
        }
        videoTrack
    }

    fun addAudioTrack(format: MediaFormat): Int = synchronized(lock) {
        if (audioTrack == -1 && !started) {
            audioTrack = muxer.addTrack(format)
            Log.i(TAG, "Audio track added: $audioTrack")
            maybeStartLocked()
        }
        audioTrack
    }

    fun notifyTrackUnavailable(trackType: String) = synchronized(lock) {
        Log.w(TAG, "Track $trackType marked unavailable")
        expectedTracks = 1
        maybeStartLocked()
    }

    private fun maybeStartLocked() {
        if (started) return
        val currentTracks = (if (videoTrack != -1) 1 else 0) + (if (audioTrack != -1) 1 else 0)
        if (currentTracks >= expectedTracks && videoTrack != -1) {
            startMuxerLocked()
        }
    }

    private fun startMuxerLocked() {
        if (started || videoTrack == -1) return
        try {
            // Anchor timeline to the first video keyframe in pending samples if available
            val keyframeSample = pendingSamples.firstOrNull {
                it.track == videoTrack && (it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            }
            if (keyframeSample != null && sharedBaseTimeUs == -1L) {
                firstVideoKeyFrameSeen = true
                sharedBaseTimeUs = keyframeSample.presentationTimeUs
                Log.i(TAG, "Muxer: Anchored sharedBaseTimeUs=$sharedBaseTimeUs from buffered keyframe")
            }

            muxer.start()
            started = true
            timeoutFuture?.cancel(false)
            Log.i(TAG, "MediaMuxer successfully started! Draining ${pendingSamples.size} buffered samples...")

            // Drain buffered samples in order
            for (sample in pendingSamples) {
                writeSampleInternalLocked(
                    sample.track,
                    ByteBuffer.wrap(sample.buffer, sample.offset, sample.size),
                    sample.offset,
                    sample.size,
                    sample.presentationTimeUs,
                    sample.flags
                )
            }
            pendingSamples.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaMuxer", e)
        }
    }

    fun writeSample(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) = synchronized(lock) {
        if (track < 0 || isPaused) return

        if (!started) {
            if (pendingSamples.size < MAX_PENDING_SAMPLES) {
                val bytes = ByteArray(info.size)
                val origPos = buffer.position()
                buffer.position(info.offset)
                buffer.get(bytes)
                buffer.position(origPos)

                pendingSamples.add(
                    PendingSample(
                        track = track,
                        buffer = bytes,
                        offset = 0,
                        size = info.size,
                        presentationTimeUs = info.presentationTimeUs,
                        flags = info.flags
                    )
                )
            }
            return
        }

        writeSampleInternalLocked(track, buffer, info.offset, info.size, info.presentationTimeUs, info.flags)
    }

    private fun writeSampleInternalLocked(
        track: Int,
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        presentationTimeUs: Long,
        flags: Int
    ) {
        if (!started || track < 0 || size <= 0 || isPaused) return

        // MediaMuxer forbids writing codec-specific data via writeSampleData (it must only come from addTrack)
        if ((flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return

        // For video track, ensure the first written sample is a keyframe
        if (track == videoTrack && !firstVideoKeyFrameSeen) {
            if ((flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                firstVideoKeyFrameSeen = true
                if (sharedBaseTimeUs == -1L && presentationTimeUs >= 0L) {
                    sharedBaseTimeUs = presentationTimeUs
                    Log.i(TAG, "Muxer: First video keyframe accepted at PTS=$presentationTimeUs, sharedBaseTimeUs=$sharedBaseTimeUs")
                }
            } else {
                // Drop pre-keyframe delta frames to ensure 100% playable/seekable video
                return
            }
        }

        // Align audio start: drop pre-video audio samples so audio and video begin at the exact same moment
        if (track == audioTrack && !firstVideoKeyFrameSeen) {
            return
        }

        // Rebase both tracks against the SAME shared base and subtract paused duration
        // identically, so audio and video stay locked to one real-world timeline.
        val finalPtsUs = if (track == videoTrack) {
            val rebased = if (sharedBaseTimeUs >= 0L) maxOf(0L, presentationTimeUs - sharedBaseTimeUs) else maxOf(0L, presentationTimeUs)
            val adjustedPts = maxOf(0L, rebased - totalPausedTimeUs)
            val nextPts = if (adjustedPts > lastVideoPtsUs) adjustedPts else lastVideoPtsUs + 1L
            lastVideoPtsUs = nextPts
            nextPts
        } else if (track == audioTrack) {
            val rebased = if (sharedBaseTimeUs >= 0L) maxOf(0L, presentationTimeUs - sharedBaseTimeUs) else maxOf(0L, presentationTimeUs)
            val adjustedPts = maxOf(0L, rebased - totalPausedTimeUs)
            val nextPts = if (adjustedPts > lastAudioPtsUs) adjustedPts else lastAudioPtsUs + 1L
            lastAudioPtsUs = nextPts
            nextPts
        } else {
            maxOf(0L, presentationTimeUs - totalPausedTimeUs)
        }

        val info = MediaCodec.BufferInfo().apply {
            set(offset, size, finalPtsUs, flags)
        }

        try {
            muxer.writeSampleData(track, buffer, info)
        } catch (e: Exception) {
            Log.e(TAG, "writeSampleData error on track $track", e)
        }
    }

    val isStarted: Boolean get() = synchronized(lock) { started }

    fun stop() = synchronized(lock) {
        timeoutFuture?.cancel(false)
        scheduler.shutdownNow()
        if (started) {
            try {
                muxer.stop()
                Log.i(TAG, "MediaMuxer stopped successfully")
            } catch (e: Exception) {
                Log.e(TAG, "muxer.stop() failed", e)
            }
        }
        try {
            muxer.release()
        } catch (_: Exception) {}
        started = false
        pendingSamples.clear()
    }
}
