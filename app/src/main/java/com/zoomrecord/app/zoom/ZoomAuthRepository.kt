package com.zoomrecord.app.zoom

import android.util.Log
import com.zoomrecord.app.auth.GoogleAuthManager
import com.zoomrecord.app.backend.ApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fetches Zoom SDK JWT tokens from our backend.
 * The backend signs these using ZOOM_SDK_SECRET (which never leaves the server).
 * All requests include the user's Firebase ID Token for authentication.
 */
class ZoomAuthRepository(
    private val apiService: ApiService,
    private val authManager: GoogleAuthManager,
) {
    companion object {
        private const val TAG = "ZoomAuthRepository"
    }

    /**
     * Fetches a Zoom SDK JWT from the backend.
     * The callback receives the JWT string, or null on failure.
     */
    fun fetchSdkJwt(callback: (String?) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val firebaseToken = authManager.getFirebaseIdToken()
                if (firebaseToken == null) {
                    Log.e(TAG, "No Firebase ID token available")
                    callback(null)
                    return@launch
                }

                val response = apiService.getSdkJwt("Bearer $firebaseToken")
                if (response.isSuccessful) {
                    val jwt = response.body()?.sdkJwt
                    Log.d(TAG, "Got SDK JWT (expires: ${response.body()?.expiresAt})")
                    callback(jwt)
                } else {
                    Log.e(TAG, "Failed to fetch SDK JWT: ${response.code()} ${response.message()}")
                    callback(null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "SDK JWT fetch error", e)
                callback(null)
            }
        }
    }
}
