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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val deepLinkUri = intent?.data?.toString()

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
                    )
                }
            }
        }
    }
}
