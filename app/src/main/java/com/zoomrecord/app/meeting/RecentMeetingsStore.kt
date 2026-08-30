package com.zoomrecord.app.meeting

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecentMeetingItem(
    val meetingId: String,
    val title: String,
    val link: String,
    val password: String = "",
    val displayName: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val formattedDate: String = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault()).format(Date(timestamp)),
)

class RecentMeetingsStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("recent_meetings_store", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_RECENT_LIST = "recent_meetings_json"
        private const val MAX_ITEMS = 15
    }

    fun addMeeting(
        meetingId: String,
        title: String? = null,
        link: String = "",
        password: String = "",
        displayName: String = "",
    ) {
        if (meetingId.isBlank() && link.isBlank()) return

        val cleanId = meetingId.ifBlank { "Zoom Meeting" }
        val inferredTitle = if (!title.isNullOrBlank()) {
            title
        } else if (link.contains("zoom.us", ignoreCase = true)) {
            "Zoom Meeting $cleanId"
        } else {
            "Meeting $cleanId"
        }

        val currentList = getRecentMeetings().toMutableList()
        // Remove existing item with same meeting ID or link to move it to top
        currentList.removeAll { it.meetingId == cleanId || (link.isNotBlank() && it.link == link) }

        val newItem = RecentMeetingItem(
            meetingId = cleanId,
            title = inferredTitle,
            link = link,
            password = password,
            displayName = displayName,
            timestamp = System.currentTimeMillis(),
        )

        currentList.add(0, newItem)
        val trimmed = currentList.take(MAX_ITEMS)
        saveList(trimmed)
    }

    fun getRecentMeetings(): List<RecentMeetingItem> {
        val jsonStr = prefs.getString(KEY_RECENT_LIST, null) ?: return defaultSampleMeetings()
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<RecentMeetingItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    RecentMeetingItem(
                        meetingId = obj.optString("meetingId"),
                        title = obj.optString("title"),
                        link = obj.optString("link"),
                        password = obj.optString("password"),
                        displayName = obj.optString("displayName"),
                        timestamp = obj.optLong("timestamp"),
                        formattedDate = obj.optString("formattedDate"),
                    )
                )
            }
            if (list.isEmpty()) defaultSampleMeetings() else list
        } catch (_: Exception) {
            defaultSampleMeetings()
        }
    }

    fun deleteMeeting(meetingId: String) {
        val currentList = getRecentMeetings().filterNot { it.meetingId == meetingId }
        saveList(currentList)
    }

    private fun saveList(list: List<RecentMeetingItem>) {
        val array = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("meetingId", item.meetingId)
                put("title", item.title)
                put("link", item.link)
                put("password", item.password)
                put("displayName", item.displayName)
                put("timestamp", item.timestamp)
                put("formattedDate", item.formattedDate)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_RECENT_LIST, array.toString()).apply()
    }

    private fun defaultSampleMeetings(): List<RecentMeetingItem> {
        return listOf(
            RecentMeetingItem(
                meetingId = "842 9182 4310",
                title = "Team Daily Standup",
                link = "https://us04web.zoom.us/j/84291824310?pwd=team",
                formattedDate = "Today • 10:00 AM",
            ),
            RecentMeetingItem(
                meetingId = "912 3456 7890",
                title = "Product Strategy & Review",
                link = "https://us04web.zoom.us/j/91234567890?pwd=prod",
                formattedDate = "Yesterday • 02:30 PM",
            ),
            RecentMeetingItem(
                meetingId = "551 2233 4455",
                title = "Client Presentation & Demo",
                link = "https://us04web.zoom.us/j/55122334455?pwd=demo",
                formattedDate = "Aug 20, 2026 • 04:00 PM",
            ),
        )
    }
}
