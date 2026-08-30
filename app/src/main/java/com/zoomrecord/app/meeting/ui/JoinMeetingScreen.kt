package com.zoomrecord.app.meeting.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.zoomrecord.app.backend.ServerRecorderClient
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.zoomrecord.app.meeting.BotStatus
import com.zoomrecord.app.meeting.MeetingViewModel
import com.zoomrecord.app.recording.BotMeetingService

/**
 * Screen 4: Join Meeting directly in Bot Mode.
 * Requests internal recording permission and connects to the live Zoom meeting in-app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinMeetingScreen(
    viewModel: MeetingViewModel,
    userName: String?,
    onBack: () -> Unit,
    onJoined: () -> Unit,
    onNavigateToLiveBot: () -> Unit = {},
    onNavigateToRecordings: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val scope = rememberCoroutineScope()
    var isServerLaunching by remember { mutableStateOf(false) }
    var showServerConfigDialog by remember { mutableStateOf(false) }
    var serverIpInput by remember { mutableStateOf(ServerRecorderClient.getBaseUrl(context)) }

    var showLiveBotScreen by remember { mutableStateOf(true) }
    var isBackgroundRecordingActive by remember { mutableStateOf(false) }
    var backgroundElapsedSec by remember { mutableIntStateOf(0) }
    var isStoppingBackground by remember { mutableStateOf(false) }

    LaunchedEffect(isBackgroundRecordingActive) {
        if (isBackgroundRecordingActive) {
            backgroundElapsedSec = 0
            while (isBackgroundRecordingActive) {
                kotlinx.coroutines.delay(1000)
                backgroundElapsedSec++
                if (backgroundElapsedSec % 5 == 0) {
                    val statusRes = ServerRecorderClient.getActiveStatus(context)
                    val status = statusRes.getOrNull()
                    if (status != null && !status.active && backgroundElapsedSec > 10) {
                        isBackgroundRecordingActive = false
                        android.widget.Toast.makeText(context, "Meeting ended! Downloading recorded file...", android.widget.Toast.LENGTH_SHORT).show()
                        val listRes = ServerRecorderClient.fetchRecordings(context)
                        val lastRec = listRes.getOrNull()?.firstOrNull()
                        if (lastRec != null) {
                            ServerRecorderClient.downloadRecording(context, lastRec.id, lastRec.fileName)
                            android.widget.Toast.makeText(context, "✅ Output file saved to device!", android.widget.Toast.LENGTH_LONG).show()
                            onNavigateToRecordings()
                        }
                    }
                }
            }
        }
    }

    val profileStore = remember { com.zoomrecord.app.auth.UserProfileStore(context) }
    val savedProfile = remember { profileStore.getProfile() }

    var zoomEmailInput by remember { mutableStateOf(savedProfile.email) }
    var zoomPasswordInput by remember { mutableStateOf(savedProfile.zoomPassword) }
    var showZoomCredentialsSection by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (savedProfile.isConfigured) {
            viewModel.updateDisplayName(savedProfile.fullName)
        } else if (uiState.displayName.isEmpty()) {
            viewModel.updateDisplayName(userName ?: "Zoom User")
        }
    }

    val saveRecentMeeting = {
        val recentStore = com.zoomrecord.app.meeting.RecentMeetingsStore(context)
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

    val directJoin = {
        saveRecentMeeting()
        onJoined()
    }

    // ── Pre-request Audio & Notification Permissions ───────────────────
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
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

    val requestAudioPermissionAndJoin = {
        val hasAudio = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasAudio) {
            val perms = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(perms.toTypedArray())
        }
        // Directly joins and records in-app (Zero screen cast / MediaProjection popup)
        directJoin()
    }

    // ── Listen for Bot state broadcasts ──────────────────────────────
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val status = intent.getStringExtra(BotMeetingService.EXTRA_BOT_STATUS) ?: "idle"
                val error = intent.getStringExtra(BotMeetingService.EXTRA_ERROR_MESSAGE)
                viewModel.updateBotStatus(status, error)
            }
        }
        val filter = IntentFilter(BotMeetingService.BROADCAST_BOT_STATE)
        ContextCompat.registerReceiver(
            context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Join Meeting",
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
                    IconButton(onClick = { showServerConfigDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Server Setting",
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
                    .height(130.dp)
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
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0D72FF)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Groups,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "MeetPro Autonomous Bot",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        Text(
                            text = "Audio & Video Recording with zero lag",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ── Active Background Recording Card (When Live Screen is Disabled) ───
            if (isBackgroundRecordingActive) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF064E3B)),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF34D399))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "RECORDING IN BACKGROUND",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF34D399)
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = String.format("%02d:%02d", backgroundElapsedSec / 60, backgroundElapsedSec % 60),
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                fontSize = 32.sp
                            )
                        )

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Silent Mode (No Screen View) • Auto-saving file on completion",
                            style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFA7F3D0)),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = {
                                isStoppingBackground = true
                                scope.launch {
                                    ServerRecorderClient.stopRecording(context)
                                    kotlinx.coroutines.delay(1000)
                                    val listRes = ServerRecorderClient.fetchRecordings(context)
                                    val rec = listRes.getOrNull()?.firstOrNull()
                                    if (rec != null) {
                                        ServerRecorderClient.downloadRecording(context, rec.id, rec.fileName)
                                        android.widget.Toast.makeText(context, "✅ Output file saved to device!", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                    isStoppingBackground = false
                                    isBackgroundRecordingActive = false
                                    onNavigateToRecordings()
                                }
                            },
                            enabled = !isStoppingBackground,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(46.dp)
                        ) {
                            Text(
                                text = if (isStoppingBackground) "⏳ Saving File..." else "⏹️ Stop & Output Recorded File",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Meeting Input Header ─────────────────────────────────
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
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = if (uiState.isLinkDetected) Color(0xFF00A651) else MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            // Link Detected Banner
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

            // ── Secondary Input: Password (Optional/Manual) ─────────
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
                label = { Text("Bot Participant Name") },
                leadingIcon = {
                    Icon(Icons.Default.Person, contentDescription = null)
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(14.dp))

            // ── Zoom Account Sign-In Card ─────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = null,
                                tint = Color(0xFF0D72FF),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Zoom Sign-In Account",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                        TextButton(onClick = { showZoomCredentialsSection = !showZoomCredentialsSection }) {
                            Text(if (showZoomCredentialsSection) "Hide" else "Change")
                        }
                    }

                    if (!showZoomCredentialsSection) {
                        Text(
                            text = "Account: $zoomEmailInput",
                            style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF475569))
                        )
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = zoomEmailInput,
                            onValueChange = { zoomEmailInput = it },
                            label = { Text("Zoom Account Email") },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = zoomPasswordInput,
                            onValueChange = { zoomPasswordInput = it },
                            label = { Text("Zoom Account Password") },
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Join Options ─────────────────────────────────────────
            Text(
                text = "Join Options",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            )

            // Option 1: Auto-Record Meeting (Internal MP4)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-Record Meeting (Internal MP4)",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    )
                    Text(
                        text = "Records screen & audio upon entering meeting",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = uiState.autoRecordOnJoin,
                    onCheckedChange = { viewModel.toggleAutoRecordOnJoin(it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF0D72FF)),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Option 2: Don't Connect To Audio (Mute speaker)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Don't Connect To Audio (Silent Mode)",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    )
                    Text(
                        text = "Speaker stays muted so you hear nothing",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = uiState.dontConnectAudio,
                    onCheckedChange = { viewModel.toggleDontConnectAudio(it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF0D72FF)),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Option 3: Turn Off My Video
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Turn Off My Video (Bot Mode)",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    )
                    Text(
                        text = "Joins without showing your camera",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = uiState.turnOffVideo,
                    onCheckedChange = { viewModel.toggleTurnOffVideo(it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF0D72FF)),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Option 4: Show Live Bot Screen / Silent Background Mode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Show Live Bot Screen",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    )
                    Text(
                        text = if (showLiveBotScreen) "Live video preview visible while recording" else "Silent background mode: saves output file directly when finished",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = showLiveBotScreen,
                    onCheckedChange = { showLiveBotScreen = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF0D72FF)),
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ── Primary Action 1: Cloud Server Recorder (Flawless Audio & Video) ───
            Button(
                onClick = {
                    val link = uiState.meetingLinkOrId.ifBlank { uiState.meetingNumber }
                    if (link.isNotBlank()) {
                        saveRecentMeeting()
                        isServerLaunching = true
                        scope.launch {
                            val res = ServerRecorderClient.startRecording(
                                context = context,
                                meetingUrl = link,
                                passcode = uiState.password.ifBlank { null },
                                displayName = uiState.displayName.ifBlank { "Meeting Assistant" },
                                zoomEmail = zoomEmailInput.ifBlank { null },
                                zoomPassword = zoomPasswordInput.ifBlank { null }
                            )
                            isServerLaunching = false
                            if (res.isSuccess) {
                                if (showLiveBotScreen) {
                                    android.widget.Toast.makeText(context, "🚀 Sent to Cloud Recorder! Opening Live Screen...", android.widget.Toast.LENGTH_SHORT).show()
                                    onNavigateToLiveBot()
                                } else {
                                    isBackgroundRecordingActive = true
                                    android.widget.Toast.makeText(context, "🟢 Cloud Recording Active! Output file will download to your phone when finished.", android.widget.Toast.LENGTH_LONG).show()
                                }
                            } else {
                                android.widget.Toast.makeText(context, "Failed: ${res.exceptionOrNull()?.message}", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
                enabled = viewModel.canStartBot && !isServerLaunching,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .shadow(12.dp, shape = RoundedCornerShape(16.dp), spotColor = Color(0xFF059669)),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isServerLaunching) "⏳ Sending to Cloud..." else "🚀 Record via Cloud Bot (Flawless Audio/Video)",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ── Primary Action Button: Direct In-App Join & Record (No Screen Cast dialog) ──
            Button(
                onClick = {
                    requestAudioPermissionAndJoin()
                },
                enabled = viewModel.canStartBot && uiState.hasEnoughStorage,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .shadow(12.dp, shape = RoundedCornerShape(16.dp), spotColor = Color(0xFF0D72FF)),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(Color(0xFF0E72FF), Color(0xFF0058EB))
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "🤖 Launch Local Bot & Record (In-App)",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
        }

        if (showServerConfigDialog) {
            AlertDialog(
                onDismissRequest = { showServerConfigDialog = false },
                title = { Text("Website Server URL") },
                text = {
                    Column {
                        Text("Enter the Website Server address (e.g. http://10.0.2.2:3000 for emulator, or your Wi-Fi IP for phone):", fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = serverIpInput,
                            onValueChange = { serverIpInput = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        ServerRecorderClient.setBaseUrl(context, serverIpInput)
                        showServerConfigDialog = false
                        android.widget.Toast.makeText(context, "Saved server URL", android.widget.Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showServerConfigDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
