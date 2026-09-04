package com.zoomrecord.app.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.zoomrecord.app.App
import com.zoomrecord.app.auth.AuthState
import com.zoomrecord.app.auth.AuthViewModel
import com.zoomrecord.app.library.RecordingItem
import com.zoomrecord.app.library.RecordingsListViewModel
import com.zoomrecord.app.library.RecordingsRepository
import com.zoomrecord.app.library.ui.RecordingPlaybackScreen
import com.zoomrecord.app.library.ui.RecordingsListScreen
import com.zoomrecord.app.meeting.MeetingViewModel
import com.zoomrecord.app.meeting.ui.JoinMeetingScreen
import com.zoomrecord.app.ui.screens.HomeScreen
import com.zoomrecord.app.ui.screens.SignInScreen
import com.zoomrecord.app.ui.screens.WelcomeScreen

/**
 * MeetPro Navigation routes.
 */
object Routes {
    const val WELCOME = "welcome"
    const val SIGN_IN = "signin"
    const val HOME = "home"
    const val JOIN = "join"
    const val RECORDINGS = "recordings"
    const val PLAYBACK = "playback/{uri}"

    fun playback(uri: Uri): String = "playback/${Uri.encode(uri.toString())}"
}

/**
 * Main application navigation graph matching the MeetPro design flow.
 */
@Composable
fun AppNavigation(
    navController: NavHostController,
    authViewModel: AuthViewModel,
    initialDeepLink: String? = null,
    navigationTarget: String? = null,
) {
    val context = LocalContext.current
    val app = context.applicationContext as App

    val meetingViewModel: MeetingViewModel = viewModel(
        factory = MeetingViewModel.Factory(context)
    )

    androidx.compose.runtime.LaunchedEffect(navigationTarget) {
        if (navigationTarget == "recordings") {
            navController.navigate(Routes.RECORDINGS) {
                popUpTo(Routes.HOME) { inclusive = false }
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(initialDeepLink) {
        if (!initialDeepLink.isNullOrBlank()) {
            try {
                val uri = android.net.Uri.parse(initialDeepLink)
                val targetMeeting = uri.getQueryParameter("url")
                    ?: uri.getQueryParameter("meeting")
                    ?: uri.getQueryParameter("id")
                    ?: uri.lastPathSegment
                if (!targetMeeting.isNullOrBlank() && targetMeeting != "join" && targetMeeting != "live") {
                    meetingViewModel.updateMeetingInput(targetMeeting)
                    navController.navigate(Routes.JOIN)
                }
            } catch (_: Exception) {}
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
    ) {
        // ── 1. Welcome Screen (MeetPro Splash) ───────────────────────
        composable(Routes.WELCOME) {
            WelcomeScreen(
                onJoinMeeting = {
                    navController.navigate(Routes.JOIN)
                },
                onSignIn = {
                    navController.navigate(Routes.SIGN_IN)
                },
            )
        }

        // ── 2. Sign In Screen ────────────────────────────────────────
        composable(Routes.SIGN_IN) {
            SignInScreen(
                authViewModel = authViewModel,
                onBack = { navController.popBackStack() },
                onSignedIn = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                },
            )
        }

        // ── 3. Home Screen ───────────────────────────────────────────
        composable(Routes.HOME) {
            val authState = authViewModel.state
            val profileStore = androidx.compose.runtime.remember { com.zoomrecord.app.auth.UserProfileStore(context) }
            val savedProfile = profileStore.getProfile()
            val userName = if (savedProfile.isConfigured) savedProfile.fullName
                           else (authState.value as? AuthState.SignedIn)?.user?.displayName ?: "User"

            HomeScreen(
                userName = userName,
                onJoinMeetingClick = {
                    navController.navigate(Routes.JOIN)
                },
                onRecordingsClick = {
                    navController.navigate(Routes.RECORDINGS)
                },
                onNewMeetingClick = {
                    navController.navigate(Routes.JOIN)
                },
                onProfileClick = {
                    navController.navigate(Routes.SIGN_IN)
                },
                onQuickJoin = { recent ->
                    if (recent.link.isNotBlank()) {
                        meetingViewModel.updateMeetingInput(recent.link)
                    } else {
                        meetingViewModel.updateMeetingNumber(recent.meetingId)
                        if (recent.password.isNotBlank()) {
                            meetingViewModel.updatePassword(recent.password)
                        }
                    }
                    navController.navigate(Routes.JOIN)
                },
            )
        }

        // ── 4. Join Meeting Screen (Link / ID) ────────────────────────
        composable(Routes.JOIN) {
            val authState = authViewModel.state
            val userName = (authState.value as? AuthState.SignedIn)?.user?.displayName

            JoinMeetingScreen(
                viewModel = meetingViewModel,
                userName = userName,
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Routes.HOME)
                    }
                },
                onNavigateToRecordings = {
                    navController.navigate(Routes.RECORDINGS)
                },
            )
        }

        // ── 5. Recordings Screen ─────────────────────────────────────
        composable(Routes.RECORDINGS) {
            val repo = RecordingsRepository(context)
            val recordingsViewModel: RecordingsListViewModel = viewModel(
                factory = RecordingsListViewModel.Factory(repo)
            )

            RecordingsListScreen(
                viewModel = recordingsViewModel,
                onOpen = { item ->
                    navController.navigate(Routes.playback(item.uri))
                },
                onHomeClick = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
                onJoinMeetingClick = {
                    navController.navigate(Routes.JOIN)
                },
            )
        }

        // ── 7. Playback Screen ───────────────────────────────────────
        composable(
            route = Routes.PLAYBACK,
            arguments = listOf(navArgument("uri") { type = NavType.StringType }),
        ) { backStackEntry ->
            val encodedUri = backStackEntry.arguments?.getString("uri") ?: return@composable
            val uri = Uri.parse(Uri.decode(encodedUri))
            val repo = RecordingsRepository(context)

            val recordings = repo.queryRecordings()
            val item = recordings.find { it.uri == uri } ?: RecordingItem(
                uri = uri,
                displayName = "Recording",
                durationMs = 0,
                sizeBytes = 0,
                width = 0,
                height = 0,
                dateAddedEpochSec = 0,
            )

            RecordingPlaybackScreen(
                item = item,
                onBack = { navController.popBackStack() },
                onDelete = {
                    repo.delete(item.uri)
                    navController.popBackStack()
                },
                onRename = { newName ->
                    repo.rename(item.uri, newName)
                },
            )
        }
    }
}
