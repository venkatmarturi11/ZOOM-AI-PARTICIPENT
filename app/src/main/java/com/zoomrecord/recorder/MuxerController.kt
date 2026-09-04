package com.zoomrecord.recorder

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import java.io.Closeable

/** Thread-safe MP4 muxer. It buffers encoded samples until the required tracks exist. */
class MuxerController(private val pfd: ParcelFileDescriptor) : Closeable {
    private val lock = Any()
    private val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var audioTrack = -1
    private var videoTrack = -1
    private var lastVideoPts = -1L
    private var lastAudioPts = -1L
    private var started = false
    private var closed = false
    private var forceVideoOnly = false
    private val pending = ArrayList<Sample>()

    data class Sample(val track: Int, val data: ByteArray, val ptsUs: Long, val flags: Int)

    fun addVideoTrack(format: MediaFormat) = synchronized(lock) {
        check(!closed)
        if (videoTrack < 0) videoTrack = muxer.addTrack(format)
        tryStartLocked()
        videoTrack
    }

    fun addAudioTrack(format: MediaFormat) = synchronized(lock) {
        check(!closed)
        if (audioTrack < 0) audioTrack = muxer.addTrack(format)
        tryStartLocked()
        audioTrack
    }

    fun allowVideoOnly() = synchronized(lock) {
        forceVideoOnly = true
        tryStartLocked()
    }

    fun writeVideo(buffer: java.nio.ByteBuffer, info: MediaCodec.BufferInfo, ptsUs: Long) =
        write(videoTrack, buffer, info, ptsUs)

    fun writeAudio(buffer: java.nio.ByteBuffer, info: MediaCodec.BufferInfo, ptsUs: Long) =
        write(audioTrack, buffer, info, ptsUs)

    private fun write(track: Int, buffer: java.nio.ByteBuffer, info: MediaCodec.BufferInfo, ptsUs: Long) {
        if (info.size <= 0 || track < 0) return
        val bytes = ByteArray(info.size)
        val oldPos = buffer.position()
        val oldLimit = buffer.limit()
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        buffer.get(bytes)
        buffer.position(oldPos)
        buffer.limit(oldLimit)

        synchronized(lock) {
            if (closed) return
            val monotonicPts = if (track == videoTrack) {
                val p = if (ptsUs > lastVideoPts) ptsUs else lastVideoPts + 1L
                lastVideoPts = p
                p
            } else if (track == audioTrack) {
                val p = if (ptsUs > lastAudioPts) ptsUs else lastAudioPts + 1L
                lastAudioPts = p
                p
            } else {
                ptsUs.coerceAtLeast(0L)
            }
            val sample = Sample(track, bytes, monotonicPts, info.flags)
            if (started) {
                try {
                    val b = java.nio.ByteBuffer.wrap(sample.data)
                    muxer.writeSampleData(sample.track, b, MediaCodec.BufferInfo().apply {
                        set(0, sample.data.size, sample.ptsUs, sample.flags)
                    })
                } catch (e: Exception) {
                    android.util.Log.w("MuxerController", "writeSampleData failed: ${e.message}")
                }
            } else if (pending.size < 500) {
                pending += sample
            }
        }
    }

    private fun tryStartLocked() {
        if (started || closed) return
        val ready = videoTrack >= 0 && (audioTrack >= 0 || forceVideoOnly)
        if (!ready) return
        try {
            muxer.start()
            started = true
            pending.sortBy { it.ptsUs }
            for (sample in pending) {
                try {
                    val b = java.nio.ByteBuffer.wrap(sample.data)
                    muxer.writeSampleData(sample.track, b, MediaCodec.BufferInfo().apply {
                        set(0, sample.data.size, sample.ptsUs, sample.flags)
                    })
                } catch (e: Exception) {
                    android.util.Log.w("MuxerController", "drain sample failed: ${e.message}")
                }
            }
            pending.clear()
        } catch (e: Exception) {
            android.util.Log.e("MuxerController", "Muxer start failed", e)
        }
    }

    fun isStarted(): Boolean = synchronized(lock) { started }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            if (!started && videoTrack >= 0) {
                forceVideoOnly = true
                tryStartLocked()
            }
            closed = true
            if (started) {
                try { muxer.stop() } catch (_: Exception) {}
            }
            try { muxer.release() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
            pending.clear()
        }
    }
}
