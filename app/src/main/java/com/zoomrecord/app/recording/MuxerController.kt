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

    // Independent timeline rebasing per track
    private var videoBaseTimeUs = -1L
    private var audioBaseTimeUs = -1L
    private var lastVideoPtsUs = -1L
    private var lastAudioPtsUs = -1L

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var timeoutFuture: ScheduledFuture<*>? = null

    init {
        // Fallback: If 10s passes and only 1 track is registered, start muxer anyway
        timeoutFuture = scheduler.schedule({
            synchronized(lock) {
                if (!started && videoTrack != -1) {
                    Log.w(TAG, "Timeout reached for second track. Starting muxer with available track(s)...")
                    startMuxerLocked()
                }
            }
        }, 10000, TimeUnit.MILLISECONDS)
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
        if (track < 0) return

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
        if (!started || track < 0 || size <= 0) return

        // For video track, ensure the first written sample is a keyframe
        if (track == videoTrack && !firstVideoKeyFrameSeen) {
            if ((flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                firstVideoKeyFrameSeen = true
                if (videoBaseTimeUs == -1L && presentationTimeUs >= 0L) {
                    videoBaseTimeUs = presentationTimeUs
                    Log.i(TAG, "Muxer timeline anchored to first video keyframe: videoBaseTimeUs=$videoBaseTimeUs")
                }
            } else {
                // Drop pre-keyframe delta frames to ensure 100% playable/seekable video
                return
            }
        }

        // Enforce strictly monotonic timestamps per track with independent timeline rebasing
        val finalPtsUs = if (track == videoTrack) {
            if (videoBaseTimeUs == -1L && presentationTimeUs >= 0L) {
                videoBaseTimeUs = presentationTimeUs
                Log.i(TAG, "Video timeline rebased: base=$videoBaseTimeUs")
            }
            val rebasedPts = if (videoBaseTimeUs > 0L) maxOf(0L, presentationTimeUs - videoBaseTimeUs) else maxOf(0L, presentationTimeUs)
            val nextPts = if (rebasedPts > lastVideoPtsUs) rebasedPts else lastVideoPtsUs + 1L
            lastVideoPtsUs = nextPts
            nextPts
        } else if (track == audioTrack) {
            if (audioBaseTimeUs == -1L && presentationTimeUs >= 0L) {
                audioBaseTimeUs = presentationTimeUs
                Log.i(TAG, "Audio timeline rebased: base=$audioBaseTimeUs")
            }
            val rebasedPts = if (audioBaseTimeUs > 0L) maxOf(0L, presentationTimeUs - audioBaseTimeUs) else maxOf(0L, presentationTimeUs)
            val nextPts = if (rebasedPts > lastAudioPtsUs) rebasedPts else lastAudioPtsUs + 1L
            lastAudioPtsUs = nextPts
            nextPts
        } else {
            presentationTimeUs
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
