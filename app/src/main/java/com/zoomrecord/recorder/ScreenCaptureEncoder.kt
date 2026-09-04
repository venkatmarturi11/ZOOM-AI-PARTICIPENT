package com.zoomrecord.recorder

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Surface
import java.nio.ByteBuffer

class ScreenCaptureEncoder(
    private val context: Context,
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val muxer: MuxerController
) {
    private lateinit var codec: MediaCodec
    private lateinit var surface: Surface
    private lateinit var display: VirtualDisplay
    private lateinit var thread: Thread
    @Volatile private var running = false
    @Volatile private var stopping = false
    private var firstVideoPts = Long.MIN_VALUE

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            try {
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / 30)
            } catch (_: Exception) {}
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        codec.start()
        running = true
        stopping = false

        display = projection.createVirtualDisplay(
            "InternalScreenRecorder",
            width, height, context.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null
        )

        thread = Thread({ drain() }, "video-encoder").also { it.start() }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        var emptyDrains = 0
        while (running || stopping) {
            val out = codec.dequeueOutputBuffer(info, 10_000)
            when {
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> muxer.addVideoTrack(codec.outputFormat)
                out >= 0 -> {
                    emptyDrains = 0
                    val b = codec.getOutputBuffer(out)
                    if (b != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        if (firstVideoPts == Long.MIN_VALUE) firstVideoPts = info.presentationTimeUs
                        muxer.writeVideo(b, info, info.presentationTimeUs - firstVideoPts)
                    }
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codec.releaseOutputBuffer(out, false)
                    if (eos) break
                }
                out == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (stopping) {
                        emptyDrains++
                        if (emptyDrains >= 5) break
                    }
                }
            }
        }
    }

    fun stop() {
        if (!running && !stopping) return
        stopping = true
        try { codec.signalEndOfInputStream() } catch (_: Exception) {}
        try { display.release() } catch (_: Exception) {}
        try { surface.release() } catch (_: Exception) {}
        try { thread.join(5_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        running = false
        stopping = false
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
    }
}
