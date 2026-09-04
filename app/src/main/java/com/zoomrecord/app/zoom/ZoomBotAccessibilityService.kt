package com.zoomrecord.app.zoom

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Autonomous Zoom Participant Bot Accessibility Service.
 *
 * Automates interactions inside Zoom and its registration flow:
 * 1. Register Dialog: Auto-clicks "Register" when "Please register to join this webinar" appears.
 * 2. Webinar Registration: Fills First Name, Last Name, Email, Country (India), Phone, and clicks "Register and Join".
 * 3. Browser-to-Zoom Redirect: Detects "Open in Zoom" / "Join Webinar" to return to Zoom instantly.
 * 4. Video Preview: Auto-clicks "Join without Video" or "Join Meeting".
 * 5. Audio Connection: Auto-clicks "Wifi or Cellular Data" / "Call over Internet".
 * 6. Popups & Consent: Auto-dismisses recording notices, disclaimers, and popups ("Got it", "Stay in meeting", "OK").
 * 7. Hide Participants: Automatically closes the participants panel upon entry.
 */
class ZoomBotAccessibilityService : AccessibilityService() {

    data class BotSessionConfig(
        val meetingNumber: String = "",
        val password: String = "",
        val displayName: String = "",
        val firstName: String = "venkateswarlu",
        val lastName: String = "marturi",
        val email: String = "venkatmarturi11@gmail.com",
        val phone: String = "8074038968",
        val country: String = "India",
        val turnOffVideo: Boolean = false,
        val dontConnectAudio: Boolean = false,
        val speakerOutputEnabled: Boolean = true,
        val armedAtMs: Long = System.currentTimeMillis(),
        val timeoutMs: Long = 10 * 60 * 1000L, // 10 minutes session lifespan
    ) {
        val isValid: Boolean
            get() = System.currentTimeMillis() - armedAtMs < timeoutMs
    }

    companion object {
        private const val TAG = "ZoomBotService"
        const val ZOOM_PACKAGE_NAME = "us.zoom.videomeetings"

        const val ACTION_BOT_STATUS = "com.zoomrecord.app.BOT_STATUS_CHANGED"
        const val EXTRA_STATUS_TEXT = "extra_status_text"
        const val EXTRA_IS_CONNECTED = "extra_is_connected"

        const val ACTION_SET_ZOOM_SPEAKER = "com.zoomrecord.app.action.SET_ZOOM_SPEAKER"
        const val EXTRA_ZOOM_SPEAKER_ON = "extra_zoom_speaker_on"

        const val ACTION_EXIT_ZOOM_MEETING = "com.zoomrecord.app.action.EXIT_ZOOM_MEETING"

        @Volatile
        var desiredSpeakerOn: Boolean? = true
            private set

        @Volatile
        var shouldExitMeeting: Boolean = false
            private set

        fun setSpeakerDesiredState(turnOn: Boolean) {
            desiredSpeakerOn = turnOn
            serviceInstance?.triggerSpeakerSync()
        }

        fun ensureWatchdogRunning() {
            shouldExitMeeting = false
            desiredSpeakerOn = true
            serviceInstance?.startWatchdog()
        }

        fun requestExitMeeting() {
            Log.i(TAG, "Exit meeting requested: bot will leave the active Zoom meeting")
            shouldExitMeeting = true
            serviceInstance?.startWatchdog()
        }

        @Volatile
        private var activeConfig: BotSessionConfig? = null

        @Volatile
        private var serviceInstance: ZoomBotAccessibilityService? = null

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        @Volatile
        var latestStatus: String = "Bot Ready"
            private set

        /**
         * Arms the bot with meeting credentials and participant identity before launching Zoom.
         */
        fun armBot(
            context: Context,
            meetingNumber: String,
            password: String = "",
            displayName: String = "",
            firstName: String = "venkateswarlu",
            lastName: String = "marturi",
            email: String = "venkatmarturi11@gmail.com",
            phone: String = "8074038968",
            country: String = "India",
            turnOffVideo: Boolean = false,
            dontConnectAudio: Boolean = false,
            speakerOutputEnabled: Boolean = true,
        ) {
            val cfg = BotSessionConfig(
                meetingNumber = meetingNumber.filter { it.isDigit() },
                password = password.trim(),
                displayName = displayName.trim().ifEmpty { "$firstName $lastName".trim() },
                firstName = firstName.trim().ifEmpty { "venkateswarlu" },
                lastName = lastName.trim().ifEmpty { "marturi" },
                email = email.trim().ifEmpty { "venkatmarturi11@gmail.com" },
                phone = phone.trim().ifEmpty { "8074038968" },
                country = country.trim().ifEmpty { "India" },
                turnOffVideo = turnOffVideo,
                dontConnectAudio = dontConnectAudio,
                speakerOutputEnabled = speakerOutputEnabled,
                armedAtMs = System.currentTimeMillis(),
            )
            activeConfig = cfg
            shouldExitMeeting = false
            desiredSpeakerOn = speakerOutputEnabled
            updateStatus(context, "🤖 Bot Armed: Auto-joining meeting $meetingNumber…", false)
            Log.i(TAG, "Zoom Bot Armed for meeting=${cfg.meetingNumber}, user=${cfg.displayName}, speaker=$speakerOutputEnabled")

            // Trigger immediate watchdog sweep
            serviceInstance?.startWatchdog()
        }

        /**
         * Clears active bot session.
         */
        fun disarmBot(context: Context? = null) {
            activeConfig = null
            serviceInstance?.stopWatchdog()
            context?.let { updateStatus(it, "Bot Idle", false) }
        }

        /**
         * Checks if the Accessibility Service is enabled in system settings.
         */
        fun isServiceEnabled(context: Context): Boolean {
            val expectedComponent = ComponentName(context, ZoomBotAccessibilityService::class.java).flattenToString()
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)
            while (colonSplitter.hasNext()) {
                val component = colonSplitter.next()
                if (component.equals(expectedComponent, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }

        /**
         * Directs user to Accessibility settings to enable this bot service.
         */
        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        private fun updateStatus(context: Context, text: String, isConnected: Boolean) {
            latestStatus = text
            try {
                val intent = Intent(ACTION_BOT_STATUS).apply {
                    setPackage(context.packageName)
                    putExtra(EXTRA_STATUS_TEXT, text)
                    putExtra(EXTRA_IS_CONNECTED, isConnected)
                }
                context.sendBroadcast(intent)
            } catch (_: Exception) {}
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // High-speed cooldown trackers (minimum intervals in milliseconds)
    private var lastPasscodeEntryTime = 0L
    private var lastNameEntryTime = 0L
    private var lastRegisterDialogClickTime = 0L
    private var lastCountryOpenTime = 0L
    private var lastCountrySelectTime = 0L
    private var lastCountryScrollTime = 0L
    private var lastRegistrationSubmitTime = 0L
    private var lastRedirectClickTime = 0L
    private var lastJoinClickTime = 0L
    private var lastAudioJoinClickTime = 0L
    private var lastPopupDismissTime = 0L
    private var lastHideParticipantsTime = 0L

    private var hasJoinedAudio = false
    private var isInMeetingRoom = false
    private var lastSpeakerClickTime = 0L
    private var lastToolbarWakeTime = 0L
    private var lastLeaveButtonClickTime = 0L
    private var lastLeaveConfirmClickTime = 0L
    private var lastLeaveWakeToolbarTime = 0L

    private val zoomSpeakerReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SET_ZOOM_SPEAKER -> {
                    val turnOn = intent.getBooleanExtra(EXTRA_ZOOM_SPEAKER_ON, false)
                    desiredSpeakerOn = turnOn
                    triggerSpeakerSync()
                }
                ACTION_EXIT_ZOOM_MEETING -> {
                    requestExitMeeting()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInstance = this
        isServiceRunning = true
        Log.i(TAG, "ZoomBotAccessibilityService connected and ready")
        updateStatus(applicationContext, "🤖 Zoom Bot Active", false)

        try {
            val filter = IntentFilter().apply {
                addAction(ACTION_SET_ZOOM_SPEAKER)
                addAction(ACTION_EXIT_ZOOM_MEETING)
            }
            registerReceiver(zoomSpeakerReceiver, filter, RECEIVER_NOT_EXPORTED)
        } catch (_: Exception) {}

        if (activeConfig?.isValid == true) {
            startWatchdog()
        }
    }

    /**
     * Periodic watchdog runnable to guarantee actions fire within 350ms even if
     * accessibility events are dropped, delayed, or suppressed by the OS.
     */
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!isServiceRunning) return
            val hasActiveConfig = activeConfig?.isValid == true
            val isRecording = com.zoomrecord.app.recording.ScreenRecordService.isRunning
            val isExiting = shouldExitMeeting
            if (hasActiveConfig || isRecording || isExiting) {
                try {
                    processActiveWindow()
                } catch (e: Exception) {
                    Log.w(TAG, "Watchdog error processing active window", e)
                }
                // High frequency polling (350ms) while connecting or exiting; low frequency (1200ms) once live in meeting
                val nextDelay = if (isExiting) 350L else if (isInMeetingRoom) 1200L else 350L
                mainHandler.postDelayed(this, nextDelay)
            }
        }
    }

    fun startWatchdog() {
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.post(watchdogRunnable)
    }

    private fun stopWatchdog() {
        mainHandler.removeCallbacks(watchdogRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val hasActiveConfig = activeConfig?.isValid == true
        val isRecording = com.zoomrecord.app.recording.ScreenRecordService.isRunning
        val isExiting = shouldExitMeeting
        if (!hasActiveConfig && !isRecording && !isExiting) return

        val pkg = event?.packageName?.toString() ?: return

        // NEVER inspect or click anything inside our own app!
        if (pkg == packageName || pkg == "com.zoomrecord.app") {
            return
        }

        // Once in the live meeting room, only monitor Zoom and system dialogs
        if (isInMeetingRoom && pkg != ZOOM_PACKAGE_NAME && pkg != "android" && !pkg.contains("systemui")) {
            return
        }

        val eventType = event.eventType
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) {
            // Ultra-responsive debounce: 20ms for window changes, 40ms for view updates
            val delay = if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) 20L else 40L
            mainHandler.removeCallbacks(inspectRunnable)
            mainHandler.postDelayed(inspectRunnable, delay)
        }
    }

    private val inspectRunnable = Runnable {
        try {
            processActiveWindow()
        } catch (e: Exception) {
            Log.w(TAG, "Error processing active window", e)
        }
    }

    private fun processActiveWindow() {
        val config = activeConfig ?: if (com.zoomrecord.app.recording.ScreenRecordService.isRunning || shouldExitMeeting) {
            BotSessionConfig(
                meetingNumber = "",
                displayName = "Participant",
                turnOffVideo = true,
                speakerOutputEnabled = com.zoomrecord.app.recording.ScreenRecordService.isSpeakerOutputActive,
                armedAtMs = System.currentTimeMillis()
            )
        } else return
        if (!config.isValid) return

        val rootNode = rootInActiveWindow ?: return
        val rootPkg = rootNode.packageName?.toString() ?: ""

        // NEVER inspect or click anything inside our own app!
        if (rootPkg == packageName || rootPkg == "com.zoomrecord.app") {
            return
        }

        try {
            val allNodes = mutableListOf<AccessibilityNodeInfo>()
            collectAllNodes(rootNode, allNodes, maxDepth = 15)

            // ── STEP 0: Exit Meeting upon Recording Stop ──────────────────
            if (shouldExitMeeting) {
                if (handleExitMeeting(allNodes)) return
            }

            // ── STEP 1: Passcode Form Entry ──────────────────────────────
            if (handlePasscodePrompt(allNodes, config)) return

            // ── STEP 2: Display Name Entry ───────────────────────────────
            if (handleDisplayNamePrompt(allNodes, config)) return

            // ── STEP 3: "Register to Join" Dialog (Image 1) ───────────────
            if (handleRegisterToJoinDialog(allNodes)) return

            // ── STEP 4: Webinar Registration Form (Image 2) ──────────────
            if (handleRegistrationForm(allNodes, config)) return

            // ── STEP 4B: Browser-to-Zoom App Redirect ────────────────────
            if (handlePostRegistrationRedirect(allNodes)) return

            // ── STEP 5: Pre-Join Preview Screen ("Join without Video") ───
            if (handlePreJoinPreview(allNodes, config)) return

            // ── STEP 6: Audio Connection ("Wifi or Cellular Data") ───────
            if (handleAudioConnection(allNodes)) return

            // ── STEP 7: Popups & Consent Modals ("Got it", etc.) ─────────
            if (handleConsentAndPopups(allNodes)) return

            // ── STEP 8: Check if in Live Meeting Room ────────────────────
            checkInMeetingStatus(allNodes)

            // ── STEP 9: Auto-Mute Mic and Turn Off Video in Meeting ──────
            if (isInMeetingRoom) handleMuteMicAndVideo(allNodes)

            // ── STEP 10: Hide Participants Panel & Swipe Floating Video ──
            if (isInMeetingRoom) {
                handleHideParticipantsPanel(allNodes)
                handleSwipeAsideFloatingVideo(allNodes)
            }

        } finally {
            // Cleanup handled by GC; nodes are collected
        }
    }

    // ── STEP 1: Passcode Dialog ───────────────────────────────────────

    private fun handlePasscodePrompt(
        nodes: List<AccessibilityNodeInfo>,
        config: BotSessionConfig,
    ): Boolean {
        if (config.password.isBlank()) return false
        val now = System.currentTimeMillis()
        if (now - lastPasscodeEntryTime < 700) return false

        val hasPasscodeLabel = nodes.any { node ->
            val text = (node.text?.toString() ?: "").lowercase()
            val desc = (node.contentDescription?.toString() ?: "").lowercase()
            text.contains("passcode") || text.contains("password") ||
            desc.contains("passcode") || desc.contains("password")
        }

        if (!hasPasscodeLabel) return false

        val pwdField = nodes.firstOrNull { node ->
            node.isEditable && (
                node.className?.toString()?.contains("EditText") == true ||
                (node.text?.toString() ?: "").lowercase().contains("passcode") ||
                (node.hintText?.toString() ?: "").lowercase().contains("passcode") ||
                (node.viewIdResourceName ?: "").lowercase().contains("password") ||
                (node.viewIdResourceName ?: "").lowercase().contains("passcode") ||
                (node.viewIdResourceName ?: "").lowercase().contains("edtpassword")
            )
        } ?: nodes.firstOrNull { it.isEditable }

        if (pwdField != null) {
            val currentVal = pwdField.text?.toString() ?: ""
            if (currentVal != config.password) {
                setTextOnNode(pwdField, config.password)
                lastPasscodeEntryTime = now
                updateStatus(applicationContext, "🤖 Auto-filling passcode…", false)
                Log.i(TAG, "Auto-filled meeting passcode into Zoom dialog")
            }

            val okBtn = findButtonByTexts(nodes, listOf("ok", "continue", "join", "confirm"))
            if (okBtn != null) {
                clickNode(okBtn)
                Log.i(TAG, "Clicked OK on Passcode dialog")
                return true
            }
        }
        return false
    }

    // ── STEP 2: Display Name Dialog ───────────────────────────────────

    private fun handleDisplayNamePrompt(
        nodes: List<AccessibilityNodeInfo>,
        config: BotSessionConfig,
    ): Boolean {
        if (config.displayName.isBlank()) return false
        val now = System.currentTimeMillis()
        if (now - lastNameEntryTime < 700) return false

        val hasNamePrompt = nodes.any { node ->
            val text = (node.text?.toString() ?: "").lowercase()
            text.contains("enter your name") || text.contains("your name") || text.contains("screen name")
        }

        if (!hasNamePrompt) return false

        val nameField = nodes.firstOrNull { node ->
            node.isEditable && (
                node.className?.toString()?.contains("EditText") == true ||
                (node.text?.toString() ?: "").lowercase().contains("name") ||
                (node.hintText?.toString() ?: "").lowercase().contains("name") ||
                (node.viewIdResourceName ?: "").lowercase().contains("name") ||
                (node.viewIdResourceName ?: "").lowercase().contains("screenname")
            )
        } ?: nodes.firstOrNull { it.isEditable }

        if (nameField != null) {
            val currentVal = nameField.text?.toString() ?: ""
            if (currentVal != config.displayName) {
                setTextOnNode(nameField, config.displayName)
                lastNameEntryTime = now
                updateStatus(applicationContext, "🤖 Setting name: ${config.displayName}…", false)
                Log.i(TAG, "Auto-filled display name '${config.displayName}'")
            }

            val okBtn = findButtonByTexts(nodes, listOf("ok", "continue", "join", "confirm"))
            if (okBtn != null) {
                clickNode(okBtn)
                Log.i(TAG, "Clicked OK on Name dialog")
                return true
            }
        }
        return false
    }

    // ── STEP 3: "Register to Join" Dialog (Image 1) ───────────────────

    /**
     * Handles the initial "Please register to join this webinar" dialog
     * by clicking the "Register" button to open the registration form.
     */
    private fun handleRegisterToJoinDialog(
        nodes: List<AccessibilityNodeInfo>,
    ): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastRegisterDialogClickTime < 800) return false

        val hasRegisterPrompt = nodes.any { node ->
            val txt = (node.text?.toString() ?: "").lowercase()
            txt.contains("register to join") ||
            txt.contains("please register") ||
            txt.contains("registration is required")
        }

        if (!hasRegisterPrompt) return false

        // Guard: ensure we are NOT already on the full registration form
        val isFullForm = nodes.any { node ->
            val txt = (node.text?.toString() ?: "").lowercase()
            txt.contains("webinar registration") || txt.contains("email address") ||
            txt.contains("register and join")
        }
        if (isFullForm) return false

        // Target only the "Register" button (NOT "Cancel" and NOT "Register and Join")
        val registerBtn = nodes.firstOrNull { node ->
            val txt = (node.text?.toString() ?: "").trim().lowercase()
            val desc = (node.contentDescription?.toString() ?: "").trim().lowercase()
            (txt == "register" || desc == "register") &&
            !txt.contains("cancel") && !txt.contains("and join")
        } ?: findButtonByTexts(nodes, listOf("register"))

        if (registerBtn != null) {
            lastRegisterDialogClickTime = now
            updateStatus(applicationContext, "🤖 Clicking Register on prompt…", false)
            clickNode(registerBtn)
            Log.i(TAG, "Auto-clicked 'Register' on webinar registration prompt")
            return true
        }

        return false
    }

    // ── STEP 4: Webinar Registration Form (Image 2) ───────────────────

    /**
     * Handles the complete webinar registration form:
     * - Fills First Name, Last Name, Email, Phone
     * - Selects Country/Region: "India"
     * - Clicks "Register and Join"
     */
    private fun handleRegistrationForm(
        nodes: List<AccessibilityNodeInfo>,
        config: BotSessionConfig,
    ): Boolean {
        // First check if country dropdown picker list is currently open
        if (handleCountryListSelection(nodes, config.country)) return true

        val isRegistration = nodes.any { node ->
            val txt = (node.text?.toString() ?: "").lowercase()
            txt.contains("webinar registration") || txt.contains("meeting registration") ||
            txt.contains("register for") || txt.contains("first name") ||
            txt.contains("register and join") || txt.contains("registration form") ||
            txt.contains("email address") || txt.contains("country/region")
        }

        if (!isRegistration) return false

        var filledAny = false
        val editables = nodes.filter { it.isEditable }
        // Filter out country/region fields from text typing (Country is a selection option box, NOT a text input!)
        val textEditables = editables.filter { field ->
            val hint = (field.hintText?.toString() ?: "").lowercase()
            val id = (field.viewIdResourceName ?: "").lowercase()
            val desc = (field.contentDescription?.toString() ?: "").lowercase()
            val nearby = findNearbyLabelText(nodes, field).lowercase()
            val context = "$hint $id $desc $nearby"
            !context.contains("country") && !context.contains("region")
        }
        val now = System.currentTimeMillis()

        // ── Fill text editable fields (First Name, Last Name, Email, Phone) ────
        for (field in textEditables) {
            val hint = buildString {
                append((field.hintText?.toString() ?: "").lowercase())
                append(" ")
                append((field.viewIdResourceName ?: "").lowercase())
                append(" ")
                append((field.contentDescription?.toString() ?: "").lowercase())
            }

            val placeholderText = (field.text?.toString() ?: "").lowercase()
            val nearbyLabel = findNearbyLabelText(nodes, field).lowercase()
            val combinedContext = "$hint $nearbyLabel $placeholderText"
            val currentText = (field.text?.toString() ?: "").trim()

            when {
                isFirstNameField(combinedContext) -> {
                    if (config.firstName.isNotBlank() && !currentText.equals(config.firstName, ignoreCase = true)) {
                        setTextOnNode(field, config.firstName)
                        filledAny = true
                        Log.i(TAG, "Filled First Name: ${config.firstName}")
                    }
                }
                isLastNameField(combinedContext) -> {
                    if (config.lastName.isNotBlank() && !currentText.equals(config.lastName, ignoreCase = true)) {
                        setTextOnNode(field, config.lastName)
                        filledAny = true
                        Log.i(TAG, "Filled Last Name: ${config.lastName}")
                    }
                }
                isEmailField(combinedContext) -> {
                    if (config.email.isNotBlank() && !currentText.equals(config.email, ignoreCase = true)) {
                        setTextOnNode(field, config.email)
                        filledAny = true
                        Log.i(TAG, "Filled Email: ${config.email}")
                    }
                }
                isPhoneField(combinedContext) -> {
                    if (config.phone.isNotBlank() && !currentText.equals(config.phone, ignoreCase = true)) {
                        setTextOnNode(field, config.phone)
                        filledAny = true
                        Log.i(TAG, "Filled Phone: ${config.phone}")
                    }
                }
            }
        }

        // ── Positional fallback for text fields lacking explicit hints ───
        if (textEditables.size >= 4) {
            val fieldValues = listOf(config.firstName, config.lastName, config.email, config.phone)
            for (i in 0 until minOf(textEditables.size, fieldValues.size)) {
                val currentText = (textEditables[i].text?.toString() ?: "").trim()
                if (currentText.isEmpty() || isPlaceholderValue(currentText)) {
                    setTextOnNode(textEditables[i], fieldValues[i])
                    filledAny = true
                    Log.i(TAG, "Filled field $i (positional) with ${fieldValues[i]}")
                }
            }
        }

        // ── Check if Country is ALREADY set to India ──────────────────
        val isIndiaAlreadySelected = nodes.any { node ->
            val txt = (node.text?.toString() ?: "").trim().lowercase()
            val desc = (node.contentDescription?.toString() ?: "").trim().lowercase()
            (txt == "india" || txt.startsWith("india ") || txt.startsWith("india(") || txt.contains("+91") ||
             desc == "india" || desc.startsWith("india ") || desc.startsWith("india(") || desc.contains("+91")) &&
            !txt.contains("select") && !desc.contains("select")
        }

        // ── Open Country/Region selection option box if not yet India ─
        val selectCountryNode = if (!isIndiaAlreadySelected) {
            nodes.firstOrNull { node ->
                val txt = (node.text?.toString() ?: "").trim().lowercase()
                val desc = (node.contentDescription?.toString() ?: "").trim().lowercase()
                val id = (node.viewIdResourceName ?: "").lowercase()
                val cls = (node.className?.toString() ?: "").lowercase()
                val nearby = findNearbyLabelText(nodes, node).lowercase()

                (cls.contains("spinner") || id.contains("country") || id.contains("region") ||
                 txt.contains("country") || desc.contains("country") || nearby.contains("country") ||
                 txt.contains("select") || desc.contains("select") || txt.contains("choose")) &&
                (node.isClickable || node.parent?.isClickable == true) &&
                !txt.contains("register") && !desc.contains("register")
            }
        } else null

        if (!isIndiaAlreadySelected && selectCountryNode != null && now - lastCountryOpenTime > 700) {
            lastCountryOpenTime = now
            updateStatus(applicationContext, "🤖 Opening Country selection option box…", false)
            clickNode(selectCountryNode)
            Log.i(TAG, "Clicked Country/Region selection option box to choose ${config.country}")
            return true
        }

        // ── Click "Register and Join" button ──────────────────────────
        val submitBtn = nodes.firstOrNull { node ->
            val txt = (node.text?.toString() ?: "").trim().lowercase()
            val desc = (node.contentDescription?.toString() ?: "").trim().lowercase()
            (txt.contains("register and join") || desc.contains("register and join") ||
             txt == "submit registration" || desc == "submit registration" ||
             txt == "register" || desc == "register" ||
             txt.contains("register") || desc.contains("register")) &&
            !txt.contains("webinar registration") && !desc.contains("webinar registration")
        } ?: findButtonByTexts(nodes, listOf("register and join", "register & join", "join webinar", "submit", "register"))

        // Submit when all details are ready
        if (submitBtn != null && (isIndiaAlreadySelected || selectCountryNode == null) && now - lastRegistrationSubmitTime > 1000) {
            lastRegistrationSubmitTime = now
            updateStatus(applicationContext, "🤖 Submitting registration…", false)
            clickNode(submitBtn)
            Log.i(TAG, "Clicked 'Register and Join' submit button")
            return true
        }

        return filledAny
    }

    // ── STEP 4B: Post-Registration Browser to Zoom Redirect ───────────

    /**
     * Detects browser or confirmation page prompts after registration submission:
     * - "Open with Zoom?" / "Open in Zoom" dialogs
     * - "Join Webinar" / "Click here to join" buttons on confirmation pages
     */
    private fun handlePostRegistrationRedirect(nodes: List<AccessibilityNodeInfo>): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastRedirectClickTime < 800) return false

        // A. System or browser "Open in Zoom" prompt
        val hasOpenInZoomPrompt = nodes.any { node ->
            val txt = (node.text?.toString() ?: "").lowercase()
            txt.contains("open in zoom") || txt.contains("open with zoom") ||
            txt.contains("open this link in") || txt.contains("open in the zoom app")
        }

        if (hasOpenInZoomPrompt) {
            val openBtn = nodes.firstOrNull { node ->
                val txt = (node.text?.toString() ?: "").trim().lowercase()
                val desc = (node.contentDescription?.toString() ?: "").trim().lowercase()
                (txt == "open" || desc == "open" || txt == "always" || desc == "always" ||
                 txt == "just once" || desc == "just once") &&
                (node.isClickable || node.parent?.isClickable == true)
            }
            if (openBtn != null) {
                lastRedirectClickTime = now
                updateStatus(applicationContext, "🤖 Redirecting to Zoom app…", false)
                clickNode(openBtn)
                Log.i(TAG, "Auto-clicked '${openBtn.text ?: openBtn.contentDescription}' on Open with Zoom dialog")
                return true
            }
        }

        // B. Webinar registration confirmed landing page
        val hasConfirmedPage = nodes.any { node ->
            val txt = (node.text?.toString() ?: "").lowercase()
            txt.contains("registration approved") || txt.contains("registration is approved") ||
            txt.contains("registration successful") || txt.contains("click here to join") ||
            txt.contains("you are registered")
        }

        if (hasConfirmedPage) {
            val joinBtn = findButtonByTexts(
                nodes,
                listOf("join webinar", "join meeting", "launch meeting", "click here to join", "open zoom")
            )
            if (joinBtn != null) {
                lastRedirectClickTime = now
                updateStatus(applicationContext, "🤖 Launching Zoom from registration confirmation…", false)
                clickNode(joinBtn)
                Log.i(TAG, "Auto-clicked '${joinBtn.text}' on webinar confirmation page")
                return true
            }
        }

        return false
    }

    // ── STEP 5: Pre-Join Video Preview Screen ─────────────────────────

    private fun handlePreJoinPreview(
        nodes: List<AccessibilityNodeInfo>,
        config: BotSessionConfig,
    ): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastJoinClickTime < 600) return false

        // Prefer "Join without Video" for silent, lightweight participant join
        val joinWithoutVideoBtn = findButtonByExactOrContainsText(
            nodes,
            listOf("join without video", "join without audio and video")
        )

        if (joinWithoutVideoBtn != null) {
            lastJoinClickTime = now
            updateStatus(applicationContext, "🤖 Joining meeting…", false)
            clickNode(joinWithoutVideoBtn)
            Log.i(TAG, "Auto-clicked 'Join without Video'")
            return true
        }

        // Standard "Join" button on pre-join preview
        val joinMeetingBtn = nodes.firstOrNull { node ->
            val txt = (node.text?.toString() ?: "").trim().lowercase()
            val desc = (node.contentDescription?.toString() ?: "").trim().lowercase()
            val id = (node.viewIdResourceName ?: "").lowercase()

            (txt == "join" || txt == "join meeting" || desc == "join" || desc == "join meeting" ||
             id.contains("btn_join") || id.contains("join_meeting")) &&
            !txt.contains("join audio") && !desc.contains("join audio")
        }

        if (joinMeetingBtn != null) {
            // Always ensure video is turned off before entering
            val videoSwitch = findSwitchByText(nodes, listOf("turn off my video", "always turn off my video", "turn off video"))
            if (videoSwitch != null && !videoSwitch.isChecked) {
                clickNode(videoSwitch)
                Log.i(TAG, "Toggled 'Turn off my video' ON")
            }

            // Always ensure microphone is muted before entering
            val muteMicSwitch = findSwitchByText(nodes, listOf("mute my microphone", "turn off my microphone", "mute mic", "do not connect audio", "don't connect to audio"))
            if (muteMicSwitch != null && !muteMicSwitch.isChecked) {
                clickNode(muteMicSwitch)
                Log.i(TAG, "Toggled 'Mute my microphone' ON")
            }

            lastJoinClickTime = now
            updateStatus(applicationContext, "🤖 Tapping Join Meeting…", false)
            clickNode(joinMeetingBtn)
            Log.i(TAG, "Auto-clicked 'Join Meeting' preview button")
            return true
        }

        return false
    }

    // ── STEP 6: Auto Join Audio ───────────────────────────────────────

    private fun handleAudioConnection(nodes: List<AccessibilityNodeInfo>): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastAudioJoinClickTime < 500) return false

        // 1. Direct "Wifi or Cellular Data" option dialog / bottom-sheet
        val wifiOrCellularBtn = nodes.firstOrNull { node ->
            val txt = (node.text?.toString() ?: "").lowercase()
            val desc = (node.contentDescription?.toString() ?: "").lowercase()
            txt.contains("wifi or cellular data") || txt.contains("wifi or cellular") ||
            txt.contains("call over internet") || txt.contains("device audio") ||
            (txt.contains("wifi") && txt.contains("cellular")) ||
            desc.contains("wifi or cellular data") || desc.contains("call over internet") ||
            desc.contains("device audio")
        }

        if (wifiOrCellularBtn != null) {
            lastAudioJoinClickTime = now
            hasJoinedAudio = true
            updateStatus(applicationContext, "🤖 Audio Connected (Wifi/Cellular)", true)
            clickNode(wifiOrCellularBtn)
            Log.i(TAG, "Auto-clicked 'Wifi or Cellular Data' audio connect option")
            return true
        }

        // 2. Toolbar "Join Audio" button
        val joinAudioToolbarBtn = nodes.firstOrNull { node ->
            val txt = (node.text?.toString() ?: "").trim().lowercase()
            val desc = (node.contentDescription?.toString() ?: "").trim().lowercase()
            val id = (node.viewIdResourceName ?: "").lowercase()

            (txt == "join audio" || desc == "join audio" || desc.contains("join audio") ||
             id.contains("joinaudio")) &&
            !txt.contains("mute") && !desc.contains("mute") && !desc.contains("unmute")
        }

        if (joinAudioToolbarBtn != null && !hasJoinedAudio) {
            lastAudioJoinClickTime = now
            updateStatus(applicationContext, "🤖 Tapping Join Audio…", false)
            clickNode(joinAudioToolbarBtn)
            Log.i(TAG, "Auto-clicked 'Join Audio' toolbar button")
            return true
        }

        return false
    }

    // ── STEP 7: Popups, Consent Modals & Disclaimers ──────────────────

    private fun handleConsentAndPopups(nodes: List<AccessibilityNodeInfo>): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastPopupDismissTime < 500) return false

        // 0. Safety Guard: If Zoom displays an accidental "Do you want to leave this meeting?" dialog,
        // but recording is active (shouldExitMeeting is false), immediately click Cancel/Stay!
        if (!shouldExitMeeting) {
            val isLeavePrompt = nodes.any { node ->
                val txt = (node.text?.toString() ?: "").lowercase()
                txt.contains("do you want to leave") || txt.contains("leave meeting?") ||
                txt.contains("are you sure you want to leave") || txt.contains("leave this meeting")
            }
            if (isLeavePrompt) {
                val stayBtn = findButtonByExactOrContainsText(
                    nodes,
                    listOf("cancel", "stay in meeting", "stay", "no", "dismiss")
                )
                if (stayBtn != null) {
                    lastPopupDismissTime = now
                    updateStatus(applicationContext, "🤖 Staying in meeting", isInMeetingRoom)
                    clickNode(stayBtn)
                    Log.i(TAG, "Auto-cancelled accidental leave meeting dialog via '${stayBtn.text}'")
                    return true
                }
            }
        }

        // "Got it", "Stay in meeting", "I agree", etc.
        val gotItBtn = findButtonByExactOrContainsText(
            nodes,
            listOf("got it", "stay in meeting", "i agree", "agree", "accept all", "accept", "understood", "continue")
        )

        if (gotItBtn != null) {
            lastPopupDismissTime = now
            updateStatus(applicationContext, "🤖 Auto-dismissed popup", isInMeetingRoom)
            clickNode(gotItBtn)
            Log.i(TAG, "Auto-dismissed consent/disclaimer modal via '${gotItBtn.text}'")
            return true
        }

        // 1. Microphone Access Warning Modal (Image from user)
        val micNoticeNode = nodes.firstOrNull { node ->
            val txt = (node.text?.toString() ?: "").lowercase()
            txt.contains("unable to access microphone") ||
            txt.contains("access your microphone") ||
            txt.contains("microphone from device settings") ||
            txt.contains("cannot access microphone") ||
            txt.contains("can't access microphone") ||
            txt.contains("microphone permission") ||
            txt.contains("allow zoom to access your microphone") ||
            txt.contains("allow zoom workplace to access your microphone")
        }

        if (micNoticeNode != null) {
            // Check for dismissive buttons (strictly avoid "settings" so we never leave Zoom)
            val dismissBtn = findButtonByExactOrContainsText(
                nodes,
                listOf("ok", "got it", "cancel", "dismiss", "close", "continue", "not now", "deny", "ignore")
            )
            if (dismissBtn != null) {
                lastPopupDismissTime = now
                updateStatus(applicationContext, "🤖 Dismissed microphone warning", isInMeetingRoom)
                clickNode(dismissBtn)
                Log.i(TAG, "Auto-dismissed microphone warning via '${dismissBtn.text}'")
                return true
            } else {
                // If the dialog has no buttons (as in Zoom's centered floating card):
                lastPopupDismissTime = now

                // A. Try clicking the notice node directly
                clickNode(micNoticeNode)

                // B. Tap outside the modal card (top blank area) to trigger touch-outside dismiss without pressing BACK
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val displayMetrics = resources.displayMetrics
                    val tapX = displayMetrics.widthPixels * 0.5f
                    val tapY = displayMetrics.heightPixels * 0.08f // top edge well outside modal card
                    val path = Path().apply {
                        moveTo(tapX, tapY)
                        lineTo(tapX, tapY)
                    }
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                        .build()
                    dispatchGesture(gesture, null, null)
                }

                Log.i(TAG, "Auto-dismissed microphone warning dialog via click/tap-outside (never BACK)")
                return true
            }
        }

        // 2. Generic notice dialogs (Recording notices, etc.)
        val hasNoticeDialog = nodes.any { node ->
            val txt = (node.text?.toString() ?: "").lowercase()
            txt.contains("this meeting is being recorded") ||
            txt.contains("recording has started") ||
            txt.contains("meeting is being live streamed") ||
            txt.contains("audio is muted by host") ||
            txt.contains("host has muted") ||
            txt.contains("muted by host") ||
            txt.contains("host muted you") ||
            txt.contains("muted by the host") ||
            txt.contains("you are muted") ||
            txt.contains("cannot start your video") ||
            txt.contains("host has disabled") ||
            txt.contains("host has stopped your video") ||
            txt.contains("privacy statement") ||
            txt.contains("not allowing participants to unmute") ||
            txt.contains("unmute themselves") ||
            txt.contains("host is not allowing") ||
            txt.contains("transcribed") ||
            txt.contains("transcription")
        }

        if (hasNoticeDialog) {
            val okBtn = findButtonByExactOrContainsText(nodes, listOf("ok", "continue", "dismiss", "close", "i understand", "got it"))
            if (okBtn != null) {
                lastPopupDismissTime = now
                updateStatus(applicationContext, "🤖 Dismissed notice dialog", isInMeetingRoom)
                clickNode(okBtn)
                Log.i(TAG, "Auto-dismissed notice modal with '${okBtn.text}'")
                return true
            }
            // Note: Never call GLOBAL_ACTION_BACK on generic notices to avoid accidentally exiting active meeting
        }

        return false
    }

    // ── STEP 8: Check In-Meeting Status ───────────────────────────────

    private fun checkInMeetingStatus(nodes: List<AccessibilityNodeInfo>) {
        val hasInMeetingControls = nodes.any { node ->
            val txt = (node.text?.toString() ?: "").trim().lowercase()
            val desc = (node.contentDescription?.toString() ?: "").trim().lowercase()
            txt == "leave" || txt == "end" || desc == "leave meeting" ||
            txt == "participants" || desc.contains("mute") || desc.contains("unmute") ||
            txt == "more" || desc == "more"
        }

        if (hasInMeetingControls && !isInMeetingRoom) {
            isInMeetingRoom = true
            Log.i(TAG, "Detected in active meeting room!")
            updateStatus(applicationContext, "🤖 In Meeting (Live & Recording)", true)

            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                if (am != null) {
                    val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                    val currentVol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                    if (currentVol == 0) {
                        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, maxVol / 2, 0)
                    }
                    Log.i(TAG, "MEETING ENTRY: Ensured speaker volume is audible for recording")
                }
            } catch (_: Exception) {}
        }

        // Synchronize Zoom loudspeaker button if desired state is specified
        val targetSpeakerOn = desiredSpeakerOn ?: activeConfig?.speakerOutputEnabled
        if (targetSpeakerOn != null) {
            syncZoomSpeakerState(nodes, targetSpeakerOn)
        }

        // Waiting room detection
        val isWaitingRoom = nodes.any { node ->
            val txt = (node.text?.toString() ?: "").lowercase()
            txt.contains("please wait, the meeting host will let you in soon") ||
            txt.contains("waiting for the host to start this meeting") ||
            txt.contains("waiting room")
        }

        if (isWaitingRoom) {
            updateStatus(applicationContext, "🤖 In Waiting Room (Host will admit soon)…", false)
        }
    }

    // ── STEP 9: Hide Participants Panel ───────────────────────────────

    /**
     * If the Participants list is showing upon joining, close it immediately.
     */
    private fun handleHideParticipantsPanel(nodes: List<AccessibilityNodeInfo>): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastHideParticipantsTime < 1000) return false

        val isParticipantsShowing = nodes.any { node ->
            val txt = (node.text?.toString() ?: "").lowercase()
            val desc = (node.contentDescription?.toString() ?: "").lowercase()
            (txt.startsWith("participants") && (txt.contains("(") || txt.length < 25)) ||
            desc.contains("participants list") || txt.contains("participants (") ||
            txt.contains("(host)") || txt.contains("(me)") || desc.contains("(host)")
        }

        if (!isParticipantsShowing) return false

        // Close / Back button on the participants panel
        val closeBtn = nodes.firstOrNull { node ->
            val txt = (node.text?.toString() ?: "").trim().lowercase()
            val desc = (node.contentDescription?.toString() ?: "").trim().lowercase()
            val id = (node.viewIdResourceName ?: "").lowercase()

            (txt == "close" || desc == "close" || txt == "back" || desc == "back" ||
             desc == "navigate up" || id.contains("btn_close") || id.contains("btn_back") ||
             desc.contains("close participants") || desc.contains("hide participants"))
        }

        if (closeBtn != null) {
            lastHideParticipantsTime = now
            clickNode(closeBtn)
            Log.i(TAG, "Closed participants panel via '${closeBtn.text ?: closeBtn.contentDescription}'")
            updateStatus(applicationContext, "🤖 In Meeting (Live & Recording)", true)
            return true
        }

        // Fallback: Toggle participants button on toolbar
        val participantsToolbarBtn = nodes.firstOrNull { node ->
            val txt = (node.text?.toString() ?: "").trim().lowercase()
            val desc = (node.contentDescription?.toString() ?: "").trim().lowercase()
            (txt == "participants" || desc == "participants") && (node.isClickable || node.parent?.isClickable == true)
        }

        if (participantsToolbarBtn != null) {
            lastHideParticipantsTime = now
            clickNode(participantsToolbarBtn)
            Log.i(TAG, "Toggled participants off via toolbar button")
            return true
        }

        return false
    }

    private var lastMuteActionTime = 0L

    /**
     * Ensures mic is muted and video is stopped while in the meeting.
     */
    private fun handleMuteMicAndVideo(nodes: List<AccessibilityNodeInfo>): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastMuteActionTime < 1500) return false

        // 1. Turn off Video / Camera if currently active
        val stopVideoBtn = nodes.firstOrNull { node ->
            val desc = (node.contentDescription?.toString() ?: "").lowercase()
            val txt = (node.text?.toString() ?: "").lowercase()
            val id = (node.viewIdResourceName ?: "").lowercase()
            (desc.contains("stop video") || desc.contains("stop my video") ||
             txt.contains("stop video") || id.contains("btn_stop_video")) &&
            (node.isClickable || node.parent?.isClickable == true)
        }

        if (stopVideoBtn != null) {
            lastMuteActionTime = now
            clickNode(stopVideoBtn)
            Log.i(TAG, "Auto-turned off camera via 'Stop Video'")
            updateStatus(applicationContext, "🤖 Camera turned OFF", true)
            return true
        }

        // 2. Mute Microphone if currently unmuted
        val muteMicBtn = nodes.firstOrNull { node ->
            val desc = (node.contentDescription?.toString() ?: "").lowercase()
            val txt = (node.text?.toString() ?: "").lowercase()
            val id = (node.viewIdResourceName ?: "").lowercase()
            (desc == "mute my audio" || desc == "mute audio" || desc.startsWith("mute my") ||
             (txt == "mute" && !desc.contains("unmute")) ||
             (id.contains("btn_mute") && !desc.contains("unmute"))) &&
            !desc.contains("unmute") && !txt.contains("unmute") &&
            (node.isClickable || node.parent?.isClickable == true)
        }

        if (muteMicBtn != null) {
            lastMuteActionTime = now
            clickNode(muteMicBtn)
            Log.i(TAG, "Auto-muted microphone via 'Mute'")
            updateStatus(applicationContext, "🤖 Microphone MUTED", true)
            return true
        }

        return false
    }

    private var hasSwipedFloatingVideo = false
    private var lastSwipeTime = 0L

    /**
     * Swipes the floating participant video thumbnail aside to the right edge to prevent obscuring meeting content.
     */
    private fun handleSwipeAsideFloatingVideo(nodes: List<AccessibilityNodeInfo>): Boolean {
        if (hasSwipedFloatingVideo) return false
        val now = System.currentTimeMillis()
        if (now - lastSwipeTime < 3000) return false

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.toFloat()
        val screenHeight = displayMetrics.heightPixels.toFloat()

        // Look for floating participant video tile in the top right
        val floatingTile = nodes.firstOrNull { node ->
            val id = (node.viewIdResourceName ?: "").lowercase()
            val desc = (node.contentDescription?.toString() ?: "").lowercase()
            val txt = (node.text?.toString() ?: "").lowercase()
            id.contains("small_video") || id.contains("pip") || id.contains("thumbnail") ||
            id.contains("mini_video") || desc.contains("video thumbnail") ||
            (txt.isNotBlank() && txt.contains((activeConfig?.firstName?.lowercase() ?: "venkateswarlu")))
        }

        val rect = android.graphics.Rect()
        if (floatingTile != null) {
            floatingTile.getBoundsInScreen(rect)
        }

        val startX = if (rect.width() > 0 && rect.right > screenWidth * 0.5f) {
            rect.centerX().toFloat()
        } else {
            screenWidth * 0.85f
        }
        val startY = if (rect.height() > 0 && rect.bottom < screenHeight * 0.5f) {
            rect.centerY().toFloat()
        } else {
            screenHeight * 0.18f
        }
        val endX = screenWidth - 2f
        val endY = startY

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = android.graphics.Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 200))
                .build()

            lastSwipeTime = now
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    hasSwipedFloatingVideo = true
                    Log.i(TAG, "Swiped floating participant window aside to the right edge")
                }
            }, null)
            return true
        }
        return false
    }

    // ── Country Selection Helpers ─────────────────────────────────────

    fun triggerSpeakerSync() {
        val desired = desiredSpeakerOn ?: return
        mainHandler.post {
            val root = rootInActiveWindow ?: return@post
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            collectAllNodes(root, nodes, maxDepth = 15)
            syncZoomSpeakerState(nodes, desired)
        }
    }

    private fun syncZoomSpeakerState(nodes: List<AccessibilityNodeInfo>, desiredOn: Boolean): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastSpeakerClickTime < 1000) return false

        // Also ensure speakerphone volume is audible at system level
        if (desiredOn) {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                if (am != null) {
                    val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                    val currentVol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                    if (currentVol == 0) {
                        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, maxVol / 2, 0)
                    }
                }
            } catch (_: Exception) {}
        }

        val speakerNode = nodes.firstOrNull { node ->
            val txt = (node.text?.toString() ?: "").lowercase()
            val desc = (node.contentDescription?.toString() ?: "").lowercase()
            val id = (node.viewIdResourceName ?: "").lowercase()
            (id.contains("speaker") || txt.contains("speaker") || desc.contains("speaker") ||
             desc.contains("audio output") || desc.contains("earpiece") || id.contains("audio_source")) &&
            !desc.contains("active speaker") && !txt.contains("active speaker") &&
            (node.isClickable || node.parent?.isClickable == true)
        }

        if (speakerNode == null) {
            // If speaker button is not visible, Zoom's toolbar might be auto-hidden.
            // Dispatch a tap on the upper portion of the screen to un-hide controls.
            if (desiredOn && isInMeetingRoom && now - lastToolbarWakeTime > 4000) {
                lastToolbarWakeTime = now
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val metrics = resources.displayMetrics
                    val tapX = metrics.widthPixels * 0.5f
                    val tapY = metrics.heightPixels * 0.15f
                    val path = android.graphics.Path().apply { moveTo(tapX, tapY) }
                    val tap = android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50))
                        .build()
                    dispatchGesture(tap, null, null)
                    Log.d(TAG, "Tapped screen to reveal Zoom meeting toolbar for speaker control")
                }
            }
            return false
        }

        val desc = (speakerNode.contentDescription?.toString() ?: "").lowercase()
        val txt = (speakerNode.text?.toString() ?: "").lowercase()
        val isCurrentlyOn = desc.contains("turn off speaker") || desc.contains("speaker is on") ||
                            desc.contains("speakerphone on") || txt.contains("speaker on") ||
                            desc.contains("switch to earpiece") || desc.contains("switch to phone") ||
                            desc.contains("routed to speaker") || desc.contains("loudspeaker on")

        val isCurrentlyOff = desc.contains("turn on speaker") || desc.contains("speaker is off") ||
                             desc.contains("speakerphone off") || txt.contains("speaker off") ||
                             desc.contains("switch to speaker") || desc.contains("earpiece") ||
                             desc.contains("phone receiver") || desc.contains("routed to ear")

        if (desiredOn && (!isCurrentlyOn || isCurrentlyOff)) {
            lastSpeakerClickTime = now
            clickNode(speakerNode)
            Log.i(TAG, "Clicked Zoom speaker button to turn ON (loudspeaker)")
            return true
        } else if (!desiredOn && isCurrentlyOn) {
            lastSpeakerClickTime = now
            clickNode(speakerNode)
            Log.i(TAG, "Clicked Zoom speaker button to turn OFF (Muted / Silent)")
            return true
        }
        return false
    }

    private val knownCountries = setOf(
        "afghanistan", "albania", "algeria", "angola", "argentina",
        "australia", "austria", "bahrain", "bangladesh", "belgium",
        "brazil", "canada", "chile", "china", "colombia",
        "denmark", "egypt", "finland", "france", "germany",
        "greece", "hungary", "iceland", "india", "indonesia", "iran", "iraq",
        "ireland", "israel", "italy", "japan", "kenya",
        "malaysia", "mexico", "nepal", "netherlands", "new zealand",
        "nigeria", "norway", "pakistan", "philippines", "poland",
        "portugal", "qatar", "russia", "saudi arabia", "singapore",
        "south africa", "south korea", "spain", "sri lanka", "sweden",
        "switzerland", "thailand", "turkey", "united arab emirates",
        "united kingdom", "united states", "vietnam"
    )

    private fun isMatchingCountry(text: String, targetCountry: String): Boolean {
        if (text.isBlank()) return false
        val t = text.trim()
        val target = targetCountry.trim()
        return t.equals(target, ignoreCase = true) ||
               t.startsWith("$target ", ignoreCase = true) ||
               t.startsWith("$target(", ignoreCase = true) ||
               t.startsWith("$target -", ignoreCase = true) ||
               t.endsWith(" - $target", ignoreCase = true) ||
               t.endsWith("($target)", ignoreCase = true) ||
               t.contains(target, ignoreCase = true)
    }

    /**
     * Handles selecting the target country from an open list, dialog, or dropdown options.
     */
    private fun handleCountryListSelection(
        nodes: List<AccessibilityNodeInfo>,
        targetCountry: String,
    ): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastCountrySelectTime < 500) return false

        // 1. Search for matching country item anywhere in nodes or via native indexed text search
        val targetNode = nodes.firstOrNull { node ->
            val txt = (node.text?.toString() ?: "").trim()
            val desc = (node.contentDescription?.toString() ?: "").trim()
            isMatchingCountry(txt, targetCountry) || isMatchingCountry(desc, targetCountry)
        } ?: try {
            rootInActiveWindow?.findAccessibilityNodeInfosByText(targetCountry)?.firstOrNull { node ->
                val txt = (node.text?.toString() ?: "").trim()
                val desc = (node.contentDescription?.toString() ?: "").trim()
                isMatchingCountry(txt, targetCountry) || isMatchingCountry(desc, targetCountry)
            }
        } catch (_: Exception) { null }

        val isInCountryDialog = nodes.any { node ->
            val txt = (node.text?.toString() ?: "").lowercase()
            val desc = (node.contentDescription?.toString() ?: "").lowercase()
            txt.contains("select country") || txt.contains("country/region") ||
            txt.contains("choose country") || desc.contains("country")
        } || nodes.count { node ->
            val txt = (node.text?.toString() ?: "").trim().lowercase()
            txt in knownCountries
        } >= 1

        // If India is found, click it!
        if (targetNode != null) {
            lastCountrySelectTime = now
            clickNode(targetNode)
            Log.i(TAG, "Selected '$targetCountry' from country selection options")
            updateStatus(applicationContext, "🤖 Selected $targetCountry", false)
            return true
        }

        // If the country dialog is open and India is offscreen, scroll down towards 'I'
        if (isInCountryDialog && now - lastCountryScrollTime > 400) {
            lastCountryScrollTime = now

            // A. Try standard accessibility scroll forward
            val scrollable = nodes.firstOrNull { it.isScrollable }
            if (scrollable != null) {
                val didScroll = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                if (didScroll) {
                    Log.d(TAG, "Scrolled country list via ACTION_SCROLL_FORWARD")
                    return true
                }
            }

            // B. Touch swipe gesture to scroll down through the options list
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val displayMetrics = resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels.toFloat()
                val screenHeight = displayMetrics.heightPixels.toFloat()

                val startX = screenWidth * 0.5f
                val startY = screenHeight * 0.72f
                val endX = screenWidth * 0.5f
                val endY = screenHeight * 0.22f

                val path = Path().apply {
                    moveTo(startX, startY)
                    lineTo(endX, endY)
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 220))
                    .build()

                dispatchGesture(gesture, null, null)
                Log.d(TAG, "Scrolled country list via gesture swipe down to locate $targetCountry")
                return true
            }
        }

        return false
    }

    // ── Form Field Matchers ───────────────────────────────────────────

    private fun findNearbyLabelText(
        nodes: List<AccessibilityNodeInfo>,
        field: AccessibilityNodeInfo,
    ): String {
        val fieldRect = Rect()
        field.getBoundsInScreen(fieldRect)

        var bestLabel = ""
        var bestDistance = Int.MAX_VALUE

        for (node in nodes) {
            if (node === field || node.isEditable) continue
            val txt = node.text?.toString() ?: continue
            if (txt.isBlank() || txt.length > 60) continue

            val nodeRect = Rect()
            node.getBoundsInScreen(nodeRect)

            val vertDist = fieldRect.top - nodeRect.bottom
            val horizOverlap = maxOf(0, minOf(fieldRect.right, nodeRect.right) - maxOf(fieldRect.left, nodeRect.left))

            if (vertDist in -20..200 && horizOverlap > 0) {
                val dist = kotlin.math.abs(vertDist) + (200 - horizOverlap)
                if (dist < bestDistance) {
                    bestDistance = dist
                    bestLabel = txt
                }
            }
        }

        return bestLabel
    }

    private fun isPlaceholderValue(text: String): Boolean {
        val t = text.lowercase()
        return t.isEmpty() || t == "first name" || t == "last name" ||
               t.contains("@company") || t.contains("phone number") ||
               t.contains("example.com")
    }

    private fun isFirstNameField(context: String): Boolean =
        (context.contains("first") && context.contains("name")) ||
        context.contains("first name") || context.contains("firstname") ||
        context.contains("given name")

    private fun isLastNameField(context: String): Boolean =
        (context.contains("last") && context.contains("name")) ||
        context.contains("last name") || context.contains("lastname") ||
        context.contains("surname") || context.contains("family name")

    private fun isEmailField(context: String): Boolean =
        context.contains("email") || context.contains("e-mail") ||
        context.contains("mail address") || context.contains("@company.com") ||
        context.contains("join@")

    private fun isPhoneField(context: String): Boolean =
        context.contains("phone") || context.contains("mobile") ||
        context.contains("tel") || context.contains("cell") ||
        context.contains("phone number") || context.contains("your phone")

    // ── Exit Meeting Handling (When recording completely stops) ──────

    private fun handleExitMeeting(nodes: List<AccessibilityNodeInfo>): Boolean {
        val now = System.currentTimeMillis()

        // 1. Check if Zoom has already exited back to the home screen or is no longer in a meeting
        val isAtHomeScreen = nodes.any { node ->
            val txt = (node.text?.toString() ?: "").lowercase()
            val desc = (node.contentDescription?.toString() ?: "").lowercase()
            txt == "join a meeting" || txt == "new meeting" || desc.contains("join a meeting") ||
            txt == "schedule" || txt == "share screen"
        }
        if (isAtHomeScreen) {
            Log.i(TAG, "Zoom is already at home screen - meeting exited successfully!")
            shouldExitMeeting = false
            isInMeetingRoom = false
            updateStatus(applicationContext, "🤖 Meeting Exited", false)
            return true
        }

        // 2. Check for the confirmation dialog: "Leave Meeting" or "Leave" modal button
        // When 'Leave' is tapped in Zoom, Zoom pops up a modal bottom sheet / dialog with a red 'Leave Meeting' button.
        val confirmLeaveBtn = nodes.firstOrNull { node ->
            val txt = (node.text?.toString() ?: "").trim().lowercase()
            val desc = (node.contentDescription?.toString() ?: "").trim().lowercase()
            val id = (node.viewIdResourceName ?: "").lowercase()
            (txt == "leave meeting" || desc == "leave meeting" || txt == "end meeting" || desc == "end meeting" ||
             txt == "end meeting for all" || desc == "end meeting for all" ||
             id.contains("btn_leave_meeting") || id.contains("leave_meeting") || id.contains("btnleavemeeting")) &&
            (node.isClickable || node.parent?.isClickable == true)
        } ?: nodes.firstOrNull { node ->
            val txt = (node.text?.toString() ?: "").trim().lowercase()
            val id = (node.viewIdResourceName ?: "").lowercase()
            (txt == "leave" || id.contains("btn_leave")) &&
            (node.isClickable || node.parent?.isClickable == true) &&
            nodes.any { n ->
                val t = (n.text?.toString() ?: "").lowercase()
                t.contains("do you want to leave") || t.contains("leave meeting") || t.contains("are you sure")
            }
        }

        if (confirmLeaveBtn != null && now - lastLeaveConfirmClickTime > 800) {
            lastLeaveConfirmClickTime = now
            clickNode(confirmLeaveBtn)
            Log.i(TAG, "Auto-clicked 'Leave Meeting' confirmation button")
            updateStatus(applicationContext, "🤖 Exiting Meeting…", false)
            shouldExitMeeting = false
            isInMeetingRoom = false
            return true
        }

        // 3. Find the meeting top toolbar "Leave" or "End" button
        val toolbarLeaveBtn = nodes.firstOrNull { node ->
            val txt = (node.text?.toString() ?: "").trim().lowercase()
            val desc = (node.contentDescription?.toString() ?: "").trim().lowercase()
            val id = (node.viewIdResourceName ?: "").lowercase()
            (txt == "leave" || txt == "end" || desc == "leave" || desc == "leave meeting" ||
             desc == "end meeting" || desc == "end" || id.contains("btn_leave") || id.contains("btn_end") || id.contains("btnleave")) &&
            (node.isClickable || node.parent?.isClickable == true)
        }

        if (toolbarLeaveBtn != null && now - lastLeaveButtonClickTime > 1000) {
            lastLeaveButtonClickTime = now
            clickNode(toolbarLeaveBtn)
            Log.i(TAG, "Auto-clicked 'Leave' button in Zoom toolbar")
            updateStatus(applicationContext, "🤖 Tapped Leave…", false)
            return true
        }

        // 4. If neither confirmation nor toolbar leave button is visible, Zoom toolbar may be auto-hidden.
        // Dispatch a tap near the top of the screen to reveal the toolbar.
        if (now - lastLeaveWakeToolbarTime > 2000) {
            lastLeaveWakeToolbarTime = now
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val metrics = resources.displayMetrics
                val tapX = metrics.widthPixels * 0.5f
                val tapY = metrics.heightPixels * 0.15f
                val path = Path().apply { moveTo(tapX, tapY) }
                val tap = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                    .build()
                dispatchGesture(tap, null, null)
                Log.d(TAG, "Tapped screen to reveal Zoom toolbar for exiting meeting")
            }
            return true
        }

        return false
    }

    // ── Node Helpers ──────────────────────────────────────────────────

    private fun collectAllNodes(
        node: AccessibilityNodeInfo?,
        outList: MutableList<AccessibilityNodeInfo>,
        maxDepth: Int,
    ) {
        if (node == null || maxDepth <= 0) return
        outList.add(node)
        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i)
            if (child != null) {
                collectAllNodes(child, outList, maxDepth - 1)
            }
        }
    }

    private fun findButtonByTexts(
        nodes: List<AccessibilityNodeInfo>,
        targetTexts: List<String>,
    ): AccessibilityNodeInfo? {
        return nodes.firstOrNull { node ->
            val txt = (node.text?.toString() ?: "").trim().lowercase()
            val desc = (node.contentDescription?.toString() ?: "").trim().lowercase()
            targetTexts.any { target ->
                txt == target || desc == target || txt.contains(target) || desc.contains(target)
            } && (node.isClickable || node.parent?.isClickable == true)
        }
    }

    private fun findButtonByExactOrContainsText(
        nodes: List<AccessibilityNodeInfo>,
        targets: List<String>,
    ): AccessibilityNodeInfo? {
        return nodes.firstOrNull { node ->
            val txt = (node.text?.toString() ?: "").trim().lowercase()
            val desc = (node.contentDescription?.toString() ?: "").trim().lowercase()
            targets.any { t -> txt == t || desc == t || (txt.contains(t) && txt.length < 35) }
        }
    }

    private fun findSwitchByText(
        nodes: List<AccessibilityNodeInfo>,
        targets: List<String>,
    ): AccessibilityNodeInfo? {
        return nodes.firstOrNull { node ->
            val txt = (node.text?.toString() ?: "").lowercase()
            val desc = (node.contentDescription?.toString() ?: "").lowercase()
            node.isCheckable && targets.any { txt.contains(it) || desc.contains(it) }
        }
    }

    private fun setTextOnNode(node: AccessibilityNodeInfo, value: String) {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!success) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
    }

    private fun clickNode(node: AccessibilityNodeInfo) {
        if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }

        var p: AccessibilityNodeInfo? = node.parent
        while (p != null) {
            if (p.isClickable) {
                p.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
            p = p.parent
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (!rect.isEmpty) {
                val clickPath = Path().apply {
                    moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())
                }
                val stroke = GestureDescription.StrokeDescription(clickPath, 0, 50)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                dispatchGesture(gesture, null, null)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "ZoomBotAccessibilityService interrupted")
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(zoomSpeakerReceiver)
        } catch (_: Exception) {}
        isServiceRunning = false
        serviceInstance = null
        stopWatchdog()
        disarmBot(applicationContext)
        super.onDestroy()
        Log.i(TAG, "ZoomBotAccessibilityService destroyed")
    }
}
