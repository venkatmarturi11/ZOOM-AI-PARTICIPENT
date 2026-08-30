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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.zoomrecord.app.auth.AuthState
import com.zoomrecord.app.auth.AuthViewModel
import com.zoomrecord.app.auth.UserProfileStore

/**
 * Screen 2: Zoom Account Credentials & Profile Setup.
 * Collects and stores real Zoom user identity (First Name, Last Name, Gmail, Phone)
 * to be used automatically when joining Zoom meetings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInScreen(
    authViewModel: AuthViewModel,
    onBack: () -> Unit,
    onSignedIn: () -> Unit,
) {
    val context = LocalContext.current
    val profileStore = remember { UserProfileStore(context) }
    val savedProfile = remember { profileStore.getProfile() }

    var firstName by remember { mutableStateOf(savedProfile.firstName) }
    var lastName by remember { mutableStateOf(savedProfile.lastName) }
    var email by remember { mutableStateOf(savedProfile.email) }
    var phone by remember { mutableStateOf(savedProfile.phone) }
    var zoomPassword by remember { mutableStateOf(savedProfile.zoomPassword) }
    var passwordVisible by remember { mutableStateOf(false) }
    var autoLoginZoomFirst by remember { mutableStateOf(savedProfile.autoLoginZoomFirst) }

    val authState by authViewModel.state.collectAsState()

    // When Google sign-in completes, auto-fill credentials
    LaunchedEffect(authState) {
        if (authState is AuthState.SignedIn) {
            val user = (authState as AuthState.SignedIn).user
            val nameParts = user.displayName?.split(" ") ?: listOf()
            if (firstName.isBlank() && nameParts.isNotEmpty()) {
                firstName = nameParts.first()
            }
            if (lastName.isBlank() && nameParts.size > 1) {
                lastName = nameParts.subList(1, nameParts.size).joinToString(" ")
            }
            if (email.isBlank() && user.email != null) {
                email = user.email!!
            }
        }
    }

    val isValid = firstName.isNotBlank() && email.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color(0xFF1E293B),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Zoom Credentials",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    color = Color(0xFF0F172A),
                ),
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Configure your Zoom login identity so the bot auto-authenticates to bypass reCAPTCHA and join meetings seamlessly.",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Color(0xFF64748B),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                ),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── Name Row (First Name & Last Name) ────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = firstName,
                    onValueChange = { firstName = it },
                    label = { Text("First Name *") },
                    placeholder = { Text("e.g. Venkateswarlu") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = Color(0xFF0D72FF),
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                )

                OutlinedTextField(
                    value = lastName,
                    onValueChange = { lastName = it },
                    label = { Text("Last Name") },
                    placeholder = { Text("e.g. Marturi") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ── Gmail / Account Email Input ──────────────────────────
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Zoom / Account Email *") },
                placeholder = { Text("your.zoom.account@gmail.com") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Email,
                        contentDescription = null,
                        tint = Color(0xFF0D72FF),
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(14.dp))

            // ── Zoom Account Password (for Auto-Login & Captcha Bypass) ─
            OutlinedTextField(
                value = zoomPassword,
                onValueChange = { zoomPassword = it },
                label = { Text("Zoom Account Password") },
                placeholder = { Text("Enter your Zoom password") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = if (zoomPassword.isNotBlank()) Color(0xFF22C55E) else Color(0xFF0D72FF),
                    )
                },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Text(
                            text = if (passwordVisible) "HIDE" else "SHOW",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0D72FF),
                        )
                    }
                },
                visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(10.dp))

            // ── Info Card: ReCAPTCHA Bypass Notice ───────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFF0FDF4))
                    .border(1.dp, Color(0xFFBBF7D0), RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF16A34A),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Auto-Login Feature: Bot logs into Zoom first so Zoom disables reCAPTCHA challenges completely.",
                        fontSize = 11.sp,
                        color = Color(0xFF166534),
                        lineHeight = 15.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ── Phone Number (Optional) ──────────────────────────────
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Phone Number (Optional)") },
                placeholder = { Text("+91 98765 43210") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = null,
                        tint = Color(0xFF94A3B8),
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Save & Continue Button ───────────────────────────────
            Button(
                onClick = {
                    profileStore.saveProfile(
                        firstName = firstName,
                        lastName = lastName,
                        email = email,
                        phone = phone,
                        zoomPassword = zoomPassword,
                        autoLoginZoomFirst = autoLoginZoomFirst,
                    )
                    authViewModel.continueAsGuest()
                    onSignedIn()
                },
                enabled = isValid,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .shadow(10.dp, shape = RoundedCornerShape(16.dp), spotColor = Color(0xFF0D72FF)),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            if (isValid) {
                                Brush.horizontalGradient(listOf(Color(0xFF0E72FF), Color(0xFF0058EB)))
                            } else {
                                Brush.horizontalGradient(listOf(Color(0xFFCBD5E1), Color(0xFF94A3B8)))
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Save Credentials & Continue",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Google One-Tap Quick Import ──────────────────────────
            Text(
                text = "or auto-fill with Google",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = Color(0xFF94A3B8),
                ),
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { authViewModel.signIn(context) },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1F5F9)),
                    modifier = Modifier.height(44.dp),
                ) {
                    Text(
                        text = "G  Import from Google Account",
                        color = Color(0xFF1E293B),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

@Composable
private fun SocialLoginCircle(
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .border(1.dp, Color(0xFFE2E8F0), CircleShape)
            .background(Color(0xFFF8FAFC))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
    }
}
