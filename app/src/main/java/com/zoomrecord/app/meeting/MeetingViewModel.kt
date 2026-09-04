package com.zoomrecord.app.meeting

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zoomrecord.app.recording.RecordingConfig
import com.zoomrecord.app.recording.StorageGuard
import com.zoomrecord.app.recording.clampToDisplay
import com.zoomrecord.app.recording.estimateBytesFor
import com.zoomrecord.app.zoom.ZoomLinkParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel managing the meeting form, link parsing, join options, and bot status.
 */
class MeetingViewModel(private val context: Context) : ViewModel() {

    private val profileStore = com.zoomrecord.app.auth.UserProfileStore(context)
    private val savedProfile = profileStore.getProfile()

    private val _uiState = MutableStateFlow(
        MeetingUiState(
            displayName = savedProfile.fullName,
        )
    )
    val uiState: StateFlow<MeetingUiState> = _uiState

    init {
        refreshStorageInfo()
        loadSavedProfile()
    }

    fun loadSavedProfile() {
        val profile = profileStore.getProfile()
        if (profile.isConfigured) {
            _uiState.update { it.copy(displayName = profile.fullName) }
        }
    }

    /**
     * Updates meeting input. If a full Zoom link is pasted, automatically
     * parses and extracts the meeting ID, passcode, and Web Client URL.
     */
    fun updateMeetingInput(input: String) {
        val parsed = ZoomLinkParser.parse(input, _uiState.value.displayName)
        _uiState.update {
            val pwd = if (parsed.password.isNotEmpty()) parsed.password else it.password
            val webUrl = if (parsed.webClientUrl.isNotEmpty()) parsed.webClientUrl
                         else ZoomLinkParser.buildWebClientUrl(parsed.meetingNumber, pwd, it.displayName, input)
            it.copy(
                meetingLinkOrId = input,
                meetingNumber = parsed.meetingNumber,
                password = pwd,
                webClientUrl = webUrl,
                isLinkDetected = parsed.password.isNotEmpty() || input.contains("zoom.us", ignoreCase = true),
            )
        }
    }

    fun updateMeetingNumber(value: String) {
        updateMeetingInput(value)
    }

    fun updatePassword(value: String) {
        _uiState.update {
            val webUrl = ZoomLinkParser.buildWebClientUrl(it.meetingNumber, value, it.displayName, it.meetingLinkOrId)
            it.copy(password = value, webClientUrl = webUrl)
        }
    }

    fun updateDisplayName(value: String) {
        _uiState.update {
            val webUrl = ZoomLinkParser.buildWebClientUrl(it.meetingNumber, it.password, value, it.meetingLinkOrId)
            it.copy(displayName = value, webClientUrl = webUrl)
        }
    }

    fun toggleAutoRecordOnJoin(enabled: Boolean) {
        _uiState.update { it.copy(autoRecordOnJoin = enabled) }
    }

    fun toggleShowFloatingHud(enabled: Boolean) {
        _uiState.update { it.copy(showFloatingHud = enabled) }
    }

    fun toggleAudioBoost(enabled: Boolean) {
        _uiState.update { it.copy(audioBoostEnabled = enabled) }
    }

    fun toggleDontConnectAudio(enabled: Boolean) {
        _uiState.update { it.copy(dontConnectAudio = enabled) }
    }

    fun toggleSpeakerOutput(enabled: Boolean) {
        _uiState.update { it.copy(speakerOutputEnabled = enabled) }
    }

    fun toggleTurnOffVideo(enabled: Boolean) {
        _uiState.update { it.copy(turnOffVideo = enabled) }
    }

    fun updateBotStatus(status: String, error: String? = null) {
        _uiState.update {
            it.copy(
                botStatus = BotStatus.fromString(status),
                botError = error,
            )
        }
        if (status == "stopped" || status == "error") {
            refreshStorageInfo()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(botError = null) }
    }

    fun refreshStorageInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            val config = RecordingConfig().clampToDisplay()
            val available = StorageGuard.availableBytes(context)
            val estimate30 = estimateBytesFor(config, 30)
            val estimate60 = estimateBytesFor(config, 60)
            val hasSpace = StorageGuard.hasEnoughSpace(context, estimate60)

            _uiState.update {
                it.copy(
                    availableBytes = available,
                    estimate30MinBytes = estimate30,
                    estimate60MinBytes = estimate60,
                    hasEnoughStorage = hasSpace,
                )
            }
        }
    }

    val canStartBot: Boolean
        get() = (_uiState.value.meetingNumber.length >= 9 || _uiState.value.webClientUrl.isNotBlank() || _uiState.value.meetingLinkOrId.isNotBlank()) &&
                _uiState.value.displayName.isNotBlank() &&
                _uiState.value.botStatus == BotStatus.IDLE

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MeetingViewModel(context.applicationContext) as T
        }
    }
}

// ── Bot Status ───────────────────────────────────────────────────────

enum class BotStatus(val displayText: String) {
    IDLE("Ready"),
    JOINING("Joining meeting…"),
    RECORDING("Recording meeting…"),
    STOPPING("Stopping…"),
    ERROR("Error");

    companion object {
        fun fromString(status: String): BotStatus = when (status) {
            "joining" -> JOINING
            "recording" -> RECORDING
            "stopping" -> STOPPING
            "stopped" -> IDLE
            "error" -> ERROR
            else -> IDLE
        }
    }
}

// ── UI State ─────────────────────────────────────────────────────────

data class MeetingUiState(
    // Meeting form
    val meetingLinkOrId: String = "",
    val meetingNumber: String = "",
    val password: String = "",
    val displayName: String = "",
    val webClientUrl: String = "",
    val isLinkDetected: Boolean = false,

    // Join & Recording options
    val autoRecordOnJoin: Boolean = true,   // Default auto-record on join (1080p + Audio)
    val showFloatingHud: Boolean = true,     // Floating timer & Stop HUD overlay over Zoom
    val audioBoostEnabled: Boolean = true,   // 2.5x high-gain audio speech boost with soft limiter
    val speakerOutputEnabled: Boolean = true,  // Default true: Speaker ON so mic captures meeting audio (only reliable capture path)
    val dontConnectAudio: Boolean = false,  // Default false: Connect to audio in Zoom
    val turnOffVideo: Boolean = true,        // Default camera off for bot

    // Bot state
    val botStatus: BotStatus = BotStatus.IDLE,
    val botError: String? = null,

    // Storage info
    val availableBytes: Long = 0,
    val estimate30MinBytes: Long = 0,
    val estimate60MinBytes: Long = 0,
    val hasEnoughStorage: Boolean = true,
) {
    val meetingInput: String
        get() = meetingLinkOrId
}
