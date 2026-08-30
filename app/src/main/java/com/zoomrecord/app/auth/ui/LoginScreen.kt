package com.zoomrecord.app.auth.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zoomrecord.app.auth.AuthState
import com.zoomrecord.app.auth.AuthViewModel

/**
 * Login screen with Google Sign-In and Direct Bot Mode access.
 */
@Composable
fun LoginScreen(
    viewModel: AuthViewModel,
    onSignedIn: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // Navigate when signed in or continuing as guest
    if (state is AuthState.SignedIn || state is AuthState.Guest) {
        onSignedIn()
        return
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // ── App branding ─────────────────────────────────────────
            Icon(
                imageVector = Icons.Default.Videocam,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "ZoomRecord",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                ),
                color = MaterialTheme.colorScheme.onBackground,
            )

            Text(
                text = "Silent Bot Meeting Recorder (1080p)",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )

            Spacer(modifier = Modifier.height(40.dp))

            // ── Direct Bot Mode Access (No Login Required) ───────────
            Button(
                onClick = { viewModel.continueAsGuest() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = "Direct Bot Access (No Login Needed)",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Sign in with Google button ───────────────────────────
            AnimatedVisibility(
                visible = state !is AuthState.SigningIn,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                OutlinedButton(
                    onClick = { viewModel.signIn(context) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        text = "Sign in with Google",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            // ── Loading state ────────────────────────────────────────
            AnimatedVisibility(
                visible = state is AuthState.SigningIn,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp,
                    )
                    Text(
                        text = "Signing in…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }

            // ── Error state ──────────────────────────────────────────
            AnimatedVisibility(
                visible = state is AuthState.Error,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                val errorState = state as? AuthState.Error
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Text(
                        text = errorState?.message ?: "Something went wrong",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // ── Footer ───────────────────────────────────────────────
            Text(
                text = "Join meetings automatically & record silently.\nNo reCAPTCHA or registration required.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
        }
    }
}
