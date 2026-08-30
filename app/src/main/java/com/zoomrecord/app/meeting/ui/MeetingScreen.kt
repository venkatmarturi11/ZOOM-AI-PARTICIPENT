package com.zoomrecord.app.meeting.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.zoomrecord.app.library.ui.formatSize
import com.zoomrecord.app.meeting.BotStatus
import com.zoomrecord.app.meeting.MeetingViewModel
import com.zoomrecord.app.recording.BotMeetingService

/**
 * Main meeting screen for Bot Mode:
 * - Enter meeting ID & passcode
 * - Single "Start Bot" button that launches silent join + background record
 * - Mutes speaker automatically
 * - Can be minimized or screen turned off while recording continues
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingScreen(
    viewModel: MeetingViewModel,
    userName: String?,
    onNavigateToRecordings: () -> Unit,
    onSignOut: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Pre-fill display name from Google profile
    if (uiState.displayName.isEmpty() && userName != null) {
        viewModel.updateDisplayName(userName)
    }

    val isRunning = uiState.botStatus == BotStatus.RECORDING || uiState.botStatus == BotStatus.JOINING

    // ── Screen capture permission launcher for Bot ───────────────────
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val svcIntent = Intent(context, BotMeetingService::class.java).apply {
                action = BotMeetingService.ACTION_START_BOT
                putExtra(BotMeetingService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(BotMeetingService.EXTRA_RESULT_DATA, result.data)
                putExtra(BotMeetingService.EXTRA_MEETING_NUMBER, uiState.meetingNumber)
                putExtra(BotMeetingService.EXTRA_MEETING_PASSWORD, uiState.password)
                putExtra(BotMeetingService.EXTRA_DISPLAY_NAME, uiState.displayName)
            }
            ContextCompat.startForegroundService(context, svcIntent)
        }
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
                title = { Text("ZoomRecord Bot") },
                actions = {
                    IconButton(onClick = onNavigateToRecordings) {
                        Icon(Icons.Default.VideoLibrary, contentDescription = "Recordings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Meeting Details for Bot",
                style = MaterialTheme.typography.titleLarge,
            )

            OutlinedTextField(
                value = uiState.meetingNumber,
                onValueChange = { viewModel.updateMeetingNumber(it) },
                label = { Text("Meeting Number / ID") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = uiState.password,
                onValueChange = { viewModel.updatePassword(it) },
                label = { Text("Meeting Passcode") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = uiState.displayName,
                onValueChange = { viewModel.updateDisplayName(it) },
                label = { Text("Bot Participant Name") },
                singleLine = true,
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth(),
            )

            // ── Storage Estimate ─────────────────────────────────────
            StorageEstimateBanner(
                availableBytes = uiState.availableBytes,
                estimate30Min = uiState.estimate30MinBytes,
                estimate60Min = uiState.estimate60MinBytes,
                hasEnoughStorage = uiState.hasEnoughStorage,
            )

            // ── Bot Controls (Single Action) ─────────────────────────
            val buttonColor by animateColorAsState(
                targetValue = if (isRunning) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                label = "buttonColor",
            )

            FilledTonalButton(
                onClick = {
                    if (isRunning) {
                        val stopIntent = Intent(context, BotMeetingService::class.java).apply {
                            action = BotMeetingService.ACTION_STOP_BOT
                        }
                        context.startService(stopIntent)
                    } else {
                        val mpm = context.getSystemService(MediaProjectionManager::class.java)
                        projectionLauncher.launch(mpm.createScreenCaptureIntent())
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = buttonColor.copy(alpha = 0.15f),
                    contentColor = buttonColor,
                ),
                enabled = (viewModel.canStartBot && uiState.hasEnoughStorage) || isRunning,
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = when (uiState.botStatus) {
                        BotStatus.JOINING -> "Bot Joining… (Tap to Cancel)"
                        BotStatus.RECORDING -> "Stop Bot & Finish Recording"
                        BotStatus.STOPPING -> "Stopping…"
                        else -> "Send Bot to Record (Silent)"
                    },
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Text(
                text = "ℹ️ Silent Mode: Your speaker is muted so you won't hear anything. The bot captures audio internally and records in the background.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Error display
            uiState.botError?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Sign out
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                androidx.compose.material3.TextButton(onClick = onSignOut) {
                    Text("Sign Out", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Storage estimate card shown before recording starts.
 */
@Composable
private fun StorageEstimateBanner(
    availableBytes: Long,
    estimate30Min: Long,
    estimate60Min: Long,
    hasEnoughStorage: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (hasEnoughStorage) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Available storage: ${formatSize(availableBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (hasEnoughStorage) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                },
            )
            Text(
                text = "30 min ≈ ${formatSize(estimate30Min)} • 60 min ≈ ${formatSize(estimate60Min)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp),
            )
            if (!hasEnoughStorage) {
                Text(
                    text = "Not enough storage for a full recording",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
