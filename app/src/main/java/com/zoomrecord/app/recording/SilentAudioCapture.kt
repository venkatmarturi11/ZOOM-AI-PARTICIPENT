package com.zoomrecord.app.recording

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * Manages phone speaker volume for silent meeting recording.
 *
 * When activated:
 * - Saves current STREAM_MUSIC volume
 * - Sets STREAM_MUSIC volume to 0 (silent)
 *
 * When deactivated:
 * - Restores original volume levels
 */
class SilentAudioCapture(private val context: Context) {

    companion object {
        private const val TAG = "SilentAudioCapture"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var savedMusicVolume: Int = -1
    private var isActive = false

    /**
     * Mutes the phone media speaker so Zoom audio is captured silently.
     */
    fun activate() {
        if (isActive || audioManager == null) return

        try {
            // Save current media volume
            savedMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

            // Set media stream to minimum non-zero volume (virtually inaudible but keeps
            // AudioPlaybackCapture receiving actual audio data — volume=0 produces silence
            // on many OEMs because playback capture taps AFTER the volume mixer)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 1, 0)

            isActive = true
            Log.i(TAG, "Speaker media volume set to minimum (saved music vol=$savedMusicVolume)")
        } catch (e: Exception) {
            Log.w(TAG, "Could not adjust speaker volume safely", e)
        }
    }

    /**
     * Restores the phone speaker to its original volume.
     */
    fun deactivate() {
        if (!isActive || audioManager == null) return

        try {
            if (savedMusicVolume >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
            }
            isActive = false
            Log.i(TAG, "Speaker media volume restored (vol=$savedMusicVolume)")
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore speaker volume", e)
        }
    }

    /**
     * Whether silent mode is currently active.
     */
    fun isActive(): Boolean = isActive
}
