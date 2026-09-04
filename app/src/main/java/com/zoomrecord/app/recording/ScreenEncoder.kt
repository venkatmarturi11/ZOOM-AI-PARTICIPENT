package com.zoomrecord.app.recording

import android.content.res.Resources
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log
import java.nio.ByteBuffer

/**
 * Captures the device screen via [MediaProjection] and encodes it to H.264
 * using [MediaCodec] with a Surface input.
 *
 * Frame flow:
 *   VirtualDisplay → Surface (codec input) → H.264 encoder → encoded NALUs
 *   → [onEncodedFrame] callback → MuxerController
 *
 * @param projection     Active MediaProjection from the system screen capture intent.
 * @param config         Recording resolution, bitrate, frame rate settings.
 * @param onVideoTrack   Called once when the encoder emits its output format
 *                       (INFO_OUTPUT_FORMAT_CHANGED). Returns the muxer track index.
 * @param onEncodedFrame Called for every encoded frame with track index, buffer, and info.
 */
class ScreenEncoder(
    private val projection: MediaProjection,
    private val config: RecordingConfig,
    private val onVideoTrack: (MediaFormat) -> Int,
    private val onEncodedFrame: (Int, ByteBuffer, MediaCodec.BufferInfo) -> Unit,
) {
    companion object {
        private const val TAG = "ScreenEncoder"
    }

    private lateinit var codec: MediaCodec
    private lateinit var virtualDisplay: VirtualDisplay
    private var trackIndex = -1

    @Volatile
    private var running = true

    @Volatile
    var isPaused: Boolean = false
        private set

    fun pause() {
        isPaused = true
        Log.i(TAG, "ScreenEncoder paused")
    }

    fun resume() {
        isPaused = false
        try {
            val params = android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec.setParameters(params)
            Log.i(TAG, "ScreenEncoder resumed — requested sync frame")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request sync frame on resume", e)
        }
    }

    /**
     * Configures and starts the H.264 encoder + VirtualDisplay pipeline.
     * Must be called from a thread that can tolerate blocking (the drain
     * thread is spawned internally).
     */
    fun start() {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, config.videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameInterval)

            try {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                val repeatIntervalUs = (1_000_000L / config.frameRate).coerceAtLeast(8_333L)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, repeatIntervalUs)
            } catch (_: Exception) {}
        }

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        // The encoder provides a Surface that the VirtualDisplay renders into
        val inputSurface = codec.createInputSurface()
        codec.start()

        virtualDisplay = projection.createVirtualDisplay(
            "ZoomRecord-Screen",
            config.width,
            config.height,
            Resources.getSystem().displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            null,
        )

        // Start the drain thread that pulls encoded frames from the codec
        drainThread.start()

        Log.i(TAG, "Started: ${config.width}x${config.height} @ ${config.frameRate}fps, " +
                "${config.videoBitrate / 1_000_000}Mbps")
    }

    /**
     * Drain thread: continuously dequeues encoded output buffers from the
     * codec and forwards them to the muxer via [onEncodedFrame].
     */
    private val drainThread = Thread({
        val bufferInfo = MediaCodec.BufferInfo()

        while (running) {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000) // 10ms timeout

            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // First callback — register the video track with the muxer
                    trackIndex = onVideoTrack(codec.outputFormat)
                    Log.d(TAG, "Video track registered: index=$trackIndex")
                }

                outIndex >= 0 -> {
                    val buf = codec.getOutputBuffer(outIndex) ?: continue

                    if (!isPaused && bufferInfo.size > 0 && trackIndex >= 0 &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        buf.position(bufferInfo.offset)
                        buf.limit(bufferInfo.offset + bufferInfo.size)
                        onEncodedFrame(trackIndex, buf, bufferInfo)
                    }

                    codec.releaseOutputBuffer(outIndex, false)

                    // EOS flag means the encoder was signaled to stop
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        Log.d(TAG, "EOS received")
                        break
                    }
                }

                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // No output available yet — loop continues
                }
            }
        }

        Log.d(TAG, "Drain thread exiting")
    }, "video-drain").apply {
        // If this thread crashes, we want to log it rather than silently die
        setUncaughtExceptionHandler { _, e ->
            Log.e(TAG, "Video drain thread crashed", e)
        }
    }

    /**
     * Stops the encoder and releases all resources.
     * Call from the service's stop path — blocks briefly for drain thread join.
     */
    fun stop() {
        running = false

        try {
            codec.signalEndOfInputStream()
        } catch (e: Exception) {
            Log.w(TAG, "signalEndOfInputStream failed", e)
        }

        drainThread.join(1_000) // Wait up to 1s for drain to finish

        try {
            virtualDisplay.release()
        } catch (e: Exception) {
            Log.w(TAG, "VirtualDisplay release failed", e)
        }

        try {
            codec.stop()
            codec.release()
        } catch (e: Exception) {
            Log.w(TAG, "Codec stop/release failed", e)
        }

        Log.i(TAG, "Stopped")
    }
}
