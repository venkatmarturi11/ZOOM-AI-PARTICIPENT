package com.zoomrecord.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.zoomrecord.app.auth.AuthViewModel
import com.zoomrecord.app.ui.navigation.AppNavigation
import com.zoomrecord.app.ui.theme.ZoomRecordTheme

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme

/**
 * Main entry point for the app.
 * Configures edge-to-edge with safeDrawingPadding so all UI content renders
 * safely below the notification bar / status bar in vertical and horizontal orientations.
 */
class MainActivity : ComponentActivity() {

    private val navigationTarget = androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sharedText = if (intent?.action == android.content.Intent.ACTION_SEND) {
            intent?.getStringExtra(android.content.Intent.EXTRA_TEXT)
        } else null
        val deepLinkUri = sharedText ?: intent?.data?.toString()
        val target = intent?.getStringExtra("navigate_to")
        if (!target.isNullOrBlank()) {
            navigationTarget.value = target
        }

        setContent {
            ZoomRecordTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val app = application as App
                    val navController = rememberNavController()

                    val authViewModel: AuthViewModel = viewModel(
                        factory = AuthViewModel.Factory(app.authManager)
                    )

                    AppNavigation(
                        navController = navController,
                        authViewModel = authViewModel,
                        initialDeepLink = deepLinkUri,
                        navigationTarget = navigationTarget.value,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val target = intent.getStringExtra("navigate_to")
        if (!target.isNullOrBlank()) {
            navigationTarget.value = target
        }
    }
}
