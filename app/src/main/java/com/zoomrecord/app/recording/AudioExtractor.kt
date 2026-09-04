package com.zoomrecord.app.recording

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Extracts and exports synchronized audio from an MP4 meeting recording
 * into a standalone companion audio file (.mp3 / .m4a) matching the exact
 * meeting timeline.
 */
object AudioExtractor {

    private const val TAG = "AudioExtractor"

    /**
     * Extracts the audio track from an MP4 file and saves it as a companion .mp3 / .m4a file.
     *
     * @param context Application context for MediaScanner indexing
     * @param mp4Path Absolute path to the source MP4 video
     * @return Path to the generated companion audio file, or null if extraction failed.
     */
    fun extractAudioFromMp4(context: Context, mp4Path: String): String? {
        val mp4File = File(mp4Path)
        if (!mp4File.exists() || mp4File.length() <= 0) {
            Log.w(TAG, "Cannot extract audio: source MP4 does not exist or is empty: $mp4Path")
            return null
        }

        val baseName = mp4File.nameWithoutExtension
        val parentDir = mp4File.parentFile ?: return null
        val audioFile = File(parentDir, "$baseName.mp3")
        val audioPath = audioFile.absolutePath

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            extractor.setDataSource(mp4Path)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null) {
                Log.w(TAG, "No audio track found in $mp4Path")
                extractor.release()
                return null
            }

            extractor.selectTrack(audioTrackIndex)

            // 1. Attempt standard M4A/MP4 audio container extraction
            muxer = MediaMuxer(audioPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val dstAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()

            val maxBufferSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                64 * 1024
            }

            val buffer = ByteBuffer.allocate(maxBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = extractor.readSampleData(buffer, 0)

                if (bufferInfo.size < 0) {
                    break
                }

                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags

                muxer.writeSampleData(dstAudioTrack, buffer, bufferInfo)
                extractor.advance()
            }

            muxer.stop()
            muxer.release()
            muxer = null
            extractor.release()

            if (audioFile.exists() && audioFile.length() > 0) {
                Log.i(TAG, "Successfully extracted audio to $audioPath (${audioFile.length()} bytes)")
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(audioPath),
                    arrayOf("audio/mp3", "audio/mpeg", "audio/mp4", "audio/x-m4a"),
                    null
                )
                return audioPath
            }
        } catch (e: Exception) {
            Log.w(TAG, "Standard audio extraction failed, falling back to ADTS extraction", e)
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            return extractAdtsFallback(context, mp4Path, audioPath)
        }

        return null
    }

    /**
     * Fallback ADTS frame extraction for raw AAC streams into an audio file.
     */
    private fun extractAdtsFallback(context: Context, mp4Path: String, outAudioPath: String): String? {
        val extractor = MediaExtractor()
        var fos: FileOutputStream? = null
        try {
            extractor.setDataSource(mp4Path)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null) return null

            extractor.selectTrack(audioTrackIndex)
            val sampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channelCount = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

            val outFile = File(outAudioPath)
            fos = FileOutputStream(outFile)
            val buffer = ByteBuffer.allocate(64 * 1024)

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val packet = ByteArray(sampleSize + 7)
                addAdtsHeader(packet, packet.size, sampleRate, channelCount)
                buffer.get(packet, 7, sampleSize)
                buffer.clear()

                fos.write(packet)
                extractor.advance()
            }

            fos.flush()
            fos.close()
            fos = null
            extractor.release()

            if (outFile.exists() && outFile.length() > 0) {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(outAudioPath),
                    arrayOf("audio/mp3", "audio/aac", "audio/mpeg"),
                    null
                )
                return outAudioPath
            }
        } catch (e: Exception) {
            Log.e(TAG, "ADTS extraction fallback failed", e)
        } finally {
            try { fos?.close() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
        return null
    }

    private fun addAdtsHeader(packet: ByteArray, packetLen: Int, sampleRate: Int, channels: Int) {
        val freqIdx = when (sampleRate) {
            96000 -> 0
            88200 -> 1
            64000 -> 2
            48000 -> 3
            44100 -> 4
            32000 -> 5
            24000 -> 6
            22050 -> 7
            16000 -> 8
            12000 -> 9
            11025 -> 10
            8000 -> 11
            else -> 4
        }
        val profile = 2 // AAC LC
        val chanCfg = channels.coerceIn(1, 2)

        packet[0] = 0xFF.toByte()
        packet[1] = 0xF9.toByte()
        packet[2] = (((profile - 1) shl 6) + (freqIdx shl 2) + (chanCfg shr 2)).toByte()
        packet[3] = (((chanCfg and 3) shl 6) + (packetLen shr 11)).toByte()
        packet[4] = ((packetLen and 0x7FF) shr 3).toByte()
        packet[5] = (((packetLen and 7) shl 5) + 0x1F).toByte()
        packet[6] = 0xFC.toByte()
    }
}
