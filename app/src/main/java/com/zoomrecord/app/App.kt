package com.zoomrecord.app

import android.app.Application
import android.util.Log
import com.zoomrecord.app.auth.GoogleAuthManager
import com.zoomrecord.app.library.RecordingsRepository

/**
 * Application class — provides singleton instances and app initialization.
 */
class App : Application() {

    lateinit var authManager: GoogleAuthManager
        private set

    override fun onCreate() {
        super.onCreate()

        // Initialize auth manager
        authManager = GoogleAuthManager(this)

        // Clean up orphaned pending recordings from a previous crash
        RecordingsRepository.recoverOrphanedPendingRecordings(this)

        Log.i("App", "Application initialized")
    }
}
