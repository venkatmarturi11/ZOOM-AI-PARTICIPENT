package com.zoomrecord.app.library.ui

import android.net.Uri
import android.widget.Toast
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.zoomrecord.app.library.RecordingItem
import com.zoomrecord.app.library.RecordingsRepository
import kotlinx.coroutines.delay

/**
 * Playback screen for a single recording.
 * Features 100% working forward (+10s), backward (-10s), seeking, speed controls,
 * and saving/downloading to any user-preferred location on the device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingPlaybackScreen(
    item: RecordingItem,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { RecordingsRepository(context) }

    // ExoPlayer state
    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var totalDurationMs by remember { mutableLongStateOf(if (item.durationMs > 0) item.durationMs else 1L) }
    var isDraggingSlider by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(0f) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var isMuted by remember { mutableStateOf(false) }

    // Dialog state
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Launcher to download / export MP4 to user's preferred directory
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4")
    ) { destUri: Uri? ->
        if (destUri != null) {
            val success = repo.exportToUri(item.uri, destUri)
            if (success) {
                Toast.makeText(context, "Recording saved successfully!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "Failed to save recording to selected location", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val playableUri = remember(item.uri) {
        if (item.uri.scheme == "file") {
            try {
                val file = File(item.uri.path ?: "")
                if (file.exists()) {
                    androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                } else item.uri
            } catch (_: Exception) {
                item.uri
            }
        } else {
            item.uri
        }
    }

    val exoPlayer = remember(playableUri) {
        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context)
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                val mediaItem = MediaItem.fromUri(playableUri)
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                isPlaying = exoPlayer.isPlaying
                if (state == Player.STATE_READY) {
                    val d = exoPlayer.duration
                    if (d > 0) totalDurationMs = d
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("RecordingPlayback", "ExoPlayer playback error", error)
                Toast.makeText(context, "Playback error: ${error.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Periodic time updates for scrubber
    LaunchedEffect(exoPlayer) {
        while (true) {
            if (!isDraggingSlider) {
                currentPositionMs = exoPlayer.currentPosition
                val d = exoPlayer.duration
                if (d > 0) totalDurationMs = d
                sliderValue = if (totalDurationMs > 0) {
                    (currentPositionMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
                } else 0f
            }
            delay(150)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = item.displayName.removeSuffix(".mp4").removeSuffix(".webm"),
                        maxLines = 1,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
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
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Interactive Video Player Surface ─────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .background(Color.Black)
                    .clickable {
                        if (exoPlayer.isPlaying) {
                            exoPlayer.pause()
                        } else {
                            if (exoPlayer.playbackState == Player.STATE_ENDED) {
                                exoPlayer.seekTo(0L)
                            }
                            exoPlayer.play()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false
                            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                // Quick Play/Pause Badge Overlay when paused
                if (!isPlaying) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (exoPlayer.playbackState == Player.STATE_ENDED) Icons.Default.Replay else Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }

            // ── Modern Player Controls Card ──────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141923)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    // Timeline Slider
                    Slider(
                        value = sliderValue,
                        onValueChange = { newValue ->
                            isDraggingSlider = true
                            sliderValue = newValue
                            currentPositionMs = (newValue * totalDurationMs).toLong()
                        },
                        onValueChangeFinished = {
                            exoPlayer.seekTo((sliderValue * totalDurationMs).toLong())
                            isDraggingSlider = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF0D72FF),
                            activeTrackColor = Color(0xFF0D72FF),
                            inactiveTrackColor = Color(0xFF2C384A),
                        ),
                    )

                    // Timestamps
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = formatDuration(currentPositionMs),
                            color = Color(0xFF8E9BAE),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = formatDuration(totalDurationMs),
                            color = Color(0xFF8E9BAE),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Action Buttons Row: [Volume] [-10s] [PLAY/PAUSE] [+10s] [Speed]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Audio Volume Mute/Unmute Toggle
                        IconButton(
                            onClick = {
                                isMuted = !isMuted
                                exoPlayer.volume = if (isMuted) 0f else 1f
                            },
                            modifier = Modifier.size(42.dp),
                        ) {
                            Icon(
                                imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = if (isMuted) "Unmute Audio" else "Mute Audio",
                                tint = if (isMuted) Color(0xFFFF5252) else Color(0xFFBAC7D5),
                                modifier = Modifier.size(22.dp),
                            )
                        }

                        // Rewind -10s
                        IconButton(
                            onClick = {
                                val target = maxOf(0L, exoPlayer.currentPosition - 10_000L)
                                exoPlayer.seekTo(target)
                            },
                            modifier = Modifier.size(44.dp),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.FastRewind,
                                    contentDescription = "Rewind 10s",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp),
                                )
                                Text(
                                    text = "-10s",
                                    color = Color(0xFF8E9BAE),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        // Main Big Play / Pause Button
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(Color(0xFF0D72FF), Color(0xFF0058EB))
                                    )
                                )
                                .clickable {
                                    if (exoPlayer.isPlaying) {
                                        exoPlayer.pause()
                                    } else {
                                        if (exoPlayer.playbackState == Player.STATE_ENDED) {
                                            exoPlayer.seekTo(0L)
                                        }
                                        exoPlayer.play()
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp),
                            )
                        }

                        // Forward +10s
                        IconButton(
                            onClick = {
                                val target = minOf(totalDurationMs, exoPlayer.currentPosition + 10_000L)
                                exoPlayer.seekTo(target)
                            },
                            modifier = Modifier.size(44.dp),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.FastForward,
                                    contentDescription = "Forward 10s",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp),
                                )
                                Text(
                                    text = "+10s",
                                    color = Color(0xFF8E9BAE),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        // Playback Speed Toggle (1.0x -> 1.25x -> 1.5x -> 2.0x)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E2738))
                                .clickable {
                                    playbackSpeed = when (playbackSpeed) {
                                        1.0f -> 1.25f
                                        1.25f -> 1.5f
                                        1.5f -> 2.0f
                                        else -> 1.0f
                                    }
                                    exoPlayer.setPlaybackSpeed(playbackSpeed)
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "${playbackSpeed}x",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            // ── Primary Download & Export Actions ────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                // Button 1: Download to Preferred Folder (Storage Access Framework)
                Button(
                    onClick = {
                        val defaultFileName = "${item.displayName.removeSuffix(".mp4")}.mp4"
                        exportLauncher.launch(defaultFileName)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D72FF)),
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "Choose Folder",
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Download to Preferred Location",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Button 2: Quick Save to Downloads
                OutlinedButton(
                    onClick = {
                        val success = repo.exportToDownloads(item.uri, item.displayName)
                        if (success) {
                            Toast.makeText(context, "Saved to Downloads/MeetProRecordings!", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "Failed to save to Downloads", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Save to Downloads",
                        modifier = Modifier.size(18.dp),
                        tint = Color(0xFF0D72FF),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Quick Save to Downloads Folder",
                        color = Color(0xFF0D72FF),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Recording Details Card ───────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141923)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text(
                        text = "Recording Details",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                        ),
                        color = Color.White,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Duration: ${formatDuration(totalDurationMs)}",
                        color = Color(0xFF8E9BAE),
                        fontSize = 13.sp,
                    )
                    Text(
                        text = "Resolution: ${item.width} × ${item.height} (1080p HD)",
                        color = Color(0xFF8E9BAE),
                        fontSize = 13.sp,
                    )
                    Text(
                        text = "File Size: ${formatSize(item.sizeBytes)}",
                        color = Color(0xFF8E9BAE),
                        fontSize = 13.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Secondary Action Buttons: Share, Rename, Delete ──────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(onClick = {
                    context.startActivity(
                        android.content.Intent.createChooser(
                            repo.shareIntent(item.uri), null
                        )
                    )
                }) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = Color(0xFF0D72FF))
                    Text("Share", modifier = Modifier.padding(start = 4.dp), color = Color(0xFF0D72FF))
                }

                TextButton(onClick = { showRenameDialog = true }) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = Color(0xFFBAC7D5))
                    Text("Rename", modifier = Modifier.padding(start = 4.dp), color = Color(0xFFBAC7D5))
                }

                TextButton(onClick = { showDeleteDialog = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "Delete",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // ── Rename dialog ────────────────────────────────────────────────
    if (showRenameDialog) {
        var newName by remember {
            mutableStateOf(item.displayName.removeSuffix(".mp4"))
        }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Recording", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        onRename(newName.trim())
                        Toast.makeText(context, "Recording renamed", Toast.LENGTH_SHORT).show()
                    }
                    showRenameDialog = false
                }) { Text("Save", fontWeight = FontWeight.Bold, color = Color(0xFF0D72FF)) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            },
        )
    }

    // ── Delete confirmation dialog ───────────────────────────────────
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Recording?", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to permanently delete this recording?") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteDialog = false
                    Toast.makeText(context, "Recording deleted", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }
}
