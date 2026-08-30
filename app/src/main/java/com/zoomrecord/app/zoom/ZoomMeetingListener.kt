package com.zoomrecord.app.zoom

import android.util.Log

/**
 * Listener for Zoom meeting status changes.
 * Used to correlate meeting events with recording timeline.
 *
 * ⚠️ STUB: Implement the actual ZoomSDK listener interface when .aar is added.
 *
 * When wired up, this logs events like disconnections/reconnections so you can
 * correlate "frozen" sections in recordings with actual Zoom connectivity issues.
 */
class ZoomMeetingListener {

    companion object {
        private const val TAG = "ZoomMeetingListener"
    }

    data class MeetingEvent(
        val type: EventType,
        val timestampMs: Long,
        val detail: String? = null,
    )

    enum class EventType {
        CONNECTED,
        DISCONNECTED,
        RECONNECTING,
        RECONNECTED,
        ENDED,
    }

    private val _events = mutableListOf<MeetingEvent>()
    val events: List<MeetingEvent> get() = _events.toList()

    /**
     * Call from the Zoom SDK's onMeetingStatusChanged callback.
     *
     * Example (when SDK is integrated):
     * ```
     * override fun onMeetingStatusChanged(status: MeetingStatus, errorCode: Int, internalErrorCode: Int) {
     *     when (status) {
     *         MeetingStatus.MEETING_STATUS_INMEETING -> logEvent(EventType.CONNECTED)
     *         MeetingStatus.MEETING_STATUS_RECONNECTING -> logEvent(EventType.RECONNECTING)
     *         MeetingStatus.MEETING_STATUS_IDLE -> logEvent(EventType.ENDED)
     *         else -> {}
     *     }
     * }
     * ```
     */
    fun logEvent(type: EventType, detail: String? = null) {
        val event = MeetingEvent(type, System.currentTimeMillis(), detail)
        _events.add(event)
        Log.i(TAG, "Meeting event: $type ${detail ?: ""}")
    }

    fun clear() {
        _events.clear()
    }
}
