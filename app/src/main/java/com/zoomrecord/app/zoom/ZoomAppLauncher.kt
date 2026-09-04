package com.zoomrecord.app.zoom

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

/**
 * Utility for launching the native Zoom Mobile App (Zoom Cloud Meetings)
 * directly into meetings via Android Deep Link Intents or native web links.
 */
object ZoomAppLauncher {

    private const val TAG = "ZoomAppLauncher"
    const val ZOOM_PACKAGE_NAME = "us.zoom.videomeetings"

    enum class LaunchResult {
        LAUNCHED_ZOOM,
        OPENED_PLAY_STORE,
        ERROR
    }

    /**
     * Checks whether the official Zoom Cloud Meetings app is installed on the device.
     */
    fun isZoomInstalled(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            pm.getPackageInfo(ZOOM_PACKAGE_NAME, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "Error checking Zoom package status", e)
            false
        }
    }

    /**
     * Constructs the official Zoom deep link URI.
     * Format: zoomus://zoom.us/join?confno={id}&pwd={pwd}&uname={name}
     */
    fun buildZoomDeepLink(
        meetingNumber: String,
        password: String = "",
        displayName: String = "",
    ): Uri {
        val cleanNumber = meetingNumber.filter { it.isDigit() }
        val encodedName = Uri.encode(displayName.ifBlank { "Participant" })
        val encodedPwd = Uri.encode(password)

        val uriBuilder = StringBuilder("zoomus://zoom.us/join?confno=$cleanNumber")
        if (encodedPwd.isNotBlank()) {
            uriBuilder.append("&pwd=$encodedPwd")
        }
        if (encodedName.isNotBlank()) {
            uriBuilder.append("&uname=$encodedName")
        }
        return Uri.parse(uriBuilder.toString())
    }

    /**
     * Constructs the secondary zoommtg deep link URI.
     * Format: zoommtg://zoom.us/join?action=join&confno={id}&pwd={pwd}&uname={name}
     */
    fun buildZoomMtgLink(
        meetingNumber: String,
        password: String = "",
        displayName: String = "",
    ): Uri {
        val cleanNumber = meetingNumber.filter { it.isDigit() }
        val encodedName = Uri.encode(displayName.ifBlank { "Participant" })
        val encodedPwd = Uri.encode(password)

        val uriBuilder = StringBuilder("zoommtg://zoom.us/join?action=join&confno=$cleanNumber")
        if (encodedPwd.isNotBlank()) {
            uriBuilder.append("&pwd=$encodedPwd")
        }
        if (encodedName.isNotBlank()) {
            uriBuilder.append("&uname=$encodedName")
        }
        return Uri.parse(uriBuilder.toString())
    }

    /**
     * Primary launcher: accepts full URL or meeting number and opens directly in Zoom.
     */
    fun launchZoom(
        context: Context,
        input: String,
        meetingNumber: String = "",
        password: String = "",
        displayName: String = "",
    ): LaunchResult {
        val pm = context.packageManager
        val trimmed = input.trim()

        // Check if Zoom is installed on this device
        val zoomInstalled = isZoomInstalled(context)

        // Extract meeting ID and passcode
        val parsed = ZoomLinkParser.parse(trimmed, displayName)
        val cleanNumber = (if (parsed.meetingNumber.isNotBlank()) parsed.meetingNumber
                           else if (meetingNumber.isNotBlank()) meetingNumber
                           else trimmed).filter { it.isDigit() }
        val effectivePwd = if (parsed.password.isNotBlank()) parsed.password else password
        val effectiveName = displayName.ifBlank { "Participant" }

        // 1. Direct Zoom Deep Link (zoomus://) — highest priority for joining meetings directly
        if (cleanNumber.length in 9..11) {
            try {
                val primaryUri = buildZoomDeepLink(cleanNumber, effectivePwd, effectiveName)
                val primaryIntent = Intent(Intent.ACTION_VIEW, primaryUri).apply {
                    setPackage(ZOOM_PACKAGE_NAME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(primaryIntent)
                Log.i(TAG, "Launched native Zoom app via zoomus:// for $cleanNumber")
                return LaunchResult.LAUNCHED_ZOOM
            } catch (e: Exception) {
                Log.w(TAG, "Failed zoomus:// launch: ${e.message}")
            }

            // 2. Secondary Zoom Deep Link (zoommtg://)
            try {
                val mtgUri = buildZoomMtgLink(cleanNumber, effectivePwd, effectiveName)
                val mtgIntent = Intent(Intent.ACTION_VIEW, mtgUri).apply {
                    setPackage(ZOOM_PACKAGE_NAME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(mtgIntent)
                Log.i(TAG, "Launched native Zoom app via zoommtg:// for $cleanNumber")
                return LaunchResult.LAUNCHED_ZOOM
            } catch (e: Exception) {
                Log.w(TAG, "Failed zoommtg:// launch: ${e.message}")
            }

            // 3. Web URL targeted directly at Zoom
            try {
                val encodedPwd = Uri.encode(effectivePwd)
                val webUrl = if (encodedPwd.isNotEmpty()) {
                    "https://zoom.us/j/$cleanNumber?pwd=$encodedPwd"
                } else {
                    "https://zoom.us/j/$cleanNumber"
                }
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).apply {
                    setPackage(ZOOM_PACKAGE_NAME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
                Log.i(TAG, "Launched native Zoom app via web fallback for $cleanNumber")
                return LaunchResult.LAUNCHED_ZOOM
            } catch (e: Exception) {
                Log.w(TAG, "Failed web URL fallback: ${e.message}")
            }
        }

        // 4. If input is a full URL (including webinars or custom join links)
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("zoomus://", ignoreCase = true) ||
            trimmed.startsWith("zoommtg://", ignoreCase = true)
        ) {
            try {
                val directIntent = Intent(Intent.ACTION_VIEW, Uri.parse(trimmed)).apply {
                    setPackage(ZOOM_PACKAGE_NAME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(directIntent)
                Log.i(TAG, "Launched native Zoom app via direct URL: $trimmed")
                return LaunchResult.LAUNCHED_ZOOM
            } catch (e: Exception) {
                Log.w(TAG, "Failed direct URL launch into Zoom", e)
            }
        }

        // 5. If Zoom IS INSTALLED, launch the main Zoom app directly!
        if (zoomInstalled) {
            try {
                val launchIntent = pm.getLaunchIntentForPackage(ZOOM_PACKAGE_NAME)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    Log.i(TAG, "Launched native Zoom app via package manager launch intent")
                    return LaunchResult.LAUNCHED_ZOOM
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed getLaunchIntentForPackage", e)
            }
        }

        // 6. ONLY if Zoom is NOT installed on the device at all, open Google Play Store
        Log.w(TAG, "Zoom app not installed — redirecting to Google Play Store")
        return try {
            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$ZOOM_PACKAGE_NAME")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(marketIntent)
            LaunchResult.OPENED_PLAY_STORE
        } catch (e: Exception) {
            try {
                val browserPlayStore = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$ZOOM_PACKAGE_NAME")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(browserPlayStore)
                LaunchResult.OPENED_PLAY_STORE
            } catch (_: Exception) {
                LaunchResult.ERROR
            }
        }
    }

    /**
     * Backward-compatible overload.
     */
    fun launchZoomMeeting(
        context: Context,
        meetingNumber: String,
        password: String = "",
        displayName: String = "",
    ): LaunchResult = launchZoom(
        context = context,
        input = meetingNumber,
        meetingNumber = meetingNumber,
        password = password,
        displayName = displayName
    )
}
