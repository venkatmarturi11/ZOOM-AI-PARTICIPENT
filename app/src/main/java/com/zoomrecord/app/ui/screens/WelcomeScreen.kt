package com.zoomrecord.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

/**
 * Screen 1: MeetPro Welcome Splash Screen.
 * Features the radiant logo, "MeetPro" branding, and quick access buttons.
 */
@Composable
fun WelcomeScreen(
    onJoinMeeting: () -> Unit,
    onSignIn: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0F141C), // Deep premium dark background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // ── Logo & Title ─────────────────────────────────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Radiant glow ring around logo
                Box(
                    modifier = Modifier
                        .size(150.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF1F4C9E).copy(alpha = 0.6f),
                                    Color(0xFF0F2B66).copy(alpha = 0.2f),
                                    Color.Transparent,
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(90.dp)
                            .shadow(20.dp, shape = RoundedCornerShape(26.dp), spotColor = Color(0xFF0D72FF))
                            .clip(RoundedCornerShape(26.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF2485FF), Color(0xFF0A58EB))
                                )
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = "MeetPro Logo",
                            tint = Color.White,
                            modifier = Modifier.size(52.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "MeetPro",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 34.sp,
                        letterSpacing = (-0.5).sp,
                    ),
                    color = Color.White,
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Connect • Meet • Record",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.5.sp,
                    ),
                    color = Color(0xFF8E9BAE),
                )
            }

            // ── Action Buttons ───────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Primary: Join a Meeting (direct bot link or meeting ID)
                Button(
                    onClick = onJoinMeeting,
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
                            text = "Join a Meeting",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = Color.White,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Secondary: Sign In
                Button(
                    onClick = onSignIn,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF18202F),
                    ),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        text = "Sign In",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = Color.White,
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "New here? ",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8E9BAE),
                    )
                    Text(
                        text = "Create an account",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF0D72FF),
                        ),
                        modifier = Modifier.clickable { onSignIn() },
                    )
                }
            }
        }
    }
}
