package com.zoomrecord.app.library.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zoomrecord.app.library.RecordingItem
import com.zoomrecord.app.library.RecordingsListViewModel
import com.zoomrecord.app.library.RecordingsUiState
import com.zoomrecord.app.ui.screens.MeetProBottomBar

import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import kotlinx.coroutines.delay

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import android.net.Uri
import com.zoomrecord.app.library.RecordingsRepository

/**
 * Screen 6: Recordings Screen matching Screen 6 in the MeetPro design mockup.
 * Features 1-tap play, download to custom folder, quick save to Downloads,
 * rename, and permanent delete.
 */
@Composable
fun RecordingsListScreen(
    viewModel: RecordingsListViewModel,
    onOpen: (RecordingItem) -> Unit,
    onHomeClick: () -> Unit,
    onJoinMeetingClick: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { RecordingsRepository(context) }

    val state by viewModel.state.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val isSearchExpanded = remember { mutableStateOf(false) }
    val tabs = listOf("All", "Cloud", "Local")
    val scope = rememberCoroutineScope()

    // Export custom location launcher
    var pendingExportItem by remember { mutableStateOf<RecordingItem?>(null) }
    val exportCustomLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4")
    ) { destUri: Uri? ->
        val item = pendingExportItem
        if (destUri != null && item != null) {
            val success = repo.exportToUri(item.uri, destUri)
            if (success) {
                Toast.makeText(context, "Recording saved successfully!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "Failed to save recording to selected location", Toast.LENGTH_SHORT).show()
            }
        }
        pendingExportItem = null
    }

    // Dialog states
    var itemToDelete by remember { mutableStateOf<RecordingItem?>(null) }
    var itemToRename by remember { mutableStateOf<RecordingItem?>(null) }

    LaunchedEffect(Unit) {
        viewModel.load()
        delay(600)
        viewModel.load()
    }

    Scaffold(
        bottomBar = {
            MeetProBottomBar(
                selectedTab = "recordings",
                onTabSelected = { tab ->
                    if (tab == "home") onHomeClick()
                    if (tab == "meetings") onJoinMeetingClick()
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // ── Top Bar: Title & Search Toggle ──────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Recordings",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp,
                    ),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (isSearchExpanded.value) Color(0xFFE8F2FF) else Color(0xFFF0F4FA))
                            .clickable { isSearchExpanded.value = !isSearchExpanded.value },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (isSearchExpanded.value) Icons.Default.Clear else Icons.Default.Search,
                            contentDescription = "Search",
                            tint = if (isSearchExpanded.value) Color(0xFF0D72FF) else Color(0xFF334155),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // ── Search Input Field (when expanded) ───────────────────
            if (isSearchExpanded.value) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    placeholder = { Text("Search by meeting name…", fontSize = 13.sp) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = Color(0xFF0D72FF),
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear",
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF0D72FF),
                        unfocusedBorderColor = Color(0xFFE2E8F0),
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Filter Tabs: All, Cloud, Local (Pill Shape) ──────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFF1F5F9))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                tabs.forEachIndexed { index, tabTitle ->
                    val isSelected = selectedTab == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) Color.White else Color.Transparent)
                            .clickable { viewModel.setSelectedTab(index) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = tabTitle,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color(0xFF0D72FF) else Color(0xFF64748B),
                            ),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Content ──────────────────────────────────────────────
            when (val s = state) {
                is RecordingsUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Color(0xFF0D72FF))
                    }
                }

                is RecordingsUiState.Empty -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayCircle,
                                contentDescription = null,
                                tint = Color(0xFFCBD5E1),
                                modifier = Modifier.size(64.dp),
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (searchQuery.isNotEmpty()) "No matching recordings" else "No recordings yet",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF475569),
                                ),
                            )
                            Text(
                                text = if (searchQuery.isNotEmpty())
                                    "Try a different search keyword"
                                else
                                    "Join a meeting with the bot to capture 1080p video and crystal audio",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF94A3B8),
                                modifier = Modifier.padding(top = 4.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Button(
                                onClick = onJoinMeetingClick,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D72FF)),
                                modifier = Modifier.height(44.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VideoCall,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = Color.White,
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Join & Record Meeting", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }
                        }
                    }
                }

                is RecordingsUiState.Content -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        s.sections.forEach { section ->
                            item {
                                Text(
                                    text = section.label,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF1E293B),
                                    ),
                                    modifier = Modifier.padding(vertical = 4.dp),
                                )
                            }
                            items(
                                items = section.items,
                                key = { it.uri.toString() },
                            ) { item ->
                                MeetProRecordingCard(
                                    item = item,
                                    onClick = { onOpen(item) },
                                    onQuickDownload = {
                                        val success = repo.exportToDownloads(item.uri, item.displayName)
                                        if (success) {
                                            Toast.makeText(context, "Saved to Downloads/MeetProRecordings!", Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(context, "Failed to save to Downloads", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onCustomDownload = {
                                        pendingExportItem = item
                                        val defaultFileName = "${item.displayName.removeSuffix(".mp4")}.mp4"
                                        exportCustomLauncher.launch(defaultFileName)
                                    },
                                    onShare = {
                                        context.startActivity(
                                            android.content.Intent.createChooser(
                                                repo.shareIntent(item.uri), null
                                            )
                                        )
                                    },
                                    onShareAudio = {
                                        item.audioUri?.let { aUri ->
                                            context.startActivity(
                                                android.content.Intent.createChooser(
                                                    repo.shareAudioIntent(aUri), "Share MP3 Audio"
                                                )
                                            )
                                        }
                                    },
                                    onRename = { itemToRename = item },
                                    onDelete = { itemToDelete = item },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Delete Confirmation Dialog ──────────────────────────────────
    itemToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text("Delete Recording?", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to permanently delete '${item.displayName.removeSuffix(".mp4")}'?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(item)
                        itemToDelete = null
                        Toast.makeText(context, "Recording deleted", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    // ── Rename Dialog ────────────────────────────────────────────────
    itemToRename?.let { item ->
        var renameText by remember { mutableStateOf(item.displayName.removeSuffix(".mp4")) }
        AlertDialog(
            onDismissRequest = { itemToRename = null },
            title = { Text("Rename Recording", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Recording Title") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameText.isNotBlank()) {
                            viewModel.rename(item, renameText.trim())
                            Toast.makeText(context, "Recording renamed", Toast.LENGTH_SHORT).show()
                        }
                        itemToRename = null
                    }
                ) {
                    Text("Save", fontWeight = FontWeight.Bold, color = Color(0xFF0D72FF))
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToRename = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun MeetProRecordingCard(
    item: RecordingItem,
    onClick: () -> Unit,
    onQuickDownload: () -> Unit,
    onCustomDownload: () -> Unit,
    onShare: () -> Unit,
    onShareAudio: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Video Thumbnail
            Box(
                modifier = Modifier
                    .size(width = 90.dp, height = 62.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF1E293B), Color(0xFF0F172A))
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(28.dp),
                )
                // Duration tag
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = formatDuration(item.durationMs),
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Metadata
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName.removeSuffix(".mp4"),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A),
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 1080p pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFE2E8F0))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = "1080p HD",
                            color = Color(0xFF334155),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (item.audioUri != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFFF3E8FF))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = "🎵 MP3",
                                color = Color(0xFF7E22CE),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = formatSize(item.sizeBytes),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                        ),
                    )
                }
            }

            // Quick Download Icon
            IconButton(
                onClick = onQuickDownload,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Save to Downloads",
                    tint = Color(0xFF0D72FF),
                    modifier = Modifier.size(20.dp),
                )
            }

            // 3-dots dropdown menu button
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(20.dp),
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Quick Save to Downloads") },
                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null, tint = Color(0xFF0D72FF)) },
                        onClick = {
                            menuExpanded = false
                            onQuickDownload()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Download to Custom Folder") },
                        leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onCustomDownload()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Share Video (MP4)") },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onShare()
                        }
                    )
                    if (item.audioUri != null) {
                        DropdownMenuItem(
                            text = { Text("Share Audio (MP3)") },
                            leadingIcon = { Icon(Icons.Default.Audiotrack, contentDescription = null, tint = Color(0xFF7E22CE)) },
                            onClick = {
                                menuExpanded = false
                                onShareAudio()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete Recording", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

// ── Formatting helpers ───────────────────────────────────────────────

fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
    else -> "%.0f KB".format(bytes / 1_000.0)
}

