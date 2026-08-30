package com.zoomrecord.app.meeting.ui

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.zoomrecord.app.backend.ServerRecorderClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LiveBotScreen(
    onNavigateBack: () -> Unit,
    onNavigateToRecordings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val baseUrl = remember { ServerRecorderClient.getBaseUrl(context) }

    var statusText by remember { mutableStateOf("CONNECTING") }
    var isStopping by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Poll server active status every 3 seconds
    LaunchedEffect(Unit) {
        while (true) {
            val res = ServerRecorderClient.getActiveStatus(context)
            if (res.isSuccess) {
                val st = res.getOrNull()
                if (st != null) {
                    statusText = if (st.active) st.status ?: "RECORDING" else "IDLE / FINISHED"
                }
            }
            delay(3000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Live Bot Monitor",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Status: $statusText",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = if (statusText.contains("RECORDING") || statusText.contains("CONNECTED")) Color(0xFF10B981) else Color.LightGray
                            )
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { webViewRef?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Feed")
                    }
                    IconButton(onClick = onNavigateToRecordings) {
                        Icon(Icons.Default.VideoLibrary, contentDescription = "Saved Records")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF0F172A))
        ) {
            // ── Local Host / Server Web Link & Bot Status Banner ─────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "LOCAL HOST / SERVER LINK",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF94A3B8)
                            )
                            Text(
                                text = baseUrl,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8)
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Copy Link Button
                            FilledTonalButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Server Link", baseUrl))
                                    Toast.makeText(context, "Copied link to clipboard!", Toast.LENGTH_SHORT).show()
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy", fontSize = 12.sp)
                            }

                            // Open in Browser Button
                            FilledTonalButton(
                                onClick = {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(baseUrl))
                                    context.startActivity(intent)
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Open", fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Bot Live Progress Stages
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isRecording = statusText.contains("RECORDING", ignoreCase = true) || statusText.contains("IN_MEETING", ignoreCase = true)

                        Text(
                            text = if (isRecording) "🔴 Recording Meeting with Audio & Video" else "⏳ Bot Joining Meeting...",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isRecording) Color(0xFF10B981) else Color(0xFFFBBF24)
                        )

                        Text(
                            text = "Audio: Full Sync • MP4",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            }
            // Live Stream HUD container
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                loadWithOverviewMode = true
                                useWideViewPort = true
                                cacheMode = WebSettings.LOAD_NO_CACHE
                            }
                            webViewClient = WebViewClient()
                            webChromeClient = WebChromeClient()
                            loadUrl(baseUrl)
                            webViewRef = this
                        }
                    }
                )

                if (isDownloading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.75f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                progress = { downloadProgress },
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Saving recorded video to phone...",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Bottom Action Controls
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Meeting is recorded directly on server with audio & video",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Stop and Save Button
                        Button(
                            onClick = {
                                scope.launch {
                                    isStopping = true
                                    Toast.makeText(context, "Stopping recording on server...", Toast.LENGTH_SHORT).show()
                                    val stopRes = ServerRecorderClient.stopRecording(context)
                                    if (stopRes.isSuccess) {
                                        Toast.makeText(context, "Recording stopped! Fetching MP4 file...", Toast.LENGTH_SHORT).show()
                                        isDownloading = true

                                        // Fetch latest recording and save to local disk
                                        delay(1500)
                                        val listRes = ServerRecorderClient.fetchRecordings(context)
                                        val latest = listRes.getOrNull()?.firstOrNull()
                                        if (latest != null) {
                                            val dlRes = ServerRecorderClient.downloadRecording(
                                                context = context,
                                                recordingId = latest.id,
                                                fileName = latest.fileName,
                                                onProgress = { p -> downloadProgress = p }
                                            )
                                            if (dlRes.isSuccess) {
                                                Toast.makeText(context, "✅ Recorded video with audio saved to phone storage!", Toast.LENGTH_LONG).show()
                                                onNavigateToRecordings()
                                            } else {
                                                Toast.makeText(context, "Saved on server, but download failed: ${dlRes.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                            }
                                        } else {
                                            Toast.makeText(context, "Recorded MP4 stored on server!", Toast.LENGTH_LONG).show()
                                            onNavigateToRecordings()
                                        }
                                        isDownloading = false
                                    } else {
                                        Toast.makeText(context, "Stop failed: ${stopRes.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                    }
                                    isStopping = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            enabled = !isStopping && !isDownloading
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isStopping) "Stopping..." else "Stop & Save to Device")
                        }
                    }
                }
            }
        }
    }
}
