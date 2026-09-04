package com.zoomrecord.recorder

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer

class AudioCaptureEncoder(
    private val projection: MediaProjection,
    private val muxer: MuxerController,
    private val onState: (String) -> Unit
) {
    companion object {
        private const val TAG = "InternalAudio"
        private const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 2
        private const val BYTES_PER_SAMPLE = 2
        private const val BITRATE = 192_000
    }

    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile var capturedNonZeroPcm = false
        private set
    private var samplesQueued: Long = 0
    private var track = -1

    fun start(): Boolean {
        if (Build.VERSION.SDK_INT < 29) {
            onState("Internal audio requires Android 10+")
            return false
        }
        return try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()

            val min = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (min <= 0) throw IllegalStateException("AudioRecord minimum buffer unavailable")

            val bufferSize = (min * 4).coerceAtLeast(32_768)
            val ar = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                ar.release()
                throw IllegalStateException("AudioPlaybackCapture AudioRecord could not initialize")
            }

            val mf = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(mf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()

            ar.startRecording()
            record = ar
            codec = c
            running = true
            onState("Internal audio capture started")
            thread = Thread({ loop(ar, c) }, "audio-capture").also { it.start() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Audio capture unavailable", e)
            onState("Internal audio unavailable: ${e.javaClass.simpleName}")
            try { record?.release() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            record = null
            codec = null
            false
        }
    }

    private fun loop(ar: AudioRecord, c: MediaCodec) {
        val pcm = ByteArray(8_192)
        val info = MediaCodec.BufferInfo()
        while (running) {
            val n = ar.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
            if (n <= 0) continue

            if (!capturedNonZeroPcm) {
                var nonZero = false
                var i = 0
                while (i + 1 < n) {
                    if (pcm[i].toInt() != 0 || pcm[i + 1].toInt() != 0) { nonZero = true; break }
                    i += 2
                }
                if (nonZero) {
                    capturedNonZeroPcm = true
                    onState("Non-zero internal PCM detected")
                }
            }

            var offset = 0
            while (offset < n && running) {
                val input = c.dequeueInputBuffer(10_000)
                if (input < 0) {
                    drain(c, info, false)
                    continue
                }
                val ib = c.getInputBuffer(input) ?: continue
                ib.clear()
                val rawCount = minOf(ib.remaining(), n - offset)
                val count = rawCount - (rawCount % (CHANNELS * BYTES_PER_SAMPLE))
                if (count <= 0) break
                ib.put(pcm, offset, count)
                val frames = count / (CHANNELS * BYTES_PER_SAMPLE)
                val pts = samplesQueued * 1_000_000L / SAMPLE_RATE
                c.queueInputBuffer(input, 0, count, pts, 0)
                samplesQueued += frames
                offset += count
            }

            drain(c, info, false)
        }

        try {
            var retries = 0
            while (retries < 10) {
                val input = c.dequeueInputBuffer(10_000)
                if (input >= 0) {
                    c.queueInputBuffer(input, 0, 0, samplesQueued * 1_000_000L / SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drain(c, info, false)
                retries++
            }
        } catch (e: Exception) { Log.w(TAG, "Audio EOS failed", e) }

        var emptyDrains = 0
        while (emptyDrains < 5) {
            val done = drain(c, info, true)
            if (done) break
            emptyDrains++
        }
    }

    private fun drain(c: MediaCodec, info: MediaCodec.BufferInfo, waitForEos: Boolean): Boolean {
        while (true) {
            val out = c.dequeueOutputBuffer(info, if (waitForEos) 10_000 else 0)
            when {
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addAudioTrack(c.outputFormat)
                }
                out >= 0 -> {
                    val b = c.getOutputBuffer(out)
                    if (b != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val pts = info.presentationTimeUs
                        muxer.writeAudio(b, info, pts)
                    }
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    c.releaseOutputBuffer(out, false)
                    if (eos) return true
                }
                out == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
            }
        }
    }

    fun stop() {
        running = false
        try { record?.stop() } catch (_: Exception) {}
        thread?.join(4_000)
        try { record?.release() } catch (_: Exception) {}
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        record = null
        codec = null
        thread = null
    }
}
