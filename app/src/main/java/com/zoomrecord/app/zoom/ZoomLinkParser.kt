package com.zoomrecord.app.zoom

import android.net.Uri

/**
 * Parses Zoom meeting links into meeting ID and passcode.
 *
 * Supported formats:
 * - https://zoom.us/j/1234567890?pwd=xxxx
 * - https://us04web.zoom.us/j/1234567890?pwd=xxxx
 * - https://zoom.us/w/1234567890?pwd=xxxx
 * - zoommtg://zoom.us/join?action=join&confno=1234567890&pwd=xxxx
 * - Direct meeting numbers: "123 456 7890", "1234567890"
 */
object ZoomLinkParser {

    data class ParsedMeeting(
        val meetingNumber: String,
        val password: String = "",
        val originalInput: String = "",
        val webClientUrl: String = "",
    )

    /**
     * Parses a user input string (either a URL or direct meeting number)
     * and extracts the meeting ID, passcode, and constructed web client URL.
     */
    fun parse(input: String, displayName: String = ""): ParsedMeeting {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ParsedMeeting("", "", "")

        var meetingId = ""
        var pwd = ""

        // Check if input is a Zoom registration link or direct web link
        if (trimmed.contains("/register", ignoreCase = true) ||
            trimmed.contains("/meeting/register", ignoreCase = true) ||
            trimmed.contains("/webinar/register", ignoreCase = true)
        ) {
            var extractedId = ""
            var extractedPwd = ""
            try {
                val uri = Uri.parse(trimmed)
                for (seg in uri.pathSegments) {
                    val digits = seg.filter { it.isDigit() }
                    if (digits.length in 9..11) {
                        extractedId = digits
                        break
                    }
                }
                extractedPwd = uri.getQueryParameter("pwd") ?: ""
            } catch (_: Exception) {}

            if (extractedId.isNotEmpty()) {
                val directUrl = buildWebClientUrl(
                    meetingNumber = extractedId,
                    password = extractedPwd,
                    displayName = displayName,
                    originalInput = trimmed
                )
                return ParsedMeeting(
                    meetingNumber = extractedId,
                    password = extractedPwd,
                    originalInput = trimmed,
                    webClientUrl = directUrl,
                )
            }
        }

        // Check if input is a URL
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("zoommtg://", ignoreCase = true)
        ) {
            try {
                val uri = Uri.parse(trimmed)
                val pathSegments = uri.pathSegments

                for (i in pathSegments.indices) {
                    val seg = pathSegments[i].lowercase()
                    if (seg == "j" || seg == "w" || seg == "wc" || seg == "join" || seg == "start") {
                        if (i + 1 < pathSegments.size) {
                            val candidate = pathSegments[i + 1].filter { it.isDigit() }
                            if (candidate.length >= 9) {
                                meetingId = candidate
                                break
                            }
                        }
                    }
                    val directDigits = seg.filter { it.isDigit() }
                    if (directDigits.length in 9..11) {
                        meetingId = directDigits
                        break
                    }
                }

                // Check query params if not found in path
                if (meetingId.isEmpty()) {
                    val confNo = uri.getQueryParameter("confno")
                        ?: uri.getQueryParameter("mid")
                        ?: uri.getQueryParameter("id")
                    if (confNo != null) {
                        meetingId = confNo.filter { it.isDigit() }
                    }
                }

                pwd = uri.getQueryParameter("pwd") ?: ""
            } catch (_: Exception) {}
        }

        // If not a URL or meetingId still empty, extract digits as meeting number
        if (meetingId.isEmpty()) {
            meetingId = trimmed.filter { it.isDigit() }
        }

        val finalUrl = buildWebClientUrl(
            meetingNumber = meetingId,
            password = pwd,
            displayName = displayName,
            originalInput = trimmed,
        )

        return ParsedMeeting(
            meetingNumber = meetingId,
            password = pwd,
            originalInput = trimmed,
            webClientUrl = finalUrl,
        )
    }

    /**
     * Builds a Zoom Web Client URL ready to join in an embedded browser/webview.
     */
    fun buildWebClientUrl(
        meetingNumber: String,
        password: String,
        displayName: String,
        originalInput: String = "",
    ): String {
        if (originalInput.startsWith("http", ignoreCase = true) &&
            originalInput.contains("zoom.us/wc", ignoreCase = true)
        ) {
            return originalInput
        }

        if (meetingNumber.isEmpty()) return ""

        val encodedName = Uri.encode(if (displayName.isNotBlank()) displayName else "Participant")
        val encodedPwd = Uri.encode(password)

        return if (password.isNotEmpty()) {
            "https://app.zoom.us/wc/$meetingNumber/join?pwd=$encodedPwd&uname=$encodedName&prefer=1"
        } else {
            "https://app.zoom.us/wc/$meetingNumber/join?uname=$encodedName&prefer=1"
        }
    }
}
