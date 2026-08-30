package com.zoomrecord.app

import android.app.Application
import android.util.Log
import com.zoomrecord.app.auth.GoogleAuthManager
import com.zoomrecord.app.backend.AuthTokenStore
import com.zoomrecord.app.library.RecordingsRepository
import com.zoomrecord.app.zoom.ZoomAuthRepository
import com.zoomrecord.app.zoom.ZoomSdkManager

/**
 * Application class — serves as the manual DI container.
 *
 * Provides singleton instances of:
 * - [GoogleAuthManager] — Google Sign-In + Firebase Auth
 * - [ZoomSdkManager] — Zoom SDK lifecycle (stubbed until .aar is added)
 * - [ZoomAuthRepository] — Fetches Zoom SDK JWT from backend
 *
 * Also performs startup tasks:
 * - Cleans up orphaned pending recordings from previous crashes
 */
class App : Application() {

    lateinit var authManager: GoogleAuthManager
        private set

    lateinit var zoomSdkManager: ZoomSdkManager
        private set

    lateinit var zoomAuthRepository: ZoomAuthRepository
        private set

    override fun onCreate() {
        super.onCreate()

        // Initialize auth manager
        authManager = GoogleAuthManager(this)

        // Initialize Zoom SDK components
        zoomAuthRepository = ZoomAuthRepository(AuthTokenStore.apiService, authManager)
        zoomSdkManager = ZoomSdkManager(this, zoomAuthRepository)

        // Initialize Zoom SDK (lazy — only does real work when .aar is present)
        zoomSdkManager.initialize { ok, detail ->
            Log.d("App", "Zoom SDK init: ok=$ok detail=$detail")
        }

        // Clean up orphaned pending recordings from a previous crash
        RecordingsRepository.recoverOrphanedPendingRecordings(this)

        Log.i("App", "Application initialized")
    }
}
