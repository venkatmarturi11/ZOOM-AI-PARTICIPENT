package com.zoomrecord.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import android.widget.Toast
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import com.zoomrecord.app.meeting.RecentMeetingItem
import com.zoomrecord.app.meeting.RecentMeetingsStore

/**
 * Screen 3: Home Dashboard matching Screen 3 in the MeetPro design mockup.
 * Displays Quick Actions, Recent Meetings with Title and Link, and Bottom Navigation.
 */
@Composable
fun HomeScreen(
    userName: String?,
    onJoinMeetingClick: () -> Unit,
    onRecordingsClick: () -> Unit,
    onNewMeetingClick: () -> Unit,
    onProfileClick: () -> Unit = {},
    onQuickJoin: (RecentMeetingItem) -> Unit = { onJoinMeetingClick() },
) {
    val context = LocalContext.current
    val recentStore = androidx.compose.runtime.remember { RecentMeetingsStore(context) }
    val recentMeetings = androidx.compose.runtime.remember { recentStore.getRecentMeetings() }

    Scaffold(
        bottomBar = {
            MeetProBottomBar(
                selectedTab = "home",
                onTabSelected = { tab ->
                    if (tab == "recordings") onRecordingsClick()
                    if (tab == "meetings") onJoinMeetingClick()
                    if (tab == "settings") onProfileClick()
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))

                // ── Header: "Home", Notifications, Avatar ────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Home",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp,
                        ),
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFF0F4FA)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Notifications,
                                contentDescription = "Notifications",
                                tint = Color(0xFF334155),
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Profile Avatar (Clickable to edit credentials)
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF0D72FF))
                                .clickable(onClick = onProfileClick),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = (userName?.take(1) ?: "U").uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                            )
                        }
                    }
                }
            }

            item {
                // ── 4-Grid Quick Action Hero Card ────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(16.dp, shape = RoundedCornerShape(24.dp), spotColor = Color(0xFF0D72FF).copy(alpha = 0.3f))
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF1E7BFF), Color(0xFF0757E8))
                            )
                        )
                        .padding(20.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround,
                        ) {
                            QuickActionItem(
                                icon = Icons.Default.VideoCall,
                                label = "New Meeting",
                                onClick = onNewMeetingClick,
                            )
                            QuickActionItem(
                                icon = Icons.Default.AddBox,
                                label = "Join Meeting",
                                onClick = onJoinMeetingClick,
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround,
                        ) {
                            QuickActionItem(
                                icon = Icons.Default.CalendarMonth,
                                label = "Schedule Meeting",
                                onClick = onJoinMeetingClick,
                            )
                            QuickActionItem(
                                icon = Icons.Default.SmartToy,
                                label = "Meeting Bot",
                                onClick = onJoinMeetingClick,
                            )
                        }
                    }
                }
            }

            item {
                // ── Recent Meetings Section Header ───────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Recent Meetings",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        ),
                    )
                    Text(
                        text = "View Records",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF0D72FF),
                        ),
                        modifier = Modifier.clickable { onRecordingsClick() },
                    )
                }
            }

            if (recentMeetings.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                    ) {
                        Text(
                            text = "No recent meetings yet. Join a meeting to see it listed here!",
                            color = Color(0xFF64748B),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            } else {
                items(recentMeetings.size) { index ->
                    val meeting = recentMeetings[index]
                    RecentMeetingCard(
                        item = meeting,
                        onJoin = { onQuickJoin(meeting) },
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun RecentMeetingCard(
    item: RecentMeetingItem,
    onJoin: () -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Title and Date
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color(0xFF0F172A),
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.formattedDate,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Color(0xFF64748B),
                            fontSize = 12.sp,
                        ),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                // Join Button
                Button(
                    onClick = onJoin,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D72FF)),
                    modifier = Modifier.height(38.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color.White,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Join",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Meeting ID and Link row
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFEFF4FB))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "ID: ${item.meetingId}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF1E293B),
                            fontSize = 12.sp,
                        ),
                    )

                    // Copy Link Button
                    if (item.link.isNotBlank()) {
                        Row(
                            modifier = Modifier
                                .clickable {
                                    clipboardManager.setText(AnnotatedString(item.link))
                                    Toast.makeText(context, "Meeting link copied!", Toast.LENGTH_SHORT).show()
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy Link",
                                tint = Color(0xFF0D72FF),
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Copy Link",
                                color = Color(0xFF0D72FF),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                if (item.link.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = null,
                            tint = Color(0xFF64748B),
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = item.link,
                            color = Color(0xFF64748B),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(120.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Medium,
                color = Color.White,
                fontSize = 12.sp,
            ),
        )
    }
}

@Composable
private fun UpcomingMeetingCard(
    title: String,
    time: String,
    meetingId: String,
    onStart: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFF8FAFC),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A),
                    ),
                )
                Text(
                    text = time,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Color(0xFF64748B),
                    ),
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = "Meeting ID: $meetingId",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                    ),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            Button(
                onClick = onStart,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE8F2FF)),
                modifier = Modifier.height(38.dp),
            ) {
                Text(
                    text = "Start",
                    color = Color(0xFF0D72FF),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
fun MeetProBottomBar(
    selectedTab: String,
    onTabSelected: (String) -> Unit,
) {
    NavigationBar(
        containerColor = Color.White,
        tonalElevation = 8.dp,
    ) {
        NavigationBarItem(
            selected = selectedTab == "home",
            onClick = { onTabSelected("home") },
            icon = { Icon(Icons.Outlined.Home, contentDescription = "Home") },
            label = { Text("Home", fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF0D72FF),
                selectedTextColor = Color(0xFF0D72FF),
                indicatorColor = Color(0xFFE8F2FF),
            ),
        )
        NavigationBarItem(
            selected = selectedTab == "meetings",
            onClick = { onTabSelected("meetings") },
            icon = { Icon(Icons.Outlined.Videocam, contentDescription = "Meetings") },
            label = { Text("Meetings", fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF0D72FF),
                selectedTextColor = Color(0xFF0D72FF),
                indicatorColor = Color(0xFFE8F2FF),
            ),
        )
        NavigationBarItem(
            selected = selectedTab == "recordings",
            onClick = { onTabSelected("recordings") },
            icon = { Icon(Icons.Outlined.VideoLibrary, contentDescription = "Recordings") },
            label = { Text("Recordings", fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF0D72FF),
                selectedTextColor = Color(0xFF0D72FF),
                indicatorColor = Color(0xFFE8F2FF),
            ),
        )
        NavigationBarItem(
            selected = selectedTab == "settings",
            onClick = { onTabSelected("settings") },
            icon = { Icon(Icons.Outlined.Settings, contentDescription = "Settings") },
            label = { Text("Settings", fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF0D72FF),
                selectedTextColor = Color(0xFF0D72FF),
                indicatorColor = Color(0xFFE8F2FF),
            ),
        )
    }
}
