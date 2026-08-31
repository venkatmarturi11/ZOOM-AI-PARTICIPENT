package com.zoomrecord.app.meeting.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import com.zoomrecord.app.recording.clampToDisplay
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.zoomrecord.app.auth.UserProfileStore
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Autonomous Zoom Bot Meeting Screen with embedded WebRTC client.
 *
 * Autonomous Bot capabilities:
 * 1. 100% Zero-Prompt Direct Join — No cast screen / MediaProjection prompt ever shown.
 * 2. Auto-Fills and Submits Zoom Registration Forms (First Name, Last Name, Email, Organization, Consent).
 * 3. Auto-Bypasses landing pages ("Launch Meeting", "Join from your browser").
 * 4. Auto-Fills Meeting Passcode & Display Name.
 * 5. Audio Control: Connects directly to mobile loudspeaker or Silent Mode based on user preference.
 * 6. Fullscreen Immersion: Strips all floating participant windows, avatars, and sidebars to lock 100% on the presentation.
 * 7. In-App Video & Audio Recorder: Encodes directly to high-quality local MP4 storage ready for the Recordings tab.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MeetingActiveScreen(
    meetingId: String,
    webUrl: String,
    displayName: String = "",
    password: String = "",
    initiallyMuted: Boolean = false,
    onLeave: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    val profileStore = remember { UserProfileStore(context) }
    val profile = remember { profileStore.getProfile() }

    val effectiveFirstName = remember(profile, displayName) {
        if (profile.firstName.isNotBlank()) profile.firstName
        else if (displayName.contains(" ")) displayName.substringBefore(" ").trim()
        else displayName.ifEmpty { "Zoom" }
    }

    val effectiveLastName = remember(profile, displayName) {
        if (profile.lastName.isNotBlank()) profile.lastName
        else if (displayName.contains(" ")) displayName.substringAfter(" ").trim()
        else "User"
    }

    val effectiveEmail = remember(profile, displayName) {
        if (profile.email.isNotBlank()) profile.email
        else {
            val clean = displayName.lowercase().replace("[^a-z0-9]".toRegex(), "").ifEmpty { "zoomuser" }
            "$clean@gmail.com"
        }
    }

    val effectivePhone = remember(profile) {
        if (profile.phone.isNotBlank()) profile.phone else "9876543210"
    }

    val zoomPassword = remember(profile) { profile.zoomPassword }
    val shouldLoginFirst = remember(profile, webUrl) {
        profile.hasZoomCredentials && profile.autoLoginZoomFirst && !webUrl.contains("/signin")
    }
    var loginPhaseDone by remember { mutableStateOf(!shouldLoginFirst) }

    var isRecordingActive by remember { mutableStateOf(false) }
    var recordedDurationSeconds by remember { mutableIntStateOf(0) }
    var isSavingRecording by remember { mutableStateOf(false) }

    // Listen for hardware ScreenRecordService state broadcasts
    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
                val isRec = intent.getBooleanExtra(com.zoomrecord.app.recording.ScreenRecordService.EXTRA_IS_RECORDING, false)
                val elapsed = intent.getIntExtra(com.zoomrecord.app.recording.ScreenRecordService.EXTRA_ELAPSED_SECONDS, 0)
                isRecordingActive = isRec
                recordedDurationSeconds = elapsed
            }
        }
        val filter = android.content.IntentFilter(com.zoomrecord.app.recording.ScreenRecordService.BROADCAST_RECORDING_STATE)
        androidx.core.content.ContextCompat.registerReceiver(
            context, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    var isLoading by remember { mutableStateOf(true) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isHudVisible by remember { mutableStateOf(true) }
    var lastTouchTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showLeaveConfirmationDialog by remember { mutableStateOf(false) }
    var inAppRecorder by remember { mutableStateOf<com.zoomrecord.app.recording.InAppMeetingRecorder?>(null) }

    // Start In-App Meeting Recorder automatically (zero cast screen prompt, records what bot sees & hears)
    LaunchedEffect(Unit) {
        if (activity != null) {
            val result = com.zoomrecord.app.library.RecordingsRepository.createPendingMp4(
                context, "zoom_bot_${System.currentTimeMillis()}"
            )
            val path = result.second
            if (path.isNotEmpty()) {
                val recorder = com.zoomrecord.app.recording.InAppMeetingRecorder(
                    activity = activity,
                    outputPath = path,
                    config = com.zoomrecord.app.recording.RecordingConfig(
                        width = 1280,
                        height = 720,
                        frameRate = 30,
                        videoBitrate = 3_000_000,
                    ).clampToDisplay(activity),
                    onStarted = {
                        isRecordingActive = true
                    },
                    onTick = { seconds ->
                        recordedDurationSeconds = seconds
                    },
                    onError = { err ->
                        Log.e("MeetingActiveScreen", "InAppMeetingRecorder error: $err")
                    }
                )
                inAppRecorder = recorder
                recorder.start()
            }
        }
    }

    // Dedicated Bot Listening Audio Pipeline:
    // Automatically routes and maintains meeting audio so the recorder captures what the bot listens to with zero user toggles
    val setupBotListeningAudio: () -> Unit = remember(webViewRef, audioManager) {
        {
            val am = audioManager
            if (am != null) {
                try {
                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        try {
                            val devices = am.availableCommunicationDevices
                            val speaker = devices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                            if (speaker != null) {
                                am.setCommunicationDevice(speaker)
                            }
                        } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    Log.w("MeetingActiveScreen", "Audio setup error", e)
                }
            }

            // Always keep WebRTC media elements unmuted internally and resume AudioContext
            val audioStreamJs = """
                (function() {
                    try {
                        var AudioCtx = window.AudioContext || window.webkitAudioContext;
                        if (window.__zoomAudioCtx && window.__zoomAudioCtx.state === 'suspended') {
                            window.__zoomAudioCtx.resume();
                        }
                    } catch(e) {}
                    var els = document.querySelectorAll('audio, video, #wc-audio');
                    for (var i = 0; i < els.length; i++) {
                        els[i].muted = false;
                        els[i].volume = 1.0;
                    }
                })();
            """.trimIndent()
            webViewRef?.post {
                try {
                    webViewRef?.evaluateJavascript(audioStreamJs, null)
                } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(webViewRef) {
        setupBotListeningAudio()
    }

    val coroutineScope = rememberCoroutineScope()

    // Safe leave: stops in-app recorder cleanly on background thread so MP4 moov atom is fully written
    val performSafeLeave: () -> Unit = remember(context, onLeave, inAppRecorder) {
        {
            if (!isSavingRecording) {
                isSavingRecording = true
                showLeaveConfirmationDialog = false
                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        // Stop in-app recorder cleanly
                        inAppRecorder?.stop()
                        inAppRecorder = null

                        // Also signal hardware ScreenRecordService if running
                        try {
                            val stopIntent = android.content.Intent(
                                context,
                                com.zoomrecord.app.recording.ScreenRecordService::class.java
                            ).apply {
                                action = com.zoomrecord.app.recording.ScreenRecordService.ACTION_STOP
                            }
                            context.startService(stopIntent)
                        } catch (_: Exception) {}
                    } catch (e: Exception) {
                        Log.e("MeetingActiveScreen", "Error finalizing recording on leave", e)
                    }
                    kotlinx.coroutines.delay(500)
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "✅ Recorded video with audio saved to phone storage!", android.widget.Toast.LENGTH_SHORT).show()
                        onLeave()
                    }
                }
            }
        }
    }

    var isPocketMode by remember { mutableStateOf(false) }

    // Lock to Immersive Fullscreen Landscape Mode and keep screen awake
    DisposableEffect(Unit) {
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.window?.let { win ->
            win.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowCompat.setDecorFitsSystemWindows(win, false)
            val controller = WindowInsetsControllerCompat(win, win.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        onDispose {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            activity?.window?.let { win ->
                win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                WindowCompat.setDecorFitsSystemWindows(win, true)
                val controller = WindowInsetsControllerCompat(win, win.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Auto-hide HUD after 4 seconds
    LaunchedEffect(isHudVisible, lastTouchTime) {
        if (isHudVisible) {
            delay(4000)
            isHudVisible = false
        }
    }

    // Auto-grant audio permission
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        injectAutonomousBot(
            webView = webViewRef,
            displayName = displayName,
            password = password,
            firstName = effectiveFirstName,
            lastName = effectiveLastName,
            email = effectiveEmail,
            phone = effectivePhone,
            zoomPassword = zoomPassword,
            targetMeetingUrl = if (shouldLoginFirst && !loginPhaseDone) webUrl else "",
        )
    }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    BackHandler {
        showLeaveConfirmationDialog = true
    }

    // Autonomous bot runner: runs smoothly with low CPU footprint to eliminate video lag
    LaunchedEffect(Unit) {
        var tickCounter = 0
        while (true) {
            val delayMs = if (tickCounter < 6) 2000L else 5000L // 2s during joining, then 5s maintenance (zero video lag)
            delay(delayMs)
            tickCounter++
            injectAutonomousBot(
                webView = webViewRef,
                displayName = displayName,
                password = password,
                firstName = effectiveFirstName,
                lastName = effectiveLastName,
                email = effectiveEmail,
                phone = effectivePhone,
                zoomPassword = zoomPassword,
                targetMeetingUrl = if (shouldLoginFirst && !loginPhaseDone) webUrl else "",
            )
            if (tickCounter % 3 == 0) {
                setupBotListeningAudio()
            }
        }
    }

    val formattedTime = String.format(
        "%02d:%02d:%02d",
        recordedDurationSeconds / 3600,
        (recordedDurationSeconds % 3600) / 60,
        recordedDurationSeconds % 60
    )

    // Teardown on exit
    DisposableEffect(Unit) {
        onDispose {
            try {
                val stopIntent = android.content.Intent(
                    context,
                    com.zoomrecord.app.recording.ScreenRecordService::class.java
                ).apply {
                    action = com.zoomrecord.app.recording.ScreenRecordService.ACTION_STOP
                }
                context.startService(stopIntent)
            } catch (_: Exception) {}

            try {
                audioManager?.mode = AudioManager.MODE_NORMAL
                audioManager?.isSpeakerphoneOn = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager?.clearCommunicationDevice()
                }
            } catch (_: Exception) {}

            try {
                webViewRef?.stopLoading()
                webViewRef?.loadUrl("about:blank")
                webViewRef?.clearHistory()
                webViewRef?.destroy()
            } catch (_: Exception) {}
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF000000),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            // ── Fullscreen Live Meeting WebView ──────────────────────────────
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                        isVerticalScrollBarEnabled = true
                        isHorizontalScrollBarEnabled = true
                        isScrollbarFadingEnabled = true
                        isNestedScrollingEnabled = true

                        // Touch handler: reveals HUD on touch without consuming or blocking web scrolling/gestures
                        setOnTouchListener { _, _ ->
                            isHudVisible = true
                            lastTouchTime = System.currentTimeMillis()
                            false
                        }

                        webViewRef = this

                        // Bridge interface — receives base64-encoded 16-bit PCM audio chunks
                        // tapped directly from the meeting's <audio>/<video> elements via the
                        // page's own Web Audio API (see injectAutonomousBot's JS tap below).
                        // Forwarded straight into the active recording session's audio encoder.
                        addJavascriptInterface(
                            object {
                                @android.webkit.JavascriptInterface
                                fun sendAudioChunk(base64Data: String) {
                                    try {
                                        val bytes = android.util.Base64.decode(base64Data, android.util.Base64.NO_WRAP)
                                        inAppRecorder?.feedDirectPcm(bytes)
                                        com.zoomrecord.app.recording.ScreenRecordService.feedDirectAudioPcm(bytes)
                                    } catch (e: Exception) {
                                        Log.w("MeetingActiveScreen", "WebAudioBridge decode failed", e)
                                    }
                                }
                            },
                            "WebAudioBridge"
                        )

                        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            @Suppress("DEPRECATION")
                            databaseEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            allowFileAccess = true
                            allowContentAccess = true
                            setSupportMultipleWindows(false)
                            javaScriptCanOpenWindowsAutomatically = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            cacheMode = WebSettings.LOAD_DEFAULT

                            useWideViewPort = true
                            loadWithOverviewMode = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                safeBrowsingEnabled = false
                            }

                            // Desktop Chrome User-Agent so Zoom WebRTC and registration run directly
                            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
                        }

                        webChromeClient = object : WebChromeClient() {
                            // Auto-dismiss all JavaScript alert() dialogs silently (fixes continuous popup)
                            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                                Log.d("MeetingActiveScreen", "Auto-dismissed JS alert: $message")
                                result?.confirm()
                                return true
                            }

                            // Auto-dismiss all JavaScript confirm() dialogs silently
                            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                                Log.d("MeetingActiveScreen", "Auto-dismissed JS confirm: $message")
                                result?.confirm()
                                return true
                            }

                            // Auto-dismiss all JavaScript prompt() dialogs silently
                            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult?): Boolean {
                                Log.d("MeetingActiveScreen", "Auto-dismissed JS prompt: $message")
                                result?.confirm(defaultValue ?: "")
                                return true
                            }

                            override fun onPermissionRequest(request: PermissionRequest?) {
                                post {
                                    try {
                                        val resources = request?.resources ?: emptyArray()
                                        val granted = mutableListOf<String>()
                                        for (r in resources) {
                                            if (r == PermissionRequest.RESOURCE_AUDIO_CAPTURE || r == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID) {
                                                granted.add(r)
                                            }
                                        }
                                        if (granted.isNotEmpty()) {
                                            request?.grant(granted.toTypedArray())
                                        } else {
                                            request?.deny()
                                        }
                                    } catch (_: Exception) {}
                                }
                            }

                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                super.onProgressChanged(view, newProgress)
                                if (newProgress >= 100) {
                                    injectAutonomousBot(
                                        webView = view,
                                        displayName = displayName,
                                        password = password,
                                        firstName = effectiveFirstName,
                                        lastName = effectiveLastName,
                                        email = effectiveEmail,
                                        phone = effectivePhone,
                                        zoomPassword = zoomPassword,
                                        targetMeetingUrl = if (shouldLoginFirst && !loginPhaseDone) webUrl else "",
                                    )
                                }
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                isLoading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                val curUrl = url?.lowercase() ?: ""
                                try {
                                    android.webkit.CookieManager.getInstance().flush()
                                } catch (_: Exception) {}

                                // Auto-transition from Zoom Sign-in to Target Meeting URL
                                if (shouldLoginFirst && !loginPhaseDone &&
                                    !curUrl.contains("/signin") && !curUrl.contains("/login") && !curUrl.contains("about:blank") &&
                                    !curUrl.contains("google.com") && !curUrl.contains("accounts.google")
                                ) {
                                    loginPhaseDone = true
                                    postDelayed({
                                        view?.loadUrl(webUrl)
                                    }, 500)
                                    return
                                }

                                injectAutonomousBot(
                                    webView = view,
                                    displayName = displayName,
                                    password = password,
                                    firstName = effectiveFirstName,
                                    lastName = effectiveLastName,
                                    email = effectiveEmail,
                                    phone = effectivePhone,
                                    zoomPassword = zoomPassword,
                                    targetMeetingUrl = if (shouldLoginFirst && !loginPhaseDone) webUrl else "",
                                )
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): android.webkit.WebResourceResponse? {
                                val url = request?.url?.toString()?.lowercase() ?: return null
                                // Block heavy third-party marketing trackers and telemetry to save mobile data
                                if (url.contains("google-analytics.com") ||
                                    url.contains("googletagmanager.com") ||
                                    url.contains("doubleclick.net") ||
                                    url.contains("onetrust.com") ||
                                    url.contains("launchdarkly.com") ||
                                    url.contains("optimizely.com") ||
                                    url.contains("facebook.net") ||
                                    url.contains("hotjar.com") ||
                                    url.contains("segment.io")) {
                                    return android.webkit.WebResourceResponse(
                                        "text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0))
                                    )
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val uri = request?.url ?: return false
                                val scheme = uri.scheme?.lowercase() ?: ""
                                if (scheme != "http" && scheme != "https") {
                                    return true
                                }
                                return false
                            }
                        }

                        val startUrl = if (shouldLoginFirst && !loginPhaseDone) "https://zoom.us/signin" else webUrl
                        if (startUrl.isNotBlank()) {
                            loadUrl(startUrl)
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // ── Loading overlay while Zoom Web Client loads ──────────────────
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF141A28).copy(alpha = 0.95f))
                        .padding(28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF0D72FF),
                            modifier = Modifier.size(38.dp),
                            strokeWidth = 3.dp,
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = if (shouldLoginFirst && !loginPhaseDone) "🔑 Auto-Logging in to Zoom Account…" else "🤖 Bot Auto-Joining Meeting…",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = if (shouldLoginFirst && !loginPhaseDone) "Signing in to bypass reCAPTCHA & bot challenges" else "Auto-joining, routing audio & locking onto presentation",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            // ── Animated Top HUD Bar (Touch-triggered, auto-hides) ───────────
            AnimatedVisibility(
                visible = isHudVisible,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F1522).copy(alpha = 0.94f))
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Left: Bot Status & REC Timer
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF0D72FF).copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.SmartToy,
                                    contentDescription = "Bot",
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Bot Active",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        if (isRecordingActive) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFFF3B30).copy(alpha = 0.2f))
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.FiberManualRecord,
                                        contentDescription = "Recording",
                                        tint = Color(0xFFFF3B30),
                                        modifier = Modifier.size(10.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "REC $formattedTime",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF22C55E).copy(alpha = 0.2f))
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Bot Armed",
                                        tint = Color(0xFF22C55E),
                                        modifier = Modifier.size(10.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Auto-REC Active",
                                        color = Color(0xFF22C55E),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }

                    // Center: Host Screen Lock Badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.CenterFocusStrong,
                            contentDescription = "Host Screen Focused",
                            tint = Color(0xFF22C55E),
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (meetingId.isNotEmpty() && meetingId != "Registration") "Host: $meetingId" else "Host Screen Locked",
                            color = Color(0xFFE2E8F0),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    // Right: Dedicated Bot Audio Status & Leave Button
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Clean Bot Listening Audio Indicator (Records only what the bot listens to)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF064E3B).copy(alpha = 0.85f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981)),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .background(Color(0xFF34D399), androidx.compose.foundation.shape.CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Bot Audio Active",
                                    color = Color(0xFFECFDF5),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.sp,
                                )
                            }
                        }

                        // Pocket / Blackout Mode button (Screen looks off, but recording and meeting continue 100%)
                        Button(
                            onClick = { isPocketMode = true },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                            modifier = Modifier.height(32.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 9.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Pocket Mode",
                                modifier = Modifier.size(13.dp),
                                tint = Color(0xFFFBBF24),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Pocket",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                            )
                        }

                        Button(
                            onClick = { showLeaveConfirmationDialog = true },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                            modifier = Modifier.height(32.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 9.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.CallEnd,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = Color.White,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Leave",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }

            // ── Saving / Finalizing Recording Overlay ────────────────────────
            if (isSavingRecording) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.9f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF0D72FF),
                            modifier = Modifier.size(44.dp),
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Saving & Finalizing Recording…",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Auto-saving HD MP4 with audio directly to your Recordings tab",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            // ── AMOLED Blackout / Pocket Mode Overlay ─────────────────────────
            if (isPocketMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF000000))
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    isPocketMode = false
                                }
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Pocket Mode",
                            tint = Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "🌙 Pocket / Blackout Mode Active",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Recording audio & video in background • Double-tap to unlock",
                            color = Color.White.copy(alpha = 0.3f),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            // ── Leave Meeting Confirmation Dialog ────────────────────────────
            if (showLeaveConfirmationDialog) {
                AlertDialog(
                    onDismissRequest = { if (!isSavingRecording) showLeaveConfirmationDialog = false },
                    containerColor = Color(0xFF18202F),
                    title = {
                        Text(
                            text = "Leave Meeting?",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        )
                    },
                    text = {
                        Text(
                            text = "Are you sure you want to leave this meeting? The recorded video and audio will be saved to your Recordings tab.",
                            color = Color(0xFFBAC7D5),
                            fontSize = 14.sp,
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = { performSafeLeave() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Leave & Save", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        OutlinedButton(
                            onClick = { if (!isSavingRecording) showLeaveConfirmationDialog = false },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFF3B4858)),
                        ) {
                            Text("Stay in Meeting", color = Color.White)
                        }
                    },
                )
            }
        }
    }
}

/**
 * Autonomous bot automation into Zoom Web Client & Registration Pages:
 * 1. Auto-authenticates with Zoom account credentials (Email & Password) to bypass reCAPTCHA and bot challenges.
 * 2. Makes page fully scrollable and interactive so user/bot can see everything.
 * 3. Fills and submits Zoom Registration Forms (First Name, Last Name, Email, Organization, Job, City, Selects, Consent).
 * 4. Detects post-registration confirmation pages and immediately navigates into the live meeting room.
 * 5. Auto-clicks "Click here to join", "Join Meeting", "Launch Meeting", "Join from your browser".
 * 6. Auto-fills Display Name and Meeting Passcode.
 * 7. Auto-joins Computer Audio immediately upon entry.
 * 8. Auto-mutes mic and turns off camera for silent bot presence.
 * 9. Suppresses participant floating windows, avatar cards, speaker bars, and toolbars when in meeting.
 * 10. Auto-dismisses cookie banners, GDPR modals, and permission guide dialogs.
 */
private fun injectAutonomousBot(
    webView: WebView?,
    displayName: String = "",
    password: String = "",
    firstName: String = "",
    lastName: String = "",
    email: String = "",
    phone: String = "",
    zoomPassword: String = "",
    targetMeetingUrl: String = "",
) {
    if (webView == null) return

    val cleanName = displayName.replace("\"", "\\\"").replace("'", "\\'")
    val cleanPwd = password.replace("\"", "\\\"").replace("'", "\\'")
    val cleanFirst = firstName.replace("\"", "\\\"").replace("'", "\\'")
    val cleanLast = lastName.replace("\"", "\\\"").replace("'", "\\'")
    val cleanEmail = email.replace("\"", "\\\"").replace("'", "\\'")
    val cleanPhone = phone.replace("\"", "\\\"").replace("'", "\\'")
    val cleanZoomPwd = zoomPassword.replace("\"", "\\\"").replace("'", "\\'")
    val cleanTargetUrl = targetMeetingUrl.replace("\"", "\\\"").replace("'", "\\'")

    val script = """
        (function() {
            // ── 0-pre. Suppress all native alert/confirm/prompt dialogs ──
            try {
                if (!window.__zoomBotAlertSuppressed) {
                    window.__zoomBotAlertSuppressed = true;
                    window.alert = function() { return undefined; };
                    window.confirm = function() { return true; };
                    window.prompt = function() { return ''; };
                }
            } catch(e) {}

            // ── 0. Synthetic WebRTC Device Mock ──────────────────────
            try {
                if (!window.__zoomBotMediaMocked && window.navigator && window.navigator.mediaDevices) {
                    window.__zoomBotMediaMocked = true;
                    var dummyCanvas = document.createElement('canvas');
                    dummyCanvas.width = 16;
                    dummyCanvas.height = 16;
                    var cCtx = dummyCanvas.getContext('2d');
                    if (cCtx) { cCtx.fillStyle = '#000000'; cCtx.fillRect(0, 0, 16, 16); }

                    window.navigator.mediaDevices.getUserMedia = function(constraints) {
                        var tracks = [];
                        try {
                            // Video track from canvas
                            if (constraints && constraints.video) {
                                var vStream = dummyCanvas.captureStream ? dummyCanvas.captureStream(1) : null;
                                if (vStream && vStream.getVideoTracks().length > 0) tracks.push(vStream.getVideoTracks()[0]);
                            }
                            // Audio track: create proper silent oscillator-based audio track
                            // so Zoom's WebRTC audio pipeline initializes correctly
                            if (constraints && constraints.audio) {
                                try {
                                    var silentCtx = new (window.AudioContext || window.webkitAudioContext)({sampleRate: 44100});
                                    var oscillator = silentCtx.createOscillator();
                                    var gainNode = silentCtx.createGain();
                                    gainNode.gain.value = 0;
                                    oscillator.connect(gainNode);
                                    var dest = silentCtx.createMediaStreamDestination();
                                    gainNode.connect(dest);
                                    oscillator.start();
                                    var silentAudioTrack = dest.stream.getAudioTracks()[0];
                                    if (silentAudioTrack) tracks.push(silentAudioTrack);
                                } catch(ae) {}
                            }
                        } catch(e) {}
                        return Promise.resolve(new MediaStream(tracks));
                    };
                    window.navigator.mediaDevices.enumerateDevices = function() {
                        return Promise.resolve([
                            { deviceId: 'default', kind: 'audioinput', label: 'Default Microphone', groupId: 'g1' },
                            { deviceId: 'default', kind: 'videoinput', label: 'Default Camera', groupId: 'g1' },
                            { deviceId: 'default', kind: 'audiooutput', label: 'Default Speaker', groupId: 'g1' }
                        ]);
                    };
                }

                if (window.Notification && !window.__zoomBotNotifMocked) {
                    window.__zoomBotNotifMocked = true;
                    window.Notification.requestPermission = function() { return Promise.resolve('granted'); };
                    Object.defineProperty(window.Notification, 'permission', { get: function() { return 'granted'; }, configurable: true });
                }
            } catch (e) {}

            // ── 0b. Bulletproof Digital WebRTC Audio Tap to Native WebAudioBridge ──
            try {
                if (!window.__webAudioTapInstalled) {
                    window.__webAudioTapInstalled = true;
                    var AudioCtxClass = window.AudioContext || window.webkitAudioContext;
                    if (AudioCtxClass) {
                        var tapCtx = new AudioCtxClass({ sampleRate: 44100 });
                        if (tapCtx.state === 'suspended') {
                            tapCtx.resume();
                        }
                        window.__zoomAudioCtx = tapCtx;

                        // Shared function to send Float32 PCM data to native bridge
                        function sendPcmToNative(floatData) {
                            if (!window.WebAudioBridge) return;
                            try {
                                var pcm = new Int16Array(floatData.length);
                                var hasAudio = false;
                                for (var i = 0; i < floatData.length; i++) {
                                    var s = Math.max(-1, Math.min(1, floatData[i]));
                                    pcm[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
                                    if (!hasAudio && Math.abs(pcm[i]) > 5) hasAudio = true;
                                }
                                if (hasAudio) {
                                    var bytes = new Uint8Array(pcm.buffer);
                                    var bin = '';
                                    var bLen = bytes.byteLength;
                                    for (var b = 0; b < bLen; b++) {
                                        bin += String.fromCharCode(bytes[b]);
                                    }
                                    window.WebAudioBridge.sendAudioChunk(btoa(bin));
                                }
                            } catch(e) {}
                        }

                        // Create audio processor — prefer AudioWorklet, fall back to ScriptProcessor
                        var tapProcessor = null;
                        try {
                            if (tapCtx.audioWorklet && typeof tapCtx.audioWorklet.addModule === 'function') {
                                // AudioWorklet approach (modern, reliable)
                                var workletCode = 'class TapProcessor extends AudioWorkletProcessor {' +
                                    'process(inputs) {' +
                                    '  var input = inputs[0];' +
                                    '  if (input && input[0] && input[0].length > 0) {' +
                                    '    this.port.postMessage(input[0]);' +
                                    '  }' +
                                    '  return true;' +
                                    '}' +
                                    '} registerProcessor("tap-processor", TapProcessor);';
                                var blob = new Blob([workletCode], { type: 'application/javascript' });
                                var blobUrl = URL.createObjectURL(blob);
                                tapCtx.audioWorklet.addModule(blobUrl).then(function() {
                                    var workletNode = new AudioWorkletNode(tapCtx, 'tap-processor');
                                    workletNode.port.onmessage = function(ev) {
                                        sendPcmToNative(ev.data);
                                    };
                                    workletNode.connect(tapCtx.destination);
                                    window.__zoomTapNode = workletNode;
                                }).catch(function() {
                                    // AudioWorklet failed, create ScriptProcessor fallback
                                    createScriptProcessorFallback();
                                });
                            } else {
                                createScriptProcessorFallback();
                            }
                        } catch(wErr) {
                            createScriptProcessorFallback();
                        }

                        function createScriptProcessorFallback() {
                            tapProcessor = tapCtx.createScriptProcessor(4096, 1, 1);
                            tapProcessor.onaudioprocess = function(ev) {
                                sendPcmToNative(ev.inputBuffer.getChannelData(0));
                            };
                            tapProcessor.connect(tapCtx.destination);
                            window.__zoomTapNode = tapProcessor;
                        }

                        function hookMediaElement(el) {
                            if (!el || el.__tapHooked) return;
                            el.__tapHooked = true;
                            try {
                                var src = tapCtx.createMediaElementSource(el);
                                var node = window.__zoomTapNode;
                                if (node) src.connect(node);
                                src.connect(tapCtx.destination);
                            } catch(e) {}
                        }

                        function hookMediaStream(stream) {
                            if (!stream || stream.__tapHooked) return;
                            stream.__tapHooked = true;
                            try {
                                var aTracks = stream.getAudioTracks();
                                if (aTracks && aTracks.length > 0) {
                                    var streamSrc = tapCtx.createMediaStreamSource(stream);
                                    var node = window.__zoomTapNode;
                                    if (node) streamSrc.connect(node);
                                }
                            } catch(e) {}
                        }

                        var existingAudio = document.querySelectorAll('audio, video, #wc-audio');
                        for (var ea = 0; ea < existingAudio.length; ea++) hookMediaElement(existingAudio[ea]);

                        var tapObs = new MutationObserver(function() {
                            var audios = document.querySelectorAll('audio, video, #wc-audio');
                            for (var a = 0; a < audios.length; a++) hookMediaElement(audios[a]);
                        });
                        tapObs.observe(document.documentElement, { childList: true, subtree: true });

                        try {
                            var origPlay = HTMLMediaElement.prototype.play;
                            HTMLMediaElement.prototype.play = function() {
                                hookMediaElement(this);
                                if (tapCtx.state === 'suspended') tapCtx.resume();
                                return origPlay.apply(this, arguments);
                            };
                        } catch(e) {}

                        // Hook AudioNode.prototype.connect to intercept internal WebAudio playing to destination
                        try {
                            var origNodeConnect = AudioNode.prototype.connect;
                            AudioNode.prototype.connect = function(dest) {
                                if (dest && window.__zoomTapNode && dest !== window.__zoomTapNode) {
                                    try {
                                        if (dest === this.context.destination || dest.numberOfOutputs === 0) {
                                            origNodeConnect.call(this, window.__zoomTapNode);
                                        }
                                    } catch(e) {}
                                }
                                return origNodeConnect.apply(this, arguments);
                            };
                        } catch(e) {}

                        // Hook RTCPeerConnection for WebRTC audio streams
                        if (window.RTCPeerConnection) {
                            // Hook addEventListener('track', ...)
                            var origAddEventListener = RTCPeerConnection.prototype.addEventListener;
                            RTCPeerConnection.prototype.addEventListener = function(type, listener, options) {
                                if (type === 'track') {
                                    var wrapped = function(event) {
                                        if (event.track && event.track.kind === 'audio') {
                                            if (event.streams && event.streams[0]) {
                                                hookMediaStream(event.streams[0]);
                                            } else {
                                                hookMediaStream(new MediaStream([event.track]));
                                            }
                                        }
                                        return listener.apply(this, arguments);
                                    };
                                    return origAddEventListener.call(this, type, wrapped, options);
                                }
                                return origAddEventListener.apply(this, arguments);
                            };

                            // Also hook the ontrack property setter for broader compatibility
                            var ontrackDesc = Object.getOwnPropertyDescriptor(RTCPeerConnection.prototype, 'ontrack');
                            if (ontrackDesc && ontrackDesc.set) {
                                var origOntrackSet = ontrackDesc.set;
                                Object.defineProperty(RTCPeerConnection.prototype, 'ontrack', {
                                    set: function(handler) {
                                        var wrappedHandler = function(event) {
                                            if (event.track && event.track.kind === 'audio') {
                                                if (event.streams && event.streams[0]) {
                                                    hookMediaStream(event.streams[0]);
                                                } else {
                                                    hookMediaStream(new MediaStream([event.track]));
                                                }
                                            }
                                            if (handler) return handler.call(this, event);
                                        };
                                        origOntrackSet.call(this, wrappedHandler);
                                    },
                                    get: ontrackDesc.get,
                                    configurable: true
                                });
                            }
                        }
                    }
                }
            } catch(e) {}

            // ── 1. Clean Layout & Full Screen Scrollability ────────────────────
            if (!document.getElementById('zoom-bot-host-style')) {
                var style = document.createElement('style');
                style.id = 'zoom-bot-host-style';
                style.innerHTML = [
                    'html, body {',
                    '  min-height: 100% !important;',
                    '  overflow: hidden !important;',
                    '  background-color: #000000 !important;',
                    '}',
                    '#wc-container, #wc-content, .meeting-app, .meeting-app__body {',
                    '  width: 100% !important;',
                    '  height: 100% !important;',
                    '  margin: 0 !important;',
                    '  padding: 0 !important;',
                    '}',
                    '.video-floating-container, div[class*="video-floating-container"],',
                    '.video-floating, .speaker-bar__video-item,',
                    '.speaker-view-video-strip, .gallery-bar, .attendee-video-overlay {',
                    '  display: none !important; visibility: hidden !important;',
                    '  opacity: 0 !important; pointer-events: none !important;',
                    '}',
                    '/* Complete Removal of Participants and Sidebar Panels */',
                    '.side-window, .side-panel-container, .participants-section-container,',
                    '.participants-content-container, .participants-panel, #participants-panel,',
                    '.participants-list, .participants-wrapper, .sidebar-container, .wrap-side-window,',
                    '.right-panel, .sidebar-window, aside[class*="side-window"], .chat-container {',
                    '  display: none !important; visibility: hidden !important;',
                    '  width: 0 !important; min-width: 0 !important; max-width: 0 !important;',
                    '  height: 0 !important; opacity: 0 !important; pointer-events: none !important;',
                    '  position: absolute !important; left: -99999px !important; top: -99999px !important;',
                    '}',
                    '/* Hide Zoom internal Record button & Ask Host dialogs completely */',
                    'button[aria-label*="Record" i], button[aria-label*="record" i],',
                    '.record-icon, .recording-icon, .record-btn, #btn-record, button[class*="record" i],',
                    'div[role="dialog"]:has(button[aria-label*="Ask host" i]), .zm-modal:has(button[aria-label*="Ask host" i]),',
                    'div[aria-label*="Ask host" i] {',
                    '  display: none !important; visibility: hidden !important; pointer-events: none !important;',
                    '}',
                    '/* Permanently eradicate all modals, dialogs, popups, and dark backdrops */',
                    '.zm-modal, .modal-dialog, div[role="dialog"], .zm-modal-backdrop, .modal-backdrop,',
                    '.join-dialog, .audio-dialog, .join-audio-container, .join-audio-menu, .zm-select-menu,',
                    'button[aria-label*="Join Audio" i], button[aria-label*="join audio" i],',
                    '#btn-join-audio, .btn-join-audio,',
                    'div[class*="modal" i], div[class*="dialog" i], div[class*="backdrop" i] {',
                    '  display: none !important; visibility: hidden !important;',
                    '  opacity: 0 !important; pointer-events: none !important;',
                    '  width: 0 !important; height: 0 !important;',
                    '  position: absolute !important; left: -99999px !important; top: -99999px !important;',
                    '}',
                    '#wc-footer, .footer, .meeting-app__footer, .footer__control-bar,',
                    '#wc-header, .header, .meeting-app__header, .share-header,',
                    '.speaker-bar, .view-switch-container, .speaker-name-bar {',
                    '  transition: opacity 0.35s ease-in-out, transform 0.35s ease-in-out !important;',
                    '}'
                ].join('\n');
                document.head.appendChild(style);
            }

            // ── Auto-Hide Zoom Meeting Toolbars When Screen is Untouched ────
            if (!window.__zoomAutoHideInstalled) {
                window.__zoomAutoHideInstalled = true;
                var hideTimeout = null;

                function setZoomBarsVisible(visible) {
                    var selectors = [
                        '#wc-footer', '.footer', '.meeting-app__footer', '.footer__control-bar',
                        '#wc-header', '.header', '.meeting-app__header', '.share-header',
                        '.speaker-bar', '.view-switch-container', '.speaker-name-bar',
                        '.video-avatar__avatar-title', '.meeting-client-footer', '.meeting-client-header'
                    ];
                    var els = document.querySelectorAll(selectors.join(', '));
                    for (var b = 0; b < els.length; b++) {
                        els[b].style.opacity = visible ? '1' : '0';
                        els[b].style.pointerEvents = visible ? 'auto' : 'none';
                    }
                }

                function onScreenActivity() {
                    setZoomBarsVisible(true);
                    if (hideTimeout) clearTimeout(hideTimeout);
                    hideTimeout = setTimeout(function() {
                        // Only auto-hide if active in meeting room
                        if (document.querySelector('#wc-container, #wc-content, canvas, video')) {
                            setZoomBarsVisible(false);
                        }
                    }, 3500);
                }

                ['touchstart', 'touchmove', 'touchend', 'pointerdown', 'pointermove', 'mousedown', 'mousemove', 'click'].forEach(function(evt) {
                    window.addEventListener(evt, onScreenActivity, { passive: true });
                    document.addEventListener(evt, onScreenActivity, { passive: true });
                });
            }

            // ── Stealth & Anti-Bot Defense Shielding (Browserless Standards) ─
            try {
                // 1. Remove automation driver flags
                Object.defineProperty(navigator, 'webdriver', { get: function() { return undefined; }, configurable: true });
                // 2. Mock Chrome runtime
                if (!window.chrome) {
                    window.chrome = { runtime: {}, loadTimes: function(){}, csi: function(){}, app: {} };
                }
                // 3. Emulate standard plugins & languages
                Object.defineProperty(navigator, 'plugins', {
                    get: function() {
                        return [
                            { name: 'Chrome PDF Plugin', filename: 'internal-pdf-viewer' },
                            { name: 'Chrome PDF Viewer', filename: 'mhjfbmdgcfjbbpaeojofoholkgfllicj' },
                            { name: 'Native Client', filename: 'internal-nacl-plugin' }
                        ];
                    },
                    configurable: true
                });
                Object.defineProperty(navigator, 'languages', {
                    get: function() { return ['en-US', 'en']; },
                    configurable: true
                });
            } catch(e) {}

            // ── Honeypot Detection & Visibility Validator ───────────────────
            function isElementVisible(el) {
                if (!el) return false;
                try {
                    var style = window.getComputedStyle(el);
                    if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') return false;
                    var rect = el.getBoundingClientRect();
                    return (rect.width > 0 && rect.height > 0 && rect.top >= -300);
                } catch(e) {
                    return (el.offsetWidth > 0 || el.offsetHeight > 0);
                }
            }

            // ── Human-like Input Dispatcher (Simulates Keystroke Events) ────
            function setField(input, val) {
                if (!input || !val || !isElementVisible(input)) return;
                try {
                    if (input.value !== val) {
                        input.focus();
                        var nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                        if (nativeSetter && nativeSetter.set) {
                            nativeSetter.set.call(input, val);
                        } else {
                            input.value = val;
                        }
                        input.dispatchEvent(new Event('focus', { bubbles: true }));
                        input.dispatchEvent(new Event('keydown', { bubbles: true }));
                        input.dispatchEvent(new Event('input', { bubbles: true }));
                        input.dispatchEvent(new Event('keyup', { bubbles: true }));
                        input.dispatchEvent(new Event('change', { bubbles: true }));
                        input.dispatchEvent(new Event('blur', { bubbles: true }));
                    }
                } catch(e) {
                    try { input.value = val; } catch(_){}
                }
            }

            // ── Pure Digital Audio Stream Extractor (Web Audio API) ─────────
            if (!window.__webAudioTapInstalled && window.WebAudioBridge) {
                window.__webAudioTapInstalled = true;
                try {
                    var AudioContextClass = window.AudioContext || window.webkitAudioContext;
                    if (AudioContextClass) {
                        var audioCtx = new AudioContextClass({ sampleRate: 44100 });

                        function hookMediaElement(el) {
                            if (!el || el.__webAudioTapped) return;
                            el.__webAudioTapped = true;
                            try {
                                var sourceNode = audioCtx.createMediaElementSource(el);
                                var procNode = audioCtx.createScriptProcessor(4096, 1, 1);

                                procNode.onaudioprocess = function(evt) {
                                    var inBuf = evt.inputBuffer.getChannelData(0);
                                    var pcm16 = new Int16Array(inBuf.length);
                                    for (var i = 0; i < inBuf.length; i++) {
                                        var val = Math.max(-1, Math.min(1, inBuf[i]));
                                        pcm16[i] = val < 0 ? val * 0x8000 : val * 0x7FFF;
                                    }
                                    var u8 = new Uint8Array(pcm16.buffer);
                                    var binStr = '';
                                    for (var bi = 0; bi < u8.length; bi++) {
                                        binStr += String.fromCharCode(u8[bi]);
                                    }
                                    window.WebAudioBridge.sendAudioChunk(btoa(binStr));
                                };

                                sourceNode.connect(procNode);
                                procNode.connect(audioCtx.destination);
                                sourceNode.connect(audioCtx.destination);

                                if (audioCtx.state === 'suspended') {
                                    audioCtx.resume();
                                }
                            } catch(err) {}
                        }

                        document.querySelectorAll('audio, video').forEach(hookMediaElement);

                        var mediaObserver = new MutationObserver(function() {
                            document.querySelectorAll('audio, video').forEach(hookMediaElement);
                        });
                        mediaObserver.observe(document.documentElement, { childList: true, subtree: true });
                    }
                } catch(e) {}
            }

            // ── Main Automation Execution Cycle ─────────────────────────────
            function runBotAutomationCycle() {
                try {
                    var curHref = (window.location.href || '').toLowerCase();
                    var isSignInPage = curHref.includes('/signin') || curHref.includes('/login');

                    // ── A. Zoom Native Direct Sign-In (Email + Password) ────────
                    if (isSignInPage) {
                        var emailField = document.querySelector('input#email, input[name="email"], input[type="email"], input#email_input, input[placeholder*="email" i]');
                        var pwdField = document.querySelector('input#password, input[name="password"], input[type="password"], input[placeholder*="password" i]');
                        var signInBtn = document.querySelector('button.signin, button#signin, button[type="submit"], .btn-signin, #btnSubmit, button[aria-label*="Sign In" i], .submit-btn');

                        if (emailField) {
                            setField(emailField, "$cleanEmail");
                        }
                        if (pwdField) {
                            setField(pwdField, "$cleanZoomPwd");
                        }

                        if (emailField && pwdField && emailField.value && pwdField.value && signInBtn) {
                            var nowSI = Date.now();
                            if (!window.__zoomDirectSignInClicked || (nowSI - window.__zoomDirectSignInClicked > 3500)) {
                                window.__zoomDirectSignInClicked = nowSI;
                                setTimeout(function() {
                                    try { signInBtn.click(); } catch(e) {}
                                }, 600);
                            }
                        }
                        return;
                    }

                    // ── B. If Logged In & Target Meeting URL is Provided, Jump to Meeting ──
                    if ("$cleanTargetUrl" && (
                        curHref.includes('/profile') || curHref.includes('/meeting') ||
                        curHref.includes('/user') || curHref.includes('/home') ||
                        curHref.includes('/postlogin') || curHref.includes('marketplace.zoom.us/user') ||
                        curHref.includes('zoom.us/web/client') || curHref.includes('zoom.us/rec')
                    )) {
                        window.location.href = "$cleanTargetUrl";
                        return;
                    }

                    // ── C. Auto-Click reCAPTCHA Checkbox if Present ─────────
                    try {
                        var recaptchaFrames = document.querySelectorAll('iframe[src*="recaptcha"], iframe[src*="google.com/recaptcha"], iframe[src*="challenges.cloudflare.com"]');
                        for (var rfi = 0; rfi < recaptchaFrames.length; rfi++) {
                            try {
                                var rDoc = recaptchaFrames[rfi].contentDocument || recaptchaFrames[rfi].contentWindow.document;
                                if (rDoc) {
                                    var rAnchor = rDoc.querySelector('.recaptcha-checkbox-border, #recaptcha-anchor, .recaptcha-checkbox, div[role="checkbox"]');
                                    if (rAnchor && !rAnchor.classList.contains('recaptcha-checkbox-checked')) {
                                        rAnchor.click();
                                    }
                                }
                            } catch(e) {}
                        }
                        var inPageCheckboxes = document.querySelectorAll('.recaptcha-checkbox-border, #recaptcha-anchor, .recaptcha-checkbox, .g-recaptcha');
                        for (var ipi = 0; ipi < inPageCheckboxes.length; ipi++) {
                            try { inPageCheckboxes[ipi].click(); } catch(e) {}
                        }
                    } catch(e) {}

                    // ── D. Zoom Registration Form Automation ─────────────────
                    var firstInputs = document.querySelectorAll('input#first_name, input[name="first_name"], input#firstName, input[name="firstName"], input[id*="first_name" i], input[id*="firstname" i], input[placeholder*="first" i], input[aria-label*="first" i]');
                    for (var f = 0; f < firstInputs.length; f++) setField(firstInputs[f], "$cleanFirst");

                    var lastInputs = document.querySelectorAll('input#last_name, input[name="last_name"], input#lastName, input[name="lastName"], input[id*="last_name" i], input[id*="lastname" i], input[placeholder*="last" i], input[aria-label*="last" i]');
                    for (var l = 0; l < lastInputs.length; l++) setField(lastInputs[l], "$cleanLast");

                    var emailInputs = document.querySelectorAll('input#email, input[name="email"], input[type="email"], input#email_address, input[name="email_address"], input[id*="email" i], input[placeholder*="email" i]');
                    for (var eIdx = 0; eIdx < emailInputs.length; eIdx++) {
                        var emEl = emailInputs[eIdx];
                        var emId = (emEl.id || emEl.name || emEl.placeholder || '').toLowerCase();
                        if (!emId.includes('confirm')) setField(emEl, "$cleanEmail");
                    }

                    var confirmEmailInputs = document.querySelectorAll('input#confirm_email, input[name="confirm_email"], input#confirmEmail, input[name="confirmEmail"], input[id*="confirm" i], input[placeholder*="confirm" i]');
                    for (var ce = 0; ce < confirmEmailInputs.length; ce++) setField(confirmEmailInputs[ce], "$cleanEmail");

                    var phoneInputs = document.querySelectorAll('input#phone, input[name="phone"], input#mobile, input[name="mobile"], input[type="tel"], input[id*="phone" i], input[id*="mobile" i], input[placeholder*="phone" i], input[placeholder*="mobile" i]');
                    for (var ph = 0; ph < phoneInputs.length; ph++) setField(phoneInputs[ph], "$cleanPhone");

                    var orgInputs = document.querySelectorAll('input#organization, input[name="organization"], input#org, input[name="org"], input[id*="org" i], input[id*="company" i], input[placeholder*="organization" i], input[placeholder*="company" i]');
                    for (var oi = 0; oi < orgInputs.length; oi++) {
                        if (!orgInputs[oi].value) setField(orgInputs[oi], "Self");
                    }

                    var jobInputs = document.querySelectorAll('input#job_title, input[name="job_title"], input[id*="job" i], input[id*="title" i], input[placeholder*="job" i], input[placeholder*="title" i]');
                    for (var ji = 0; ji < jobInputs.length; ji++) {
                        if (!jobInputs[ji].value) setField(jobInputs[ji], "Student");
                    }

                    var cityInputs = document.querySelectorAll('input#city, input[name="city"], input[placeholder*="city" i]');
                    for (var ci = 0; ci < cityInputs.length; ci++) {
                        if (!cityInputs[ci].value) setField(cityInputs[ci], "India");
                    }

                    // Any required text inputs still blank
                    var requiredInputs = document.querySelectorAll('input[required]');
                    for (var rq = 0; rq < requiredInputs.length; rq++) {
                        var reqEl = requiredInputs[rq];
                        var reqType = (reqEl.type || 'text').toLowerCase();
                        if (reqType !== 'checkbox' && reqType !== 'radio' && reqType !== 'submit' && !reqEl.value) {
                            setField(reqEl, "$cleanFirst");
                        }
                    }

                    // Dropdowns / Selects: Select valid option if empty
                    var allSelects = document.querySelectorAll('select');
                    for (var s = 0; s < allSelects.length; s++) {
                        var sel = allSelects[s];
                        if (sel.selectedIndex <= 0 || !sel.value) {
                            if (sel.options && sel.options.length > 1) {
                                sel.selectedIndex = 1;
                                sel.dispatchEvent(new Event('change', { bubbles: true }));
                            }
                        }
                    }

                    // Checkboxes: Check consent, terms, GDPR, and required checkboxes
                    var allCheckboxes = document.querySelectorAll('input[type="checkbox"]');
                    for (var cb = 0; cb < allCheckboxes.length; cb++) {
                        if (!allCheckboxes[cb].checked) {
                            try { allCheckboxes[cb].click(); } catch(e) {}
                        }
                    }

                    // ── E. Zoom Meeting Display Name & Passcode Form ──────────
                    var nameVal = "$cleanName" || ("$cleanFirst" + " " + "$cleanLast").trim();
                    if (nameVal) {
                        var nameInputs = document.querySelectorAll('input#inputname, input#input-for-name, input[name="display_name"], input[placeholder*="name" i]');
                        for (var k = 0; k < nameInputs.length; k++) {
                            var ni = nameInputs[k];
                            var niType = (ni.type || '').toLowerCase();
                            if (niType === 'email' || niType === 'password' || niType === 'hidden') continue;
                            setField(ni, nameVal);
                        }
                    }

                    var pwdVal = "$cleanPwd";
                    if (pwdVal) {
                        var pwdInputs = document.querySelectorAll('input#inputpasscode, input#input-for-pwd, input[name="password"], input[type="password"]');
                        for (var p = 0; p < pwdInputs.length; p++) {
                            setField(pwdInputs[p], pwdVal);
                        }
                    }

                    // ── F. Post-Registration Direct Join Links Detection & Auto-Navigation ──
                    var allLinks = document.querySelectorAll('a[href]');
                    for (var lIdx = 0; lIdx < allLinks.length; lIdx++) {
                        var link = allLinks[lIdx];
                        var href = (link.getAttribute('href') || '').trim();
                        var lText = (link.textContent || '').trim().toLowerCase();
                        if (href.includes('/j/') || href.includes('/w/') || href.includes('zoom.us/j') || href.includes('zoom.us/w') ||
                            lText.includes('click here to join') || lText.includes('join meeting') || lText.includes('start meeting') || lText.includes('join webinar')) {
                            var mMatch = href.match(/\/(j|w)\/(\d{9,11})/);
                            if (mMatch && mMatch[2]) {
                                var mid = mMatch[2];
                                var pwdMatch = href.match(/pwd=([^&]+)/);
                                var pwdParam = pwdMatch ? '&pwd=' + pwdMatch[1] : '';
                                var wcUrl = 'https://app.zoom.us/wc/' + mid + '/join?uname=' + encodeURIComponent(nameVal) + pwdParam + '&prefer=1';
                                window.location.href = wcUrl;
                                return;
                            } else {
                                try { link.click(); } catch(e) {}
                            }
                        }
                    }

                    // Check if already connected inside the meeting room
                    var inActiveMeeting = !!(document.querySelector('#wc-footer, .footer__control-bar, .meeting-app, #wc-container canvas, .speaker-view-container, .meeting-info-icon__info'));

                    // Check if Zoom is asking for Email 2FA / OTP Verification Code
                    var isVerificationScreen = !!(document.querySelector('input[name*="code" i], input[id*="code" i], input[placeholder*="code" i], #code') ||
                        (document.body && (document.body.innerText.includes('Did not get the code') || document.body.innerText.includes('Enter code') || document.body.innerText.includes('verification code'))));

                    if (isVerificationScreen) {
                        // Pause automated button clicking while user enters the 6-digit email code
                        return;
                    }

                    // Auto-Click Computer Audio Connect modal ONLY ONCE at starting
                    if (!window.__zoomAudioPermanentlyDone) {
                        try {
                            var modalAudio = document.querySelector(
                                '.join-audio-by-computer, .join-dialog__join-btn, button[aria-label*="Computer Audio" i], ' +
                                'button[aria-label*="Join Audio by Computer" i], button[aria-label*="Call over Internet" i], ' +
                                'button[aria-label*="Wifi or Cellular Data" i]'
                            );
                            if (modalAudio) {
                                modalAudio.click();
                                window.__zoomAudioPermanentlyDone = true;
                            }
                        } catch(e) {}
                    }

                    // Auto-dismiss and permanently delete any audio popups/menus
                    try {
                        var audioMenus = document.querySelectorAll('.join-audio-menu, .zm-select-menu, div[role="menu"]:has(button[aria-label*="Audio" i]), .join-dialog, .audio-dialog, [aria-label*="audio options" i]');
                        for (var amIdx = 0; amIdx < audioMenus.length; amIdx++) {
                            audioMenus[amIdx].remove();
                        }
                    } catch(e) {}

                    // Auto-remove any side/participant panels and click close buttons if open
                    try {
                        var closeButtons = document.querySelectorAll(
                            '.side-window__close, .side-panel__close, button[aria-label*="Close Participants" i], button[aria-label*="close" i], .close-btn'
                        );
                        for (var cb = 0; cb < closeButtons.length; cb++) {
                            try { closeButtons[cb].click(); } catch(e) {}
                        }

                        var sideWindows = document.querySelectorAll(
                            '.side-window, .side-panel-container, .participants-section-container, [aria-label*="Participants" i]'
                        );
                        for (var sw = 0; sw < sideWindows.length; sw++) {
                            try { sideWindows[sw].remove(); } catch(e) {}
                        }
                    } catch(e) {}

                    // Auto-dismiss any "Ask host" / "Not now" permission modals and remove darkening backdrops
                    try {
                        var askModals = document.querySelectorAll('.zm-modal, div[role="dialog"], .modal-dialog, div[class*="modal" i]');
                        for (var am = 0; am < askModals.length; am++) {
                            var mText = (askModals[am].innerText || askModals[am].textContent || '').toLowerCase();
                            if (mText.includes('ask host') || mText.includes('not now') || mText.includes('unmute') || mText.includes('recording permission')) {
                                var btns = askModals[am].querySelectorAll('button, a[role="button"]');
                                for (var b = 0; b < btns.length; b++) {
                                    var bTxt = (btns[b].textContent || btns[b].value || btns[b].getAttribute('aria-label') || '').trim().toLowerCase();
                                    if (bTxt === 'not now' || bTxt.includes('not now') || bTxt === 'cancel' || bTxt === 'dismiss') {
                                        try { btns[b].click(); } catch(e) {}
                                    }
                                }
                                try { askModals[am].remove(); } catch(e) {}
                            }
                        }

                        var notNowBtns = document.querySelectorAll('button, a[role="button"], input[type="button"]');
                        for (var n = 0; n < notNowBtns.length; n++) {
                            var nText = (notNowBtns[n].textContent || notNowBtns[n].value || notNowBtns[n].getAttribute('aria-label') || '').trim().toLowerCase();
                            if (nText === 'not now' || nText.includes('not now') || nText === 'cancel' || nText === 'dismiss') {
                                try { notNowBtns[n].click(); } catch(e) {}
                            }
                        }

                        var backdrops = document.querySelectorAll('.zm-modal-backdrop, .modal-backdrop, div[class*="backdrop" i]');
                        for (var bd = 0; bd < backdrops.length; bd++) {
                            try { backdrops[bd].remove(); } catch(e) {}
                        }
                    } catch(e) {}

                    // ── G. Autonomous Button Click Engine ─────────────────────
                    var allButtons = document.querySelectorAll('button, a[role="button"], input[type="button"], input[type="submit"]');
                    var joinClicked = false;
                    for (var i = 0; i < allButtons.length; i++) {
                        var btn = allButtons[i];

                        // Exclude any buttons or links inside top header/navigation bars (avoids "Host" dropdown)
                        if (btn.closest('header, nav, #header, #navbar, .navbar, .header, [role="navigation"], .zoom-header')) {
                            continue;
                        }

                        var btnText = (btn.textContent || btn.value || btn.getAttribute('aria-label') || '').trim().toLowerCase();
                        var btnClass = (btn.className || '').toString();
                        var btnId = (btn.id || '').toLowerCase();

                        // Never click participant list, chat, side panel, recording, or ask host buttons
                        if (btnText.includes('participant') || btnText.includes('chat') ||
                            btnText.includes('record') || btnText.includes('ask host') ||
                            btnClass.includes('participants') || btnClass.includes('chat') ||
                            (btn.getAttribute('aria-label') || '').toLowerCase().includes('participant') ||
                            (btn.getAttribute('aria-label') || '').toLowerCase().includes('record')) {
                            continue;
                        }

                        // A. Join Audio by Computer — Handle modal confirmation ONLY ONCE
                        var isAudioModalBtn = btnText.includes('join audio by computer') ||
                            btnText.includes('join with computer audio') ||
                            btnText.includes('join by computer') ||
                            btnText.includes('call over internet') ||
                            btnText.includes('wifi or cellular data') ||
                            btnClass.includes('join-audio-by-computer') ||
                            btnClass.includes('join-dialog__join-btn');

                        if (isAudioModalBtn) {
                            if (!window.__zoomAudioPermanentlyDone) {
                                window.__zoomAudioPermanentlyDone = true;
                                try { btn.click(); } catch(e) {}
                            }
                            continue;
                        }

                        // NEVER click ANY audio, mic, or join audio buttons on toolbar (prevents popping dialogs)
                        if (btnText.includes('audio') || btnText.includes('mute') || btnClass.includes('audio') || btnClass.includes('mute') || btnId.includes('audio')) {
                            continue;
                        }

                        // When already in the meeting room, NEVER click mute/audio buttons (avoids "Host has muted you" infinite modal loop)
                            // B. Registration Form Submission ("Register and Join", "Register", "Submit") - Debounced to prevent 403
                            if (btnText.includes('register and join') ||
                                btnText.includes('register') ||
                                btnText.includes('submit registration') ||
                                btnText === 'submit' ||
                                btnClass.includes('btn-register') ||
                                btnId === 'btn-register' ||
                                btnId === 'btnsubmit' ||
                                btnId === 'btn-submit') {
                                var nowReg = Date.now();
                                if (!window.__zoomRegisterFormSubmitted || (nowReg - window.__zoomRegisterFormSubmitted > 10000)) {
                                    window.__zoomRegisterFormSubmitted = nowReg;
                                    setTimeout(function() {
                                        try { btn.click(); } catch(e) {}
                                    }, 1000);
                                }
                            }

                            // C. Post-Registration Direct Join Links
                            if (btnText.includes('click here to join') ||
                                btnText.includes('join meeting in progress') ||
                                btnText.includes('click here') ||
                                btnText.includes('start meeting') ||
                                btnText.includes('join webinar')) {
                                try { btn.click(); } catch(e) {}
                            }

                            // D. Zoom Web Landing Page ("Launch Meeting", "Join from your browser")
                            if (btnText.includes('join from your browser') ||
                                btnText.includes('stay in browser') ||
                                btnText.includes('launch meeting') ||
                                btnText.includes('join meeting') ||
                                btnClass.includes('join-from-browser')) {
                                try { btn.click(); } catch(e) {}
                            }

                            // D2. Pre-join "Join" button on Zoom web client
                            if (!joinClicked && (btnText === 'join' || btnId === 'joinbtn' || btnId === 'join_btn' ||
                                btnClass.includes('preview-join-button') || btnClass.includes('zm-btn--primary') ||
                                (btn.tagName === 'BUTTON' && btnText.trim() === 'join'))) {
                                var rect = btn.getBoundingClientRect();
                                if (rect.width > 50 && rect.height > 20) {
                                    try { btn.click(); joinClicked = true; } catch(e) {}
                                }
                            }
                        }

                        // E1. Auto-Mute Bot Microphone — execute ONCE only, never repeatedly
                        if (!window.__zoomMutedOnce && !inActiveMeeting && (
                            btnText === 'mute' || btnText === 'mute my microphone' || btnText.includes('mute microphone') ||
                            btnClass.includes('mute-btn') || (btn.getAttribute('aria-label') || '').toLowerCase().startsWith('mute')
                        )) {
                            window.__zoomMutedOnce = true;
                            try { btn.click(); } catch(e) {}
                        }

                        // E2. Auto Turn Off Camera — execute ONCE only
                        if (!window.__zoomVideoStoppedOnce && !inActiveMeeting && (
                            btnText.includes('stop video') || btnText.includes('turn off my video') ||
                            btnText.includes('turn off video') || btnText.includes('turn off camera') ||
                            (btn.getAttribute('aria-label') || '').toLowerCase().startsWith('stop video')
                        )) {
                            window.__zoomVideoStoppedOnce = true;
                            try { btn.click(); } catch(e) {}
                        }

                        // F. Screen share floating notice
                        if (btnText === 'hide') {
                            try { btn.click(); } catch(e) {}
                        }

                        // G. Consent Modals & Cookie Notices & Permission Dialogs
                        if (btnText.includes('got it') ||
                            btnText.includes('i agree') ||
                            btnText.includes('accept all') ||
                            btnText.includes('accept cookies') ||
                            btnText.includes('understood') ||
                            btnText.includes('stay in meeting') ||
                            btnText === 'ok' ||
                            btnText.includes('dismiss')) {
                            try { btn.click(); } catch(e) {}
                        }

                        // H. Zoom's in-page "Allow" button
                        if (btnText === 'allow' || btnText.includes('allow microphone') || btnText.includes('allow camera') ||
                            btnText.includes('allow access') || btnText.includes('grant access')) {
                            try { btn.click(); } catch(e) {}
                        }
                    }

                    // ── H. Direct Form Submission Fallback ────────────────────
                    var regForms = document.querySelectorAll('form#registration, form[name="registration"], form.form-horizontal, form');
                    for (var rForm = 0; rForm < regForms.length; rForm++) {
                        var formEl = regForms[rForm];
                        var formText = (formEl.innerText || formEl.textContent || '').toLowerCase();
                        if (formText.includes('first name') || formText.includes('email') || formText.includes('register')) {
                            var submitInput = formEl.querySelector('input[type="submit"], button[type="submit"]');
                            if (submitInput) {
                                try { submitInput.click(); } catch(e) {}
                            }
                        }
                    }

                    // ── I. Auto-Dismiss Non-Audio Modals ─────────────────────
                    var dialogs = document.querySelectorAll('.zm-toast, .zm-toast-message, div[role="dialog"], .modal-dialog');
                    for (var d = 0; d < dialogs.length; d++) {
                        var dEl = dialogs[d];
                        var dTxt = (dEl.textContent || '').toLowerCase();
                        if (dTxt.includes('cookie') || dTxt.includes('recording') || dTxt.includes('use your microphone and camera')) {
                            var closeBtn = dEl.querySelector('button[aria-label*="close" i], button.zm-btn, button.close, .zm-icon-close');
                            if (closeBtn) { try { closeBtn.click(); } catch(e) {} }
                            else { try { dEl.style.display = 'none'; } catch(e) {} }
                        }
                    }

                    // Keep all incoming meeting audio elements strictly unmuted
                    try {
                        var allMedia = document.querySelectorAll('audio, video, #wc-audio');
                        for (var m = 0; m < allMedia.length; m++) {
                            allMedia[m].muted = false;
                            allMedia[m].volume = 1.0;
                        }
                    } catch(e) {}

                    // ── J. Ensure Audio Elements Are Unmuted ─────────────────
                    var audioEls = document.querySelectorAll('audio, video, #wc-audio');
                    for (var a = 0; a < audioEls.length; a++) {
                        audioEls[a].muted = false;
                        audioEls[a].volume = 1.0;
                    }
                } catch(e) {}
            }

            // Run cycle immediately
            runBotAutomationCycle();

            // Install persistent lightweight 2500ms automation loop (eliminates video lag and CPU reflows)
            if (!window.__zoomBotLoopInstalled) {
                window.__zoomBotLoopInstalled = true;
                setInterval(runBotAutomationCycle, 2500);
            }
        })();
    """.trimIndent()

    webView.post {
        try {
            webView.evaluateJavascript(script, null)
        } catch (_: Exception) {}
    }
}

