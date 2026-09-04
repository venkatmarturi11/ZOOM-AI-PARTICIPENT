package com.zoomrecord.app.zoom

import android.net.Uri

/**
 * Robust parser for Zoom meeting links and meeting IDs.
 *
 * Supported formats:
 * - Standard meeting: https://zoom.us/j/1234567890?pwd=xxxx
 * - Subdomain meeting: https://us04web.zoom.us/j/1234567890?pwd=xxxx
 * - Webinar link: https://us06web.zoom.us/w/1234567890?pwd=xxxx
 * - Webinar registration: https://us06web.zoom.us/webinar/register/WN_xxxx
 * - Meeting registration: https://zoom.us/meeting/register/xxxx
 * - Deep links: zoommtg://zoom.us/join?action=join&confno=1234567890&pwd=xxxx
 * - Direct numbers: "123 456 7890", "1234567890"
 */
object ZoomLinkParser {

    private val DIGITS_9_TO_11_REGEX = Regex("""\b(\d{9,11})\b""")

    data class ParsedMeeting(
        val meetingNumber: String,
        val password: String = "",
        val originalInput: String = "",
        val webClientUrl: String = "",
        val zoomDeepLinkUri: String = "",
        val isWebLink: Boolean = false,
    )

    /**
     * Parses user input (URL or raw meeting ID) into meeting components.
     */
    fun parse(input: String, displayName: String = ""): ParsedMeeting {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ParsedMeeting("", "", "")

        val isUrl = trimmed.startsWith("http://", ignoreCase = true) ||
                    trimmed.startsWith("https://", ignoreCase = true) ||
                    trimmed.startsWith("zoommtg://", ignoreCase = true) ||
                    trimmed.startsWith("zoomus://", ignoreCase = true)

        var meetingId = ""
        var pwd = ""

        if (isUrl) {
            try {
                val uri = Uri.parse(trimmed)
                val pathSegments = uri.pathSegments

                // 1. Check path segments after /j/, /w/, /wc/, /join/
                for (i in pathSegments.indices) {
                    val seg = pathSegments[i].lowercase()
                    if (seg == "j" || seg == "w" || seg == "wc" || seg == "join" || seg == "start") {
                        if (i + 1 < pathSegments.size) {
                            val candidate = pathSegments[i + 1].filter { it.isDigit() }
                            if (candidate.length in 9..11) {
                                meetingId = candidate
                                break
                            }
                        }
                    }
                }

                // 2. Scan path segments for any segment that is strictly 9..11 digits
                if (meetingId.isEmpty()) {
                    for (seg in pathSegments) {
                        val digits = seg.filter { it.isDigit() }
                        if (digits.length in 9..11) {
                            meetingId = digits
                            break
                        }
                    }
                }

                // 3. Check query parameters: confno, mid, id
                if (meetingId.isEmpty()) {
                    val confNo = uri.getQueryParameter("confno")
                        ?: uri.getQueryParameter("mid")
                        ?: uri.getQueryParameter("id")
                    if (confNo != null) {
                        val digits = confNo.filter { it.isDigit() }
                        if (digits.length in 9..11) {
                            meetingId = digits
                        }
                    }
                }

                // 4. Regex fallback: search the URI path for 9..11 consecutive digits
                if (meetingId.isEmpty()) {
                    val match = DIGITS_9_TO_11_REGEX.find(uri.path ?: "")
                    if (match != null) {
                        meetingId = match.groupValues[1]
                    }
                }

                pwd = uri.getQueryParameter("pwd") ?: ""
            } catch (_: Exception) {}
        } else {
            // Direct number input: strip spaces and verify digit length
            val digits = trimmed.filter { it.isDigit() }
            if (digits.length in 9..11) {
                meetingId = digits
            }
        }

        val finalUrl = buildWebClientUrl(
            meetingNumber = meetingId,
            password = pwd,
            displayName = displayName,
            originalInput = trimmed,
        )

        val deepLink = if (meetingId.isNotEmpty()) {
            ZoomAppLauncher.buildZoomDeepLink(
                meetingNumber = meetingId,
                password = pwd,
                displayName = displayName
            ).toString()
        } else ""

        return ParsedMeeting(
            meetingNumber = meetingId,
            password = pwd,
            originalInput = trimmed,
            webClientUrl = finalUrl,
            zoomDeepLinkUri = deepLink,
            isWebLink = isUrl,
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
