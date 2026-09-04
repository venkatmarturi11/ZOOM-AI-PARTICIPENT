package com.zoomrecord.app.meeting.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import com.zoomrecord.app.backend.ServerRecorderClient
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import com.zoomrecord.app.meeting.MeetingViewModel
import com.zoomrecord.app.meeting.RecentMeetingsStore
import com.zoomrecord.app.recording.FloatingRecordingOverlay
import com.zoomrecord.app.recording.ScreenRecordService
import com.zoomrecord.app.zoom.ZoomAppLauncher
import com.zoomrecord.app.zoom.ZoomBotAccessibilityService

/**
 * Clean, modern Screen to parse Zoom meeting invites and launch the official Zoom app
 * while capturing 1080p best quality screen and pristine internal audio with a floating HUD.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinMeetingScreen(
    viewModel: MeetingViewModel,
    userName: String?,
    onBack: () -> Unit,
    onNavigateToRecordings: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val profileStore = remember { com.zoomrecord.app.auth.UserProfileStore(context) }
    val savedProfile = remember { profileStore.getProfile() }

    LaunchedEffect(Unit) {
        if (savedProfile.isConfigured && savedProfile.fullName.isNotBlank()) {
            viewModel.updateDisplayName(savedProfile.fullName)
        } else if (uiState.displayName.isEmpty()) {
            viewModel.updateDisplayName(userName ?: "Meeting Participant")
        }
        // Warm up the 24/7 free cloud bot server in background so it's instantly ready
        ServerRecorderClient.warmUpCloudServer()
    }

    val saveRecentMeeting = {
        val recentStore = RecentMeetingsStore(context)
        val mid = uiState.meetingNumber
        val link = uiState.meetingLinkOrId
        val title = if (link.contains("zoom.us")) "Zoom Meeting $mid" else "Meeting $mid"
        recentStore.addMeeting(
            meetingId = mid,
            title = title,
            link = link,
            password = uiState.password,
            displayName = uiState.displayName,
        )
    }

    val isZoomInstalled = remember { ZoomAppLauncher.isZoomInstalled(context) }
    var isLaunchingZoomAndRecording by remember { mutableStateOf(false) }
    var showOverlayPermissionDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var isCloudLaunching by remember { mutableStateOf(false) }
    var activeCloudMeetingId by remember { mutableStateOf<String?>(null) }

    var isBotAccessibilityEnabled by remember {
        mutableStateOf(ZoomBotAccessibilityService.isServiceEnabled(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isBotAccessibilityEnabled = ZoomBotAccessibilityService.isServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val armBotAssistant = {
        ZoomBotAccessibilityService.armBot(
            context = context,
            meetingNumber = uiState.meetingNumber,
            password = uiState.password,
            displayName = uiState.displayName,
            firstName = savedProfile.firstName,
            lastName = savedProfile.lastName,
            email = savedProfile.email,
            phone = savedProfile.phone,
            country = "India",
            turnOffVideo = uiState.turnOffVideo,
            dontConnectAudio = uiState.dontConnectAudio,
            speakerOutputEnabled = uiState.speakerOutputEnabled,
        )
    }

    val mediaProjectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            saveRecentMeeting()
            armBotAssistant()

            // 1. Start foreground ScreenRecordService with MediaProjection data
            val startIntent = Intent(context, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_START
                putExtra(ScreenRecordService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenRecordService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenRecordService.EXTRA_SHOW_FLOATING_OVERLAY, uiState.showFloatingHud)
                putExtra(ScreenRecordService.EXTRA_AUDIO_BOOST, uiState.audioBoostEnabled)
                putExtra(ScreenRecordService.EXTRA_SPEAKER_OUTPUT_ENABLED, uiState.speakerOutputEnabled)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(startIntent)
            } else {
                context.startService(startIntent)
            }

            // 2. Immediately launch Zoom Cloud Meetings native app
            val launchResult = ZoomAppLauncher.launchZoom(
                context = context,
                input = uiState.meetingInput,
                meetingNumber = uiState.meetingNumber,
                password = uiState.password,
                displayName = uiState.displayName
            )

            isLaunchingZoomAndRecording = false

            when (launchResult) {
                ZoomAppLauncher.LaunchResult.LAUNCHED_ZOOM -> {
                    Toast.makeText(context, "🚀 Zoom App launched & Bot active!", Toast.LENGTH_SHORT).show()
                }
                ZoomAppLauncher.LaunchResult.OPENED_PLAY_STORE -> {
                    Toast.makeText(context, "Please install Zoom to join meetings directly", Toast.LENGTH_LONG).show()
                }
                ZoomAppLauncher.LaunchResult.ERROR -> {
                    Toast.makeText(context, "Could not open Zoom. Check meeting details.", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            isLaunchingZoomAndRecording = false
            Toast.makeText(context, "Opening Zoom with Bot Auto-Pilot…", Toast.LENGTH_SHORT).show()
            armBotAssistant()
            ZoomAppLauncher.launchZoom(
                context = context,
                input = uiState.meetingInput,
                meetingNumber = uiState.meetingNumber,
                password = uiState.password,
                displayName = uiState.displayName
            )
        }
    }

    val startZoomAndRecord = {
        isLaunchingZoomAndRecording = true
        armBotAssistant()
        if (uiState.autoRecordOnJoin) {
            if (uiState.showFloatingHud && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !FloatingRecordingOverlay.canDrawOverlay(context)) {
                showOverlayPermissionDialog = true
            } else {
                screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            }
        } else {
            // When toggle is OFF: Do NOT record! Directly launch Zoom with Bot assistant
            saveRecentMeeting()
            val launchResult = ZoomAppLauncher.launchZoom(
                context = context,
                input = uiState.meetingInput,
                meetingNumber = uiState.meetingNumber,
                password = uiState.password,
                displayName = uiState.displayName
            )
            isLaunchingZoomAndRecording = false
            when (launchResult) {
                ZoomAppLauncher.LaunchResult.LAUNCHED_ZOOM -> {
                    Toast.makeText(context, "🚀 Zoom App launched (Recording disabled by toggle)", Toast.LENGTH_SHORT).show()
                }
                ZoomAppLauncher.LaunchResult.OPENED_PLAY_STORE -> {
                    Toast.makeText(context, "Please install Zoom to join meetings directly", Toast.LENGTH_LONG).show()
                }
                ZoomAppLauncher.LaunchResult.ERROR -> {
                    Toast.makeText(context, "Could not open Zoom. Check meeting details.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ── Pre-request Audio & Notification Permissions ───────────────────
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    LaunchedEffect(Unit) {
        val perms = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val ungranted = perms.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (ungranted.isNotEmpty()) {
            permissionLauncher.launch(ungranted.toTypedArray())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Join Zoom Meeting",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToRecordings) {
                        Icon(
                            imageVector = Icons.Default.VideoLibrary,
                            contentDescription = "Recordings",
                            tint = Color(0xFF0D72FF)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── Top Illustration Card ────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(115.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFFE8F2FF), Color(0xFFD6E8FF))
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0D72FF)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "MeetPro Mobile",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                            ),
                        )
                        Text(
                            text = "Direct Zoom App + 1080p HD Recorder",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ── Native Zoom App Status Indicator ─────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isZoomInstalled) Color(0xFFECFDF5) else Color(0xFFFFFBEB))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (isZoomInstalled) Icons.Default.CheckCircle else Icons.Default.Info,
                    contentDescription = null,
                    tint = if (isZoomInstalled) Color(0xFF059669) else Color(0xFFD97706),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = if (isZoomInstalled) "Official Zoom App Detected" else "Zoom App Not Detected",
                        color = if (isZoomInstalled) Color(0xFF065F46) else Color(0xFF92400E),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (isZoomInstalled) "Will open meeting directly in Zoom & record in HD" else "Will open Play Store to install Zoom first",
                        color = if (isZoomInstalled) Color(0xFF047857) else Color(0xFFB45309),
                        fontSize = 11.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── AI Auto-Pilot Bot Assistant Card ─────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isBotAccessibilityEnabled) Color(0xFFF0FDF4) else Color(0xFFFEF2F2)
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isBotAccessibilityEnabled) Color(0xFFBBF7D0) else Color(0xFFFECACA)
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "🤖 Zoom Auto-Pilot Bot",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (isBotAccessibilityEnabled) Color(0xFF15803D) else Color(0xFFB91C1C)
                            ),
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isBotAccessibilityEnabled) Color(0xFFDCFCE7) else Color(0xFFFEE2E2))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (isBotAccessibilityEnabled) "✓ ACTIVE" else "DISABLED",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isBotAccessibilityEnabled) Color(0xFF16A34A) else Color(0xFFDC2626)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = if (isBotAccessibilityEnabled) {
                            "Bot will automatically enter passcode, fill registration, dismiss popups, & connect audio when Zoom opens."
                        } else {
                            "Bot needs Accessibility permission to auto-join, fill passcodes, dismiss popups, and connect audio in Zoom."
                        },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            color = if (isBotAccessibilityEnabled) Color(0xFF166534) else Color(0xFF991B1B)
                        ),
                    )

                    if (!isBotAccessibilityEnabled) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                Toast.makeText(
                                    context,
                                    "Tap 'Zoom AI Auto-Pilot Bot' and turn it ON",
                                    Toast.LENGTH_LONG
                                ).show()
                                ZoomBotAccessibilityService.openAccessibilitySettings(context)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp),
                        ) {
                            Text(
                                "⚡ Enable Bot in Accessibility Settings",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Enter meeting details to join",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )

            // ── Primary Input: Meeting Link or ID ───────────────────
            OutlinedTextField(
                value = uiState.meetingLinkOrId,
                onValueChange = { viewModel.updateMeetingInput(it) },
                label = { Text("Meeting Link or Meeting ID") },
                placeholder = { Text("https://zoom.us/j/... or 123 456 7890") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = null,
                        tint = if (uiState.isLinkDetected) Color(0xFF00A651) else MaterialTheme.colorScheme.primary,
                    )
                },
                trailingIcon = {
                    if (uiState.isLinkDetected) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Link Detected",
                            tint = Color(0xFF00A651),
                        )
                    } else {
                        IconButton(onClick = {
                            val clip = clipboardManager.getText()?.text
                            if (!clip.isNullOrBlank()) {
                                viewModel.updateMeetingInput(clip)
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = "Paste",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = if (uiState.isLinkDetected) Color(0xFF00A651) else MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            // Link Detected Confirmation Banner
            AnimatedVisibility(visible = uiState.isLinkDetected) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "✓ Extracted ID: ${uiState.meetingNumber}${if (uiState.password.isNotEmpty()) " (Passcode Included)" else ""}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Color(0xFF00A651),
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ── Secondary Input: Password ────────────────────────────
            OutlinedTextField(
                value = uiState.password,
                onValueChange = { viewModel.updatePassword(it) },
                label = { Text("Meeting Passcode (Optional if in link)") },
                placeholder = { Text("e.g. 123456") },
                leadingIcon = {
                    Icon(Icons.Default.Lock, contentDescription = null)
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(14.dp))

            // ── Participant Name ─────────────────────────────────────
            OutlinedTextField(
                value = uiState.displayName,
                onValueChange = { viewModel.updateDisplayName(it) },
                label = { Text("Your Participant Name") },
                leadingIcon = {
                    Icon(Icons.Default.Person, contentDescription = null)
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── Recording Config Hero Card ─────────────────────────────
            val recBorderColor by animateColorAsState(
                if (uiState.autoRecordOnJoin) Color(0xFF0D72FF) else Color(0xFFE2E8F0),
                label = "recBorderColor"
            )
            val recBgColor by animateColorAsState(
                if (uiState.autoRecordOnJoin) Color(0xFFF0F7FF) else Color(0xFFF8FAFC),
                label = "recBgColor"
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = recBgColor),
                border = BorderStroke(1.5.dp, recBorderColor)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    // Header with Title & Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-Record Meeting (1080p)",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A),
                                    fontSize = 16.sp,
                                ),
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (uiState.autoRecordOnJoin)
                                    "Captures full-screen video, speakerphone audio & voice"
                                else
                                    "Recording disabled • Fast direct Zoom launch",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (uiState.autoRecordOnJoin) Color(0xFF2563EB) else Color(0xFF64748B),
                                fontSize = 12.sp,
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = uiState.autoRecordOnJoin,
                            onCheckedChange = { viewModel.toggleAutoRecordOnJoin(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF0D72FF),
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = Color(0xFFCBD5E1),
                            ),
                        )
                    }

                    // Live Status Badge
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (uiState.autoRecordOnJoin) Color(0xFFDBEAFE) else Color(0xFFE2E8F0)
                            )
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = if (uiState.autoRecordOnJoin)
                                "● REC ACTIVE: 1080p HD + Dual Audio"
                            else
                                "○ REC OFF: Join without recording",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (uiState.autoRecordOnJoin) Color(0xFF1D4ED8) else Color(0xFF475569)
                        )
                    }

                    // Expandable Recording Sub-options
                    AnimatedVisibility(visible = uiState.autoRecordOnJoin) {
                        Column {
                            Spacer(modifier = Modifier.height(14.dp))
                            HorizontalDivider(
                                color = Color(0xFFBFDBFE).copy(alpha = 0.6f),
                                thickness = 1.dp
                            )
                            Spacer(modifier = Modifier.height(14.dp))

                            // Sub-option 1: Floating REC HUD Controller
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Floating REC Controller",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp
                                        ),
                                    )
                                    Text(
                                        text = "Draggable timer & Stop button directly over Zoom",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF64748B),
                                        fontSize = 11.sp,
                                    )
                                }
                                Switch(
                                    checked = uiState.showFloatingHud,
                                    onCheckedChange = { viewModel.toggleShowFloatingHud(it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFF0D72FF)
                                    ),
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Sub-option 2: High-Gain Speech Boost (2.5x)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Speech Gain Boost (2.5x)",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp
                                        ),
                                    )
                                    Text(
                                        text = "Amplifies loudspeaker meeting dialogue with distortion limiter",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF64748B),
                                        fontSize = 11.sp,
                                    )
                                }
                                Switch(
                                    checked = uiState.audioBoostEnabled,
                                    onCheckedChange = { viewModel.toggleAudioBoost(it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFF059669)
                                    ),
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ── Meeting Join Preferences Card ─────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Meeting Auto-Pilot Preferences",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1E293B)
                        ),
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // Preference 1: Turn off video
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Turn Off Video on Join",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                ),
                            )
                            Text(
                                text = "Disables camera so you join meeting discreetly",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF64748B),
                                fontSize = 11.sp,
                            )
                        }
                        Switch(
                            checked = uiState.turnOffVideo,
                            onCheckedChange = { viewModel.toggleTurnOffVideo(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF0D72FF)
                            ),
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Preference 2: Connect audio
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-Connect Meeting Audio",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                ),
                            )
                            Text(
                                text = "Bot auto-taps 'Wifi or Cellular Data' to hear meeting speech",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF64748B),
                                fontSize = 11.sp,
                            )
                        }
                        Switch(
                            checked = !uiState.dontConnectAudio,
                            onCheckedChange = { viewModel.toggleDontConnectAudio(!it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF0D72FF)
                            ),
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Preference 3: Mobile Speaker Sound (Default OFF / Silent)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Play Sound via Mobile Speaker",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                ),
                            )
                            Text(
                                text = if (uiState.speakerOutputEnabled)
                                    "Loudspeaker ON: Audio plays out loud into the room"
                                else
                                    "Loudspeaker OFF (Silent): Audio is recorded directly with zero room noise",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (uiState.speakerOutputEnabled) Color(0xFF0D72FF) else Color(0xFF64748B),
                                fontSize = 11.sp,
                            )
                        }
                        Switch(
                            checked = uiState.speakerOutputEnabled,
                            onCheckedChange = { viewModel.toggleSpeakerOutput(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF0D72FF)
                            ),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Audio Capture Guide Card ─────────────────────────────────
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)),
                border = BorderStroke(1.dp, Color(0xFFBBF7D0)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "💡", fontSize = 18.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "How to Capture Audio & Video (100% Working)",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF166534)
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "1. ☁️ Best Method (100% Digital Audio): Tap the Green Button above ('Record via 24/7 Cloud Bot'). It uses our proven zoomrec cloud engine to record meeting video & digital sound directly with zero phone microphone limits!\n\n" +
                               "2. 📱 Phone Screen REC: Ensure Zoom's Microphone permission is ALLOWED ('While using app') in phone settings. Our bot auto-mutes your mic in Zoom, and the CAMCORDER recorder captures the loudspeaker audio with 6.0x speech boost!",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            color = Color(0xFF15803D),
                            lineHeight = 18.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:us.zoom.videomeetings")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                val genericIntent = Intent(Settings.ACTION_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(genericIntent)
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFF16A34A)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF15803D)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Check Zoom Settings (Allow Mic)",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Primary Action 1: Free Cloud Bot Recorder (Method 3: Pure Digital Audio, Volume 0 OK) ───
            val cloudGreen = Color(0xFF059669)
            Button(
                onClick = {
                    val raw = uiState.meetingLinkOrId.ifBlank { uiState.meetingNumber }.trim()
                    val digits = raw.replace("[^0-9]".toRegex(), "")
                    val targetUrl = if (raw.startsWith("http://") || raw.startsWith("https://")) {
                        raw
                    } else if (digits.length in 9..11) {
                        "https://zoom.us/j/$digits"
                    } else {
                        raw
                    }

                    coroutineScope.launch {
                        isCloudLaunching = true
                        val res = ServerRecorderClient.startRecording(
                            context = context,
                            meetingUrl = targetUrl,
                            passcode = uiState.password.ifBlank { null },
                            displayName = uiState.displayName.ifBlank { "Cloud Assistant" }
                        )
                        isCloudLaunching = false
                        if (res.isSuccess) {
                            activeCloudMeetingId = res.getOrNull()?.meetingId
                            Toast.makeText(
                                context,
                                "☁️ Cloud Bot deployed! Recording silently in cloud with 100% digital audio.",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Toast.makeText(
                                context,
                                "Cloud Bot error: ${res.exceptionOrNull()?.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                },
                enabled = viewModel.canStartBot && !isCloudLaunching,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .shadow(16.dp, shape = RoundedCornerShape(16.dp), spotColor = cloudGreen),
                colors = ButtonDefaults.buttonColors(containerColor = cloudGreen),
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (isCloudLaunching) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = if (isCloudLaunching) "⏳ Deploying Cloud Bot (Connecting)..." else "☁️ Record via 24/7 Cloud Bot (No PC Needed)",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                fontSize = 14.sp,
                            ),
                        )
                        Text(
                            text = "100% Free • $0 Cost • Phone Volume 0 / Off • Digital Audio",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 11.sp,
                            ),
                        )
                    }
                }
            }

            if (activeCloudMeetingId != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = {
                        coroutineScope.launch {
                            val res = ServerRecorderClient.stopRecording(context, activeCloudMeetingId)
                            activeCloudMeetingId = null
                            if (res.isSuccess) {
                                Toast.makeText(context, "Cloud recording saved! Check Recordings tab.", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("⏹ Stop Cloud Bot Recording", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ── Primary Action 2: Phone Screen & Speaker Recording ─────────────────
            val buttonColor by animateColorAsState(
                if (uiState.autoRecordOnJoin) Color(0xFF0D72FF) else Color(0xFF0F766E),
                label = "btnColor"
            )

            Button(
                onClick = {
                    startZoomAndRecord()
                },
                enabled = viewModel.canStartBot && !isLaunchingZoomAndRecording,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .shadow(16.dp, shape = RoundedCornerShape(16.dp), spotColor = buttonColor),
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = if (isLaunchingZoomAndRecording) {
                            if (uiState.autoRecordOnJoin) "🚀 Opening Zoom & Starting REC..." else "🚀 Opening Zoom with Auto-Pilot..."
                        } else {
                            if (uiState.autoRecordOnJoin) "📱 Open in Zoom & Record on Phone" else "📱 Open in Zoom (Recording OFF)"
                        },
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            fontSize = 15.sp,
                        ),
                    )
                    Text(
                        text = if (uiState.autoRecordOnJoin)
                            "1080p Screen • Mic Audio • Auto-Pilot"
                        else
                            "Direct Join • Auto-Pilot Assistant",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 11.sp,
                        ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
        }

        // ── Floating Controller Permission Dialog ──────────────────────────
        if (showOverlayPermissionDialog) {
            AlertDialog(
                onDismissRequest = {
                    showOverlayPermissionDialog = false
                    screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                },
                title = {
                    Text(
                        text = "Enable Floating Controller?",
                        fontWeight = FontWeight.Bold,
                    )
                },
                text = {
                    Text(
                        text = "To display the floating REC timer and Stop button directly over the Zoom app, enable 'Display over other apps'.\n\nYou can also skip and use the notification bar to stop recording.",
                        fontSize = 14.sp,
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showOverlayPermissionDialog = false
                            isLaunchingZoomAndRecording = false
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                }
                            } catch (_: Exception) {}
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D72FF))
                    ) {
                        Text("Open Settings", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showOverlayPermissionDialog = false
                            screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                        }
                    ) {
                        Text("Continue without Overlay")
                    }
                }
            )
        }
    }
}
