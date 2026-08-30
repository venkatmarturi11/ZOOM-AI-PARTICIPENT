package com.zoomrecord.app.auth

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

/**
 * Manages Google Sign-In via Android Credential Manager + Firebase Auth.
 *
 * Flow:
 * 1. User taps "Sign in with Google"
 * 2. Credential Manager shows the Google One-Tap UI (no reCAPTCHA, no registration)
 * 3. User picks their Google account → we get a Google ID Token
 * 4. Exchange Google ID Token for a Firebase credential
 * 5. Sign into Firebase Auth → get a Firebase ID Token
 * 6. Firebase ID Token is sent to our backend on every API call
 *
 * The web client ID comes from Firebase Console → Authentication → Sign-in method → Google.
 * It's the "Web client ID" (not the Android client ID).
 */
class GoogleAuthManager(private val context: Context) {

    companion object {
        private const val TAG = "GoogleAuthManager"

        // TODO: Replace with your actual Web Client ID from Firebase Console
        // Firebase Console → Authentication → Sign-in method → Google → Web client ID
        const val WEB_CLIENT_ID = "YOUR_WEB_CLIENT_ID.apps.googleusercontent.com"
    }

    private val credentialManager = CredentialManager.create(context)
    private val firebaseAuth = FirebaseAuth.getInstance()

    /**
     * Returns the currently signed-in Firebase user, or null if not signed in.
     */
    val currentUser: FirebaseUser?
        get() = firebaseAuth.currentUser

    /**
     * Whether there's a currently signed-in user.
     */
    val isSignedIn: Boolean
        get() = firebaseAuth.currentUser != null

    /**
     * Initiates Google One-Tap sign-in and exchanges the result for a Firebase credential.
     *
     * @param activityContext Must be an Activity context for the Credential Manager UI.
     * @return The signed-in Firebase user.
     * @throws Exception if sign-in fails at any step.
     */
    suspend fun signIn(activityContext: Context): FirebaseUser {
        // Build the Google ID sign-in request
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false) // Show all accounts, not just previously used
            .setServerClientId(WEB_CLIENT_ID)
            .setAutoSelectEnabled(true) // Auto-select if only one account
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        // Launch the One-Tap UI
        val result: GetCredentialResponse = credentialManager.getCredential(
            context = activityContext,
            request = request,
        )

        // Extract the Google ID Token from the credential
        val credential = result.credential
        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
        val googleIdToken = googleIdTokenCredential.idToken

        Log.d(TAG, "Got Google ID Token, exchanging for Firebase credential…")

        // Exchange for Firebase credential and sign in
        val firebaseCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
        val authResult = firebaseAuth.signInWithCredential(firebaseCredential).await()

        val user = authResult.user
            ?: throw IllegalStateException("Firebase sign-in succeeded but user is null")

        Log.i(TAG, "Signed in as: ${user.displayName} (${user.email})")
        return user
    }

    /**
     * Gets the current Firebase ID Token for sending to the backend.
     * Returns null if not signed in or token refresh fails.
     *
     * @param forceRefresh If true, forces a token refresh from Firebase servers.
     */
    suspend fun getFirebaseIdToken(forceRefresh: Boolean = false): String? {
        val user = firebaseAuth.currentUser ?: return null
        return try {
            user.getIdToken(forceRefresh).await().token
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get Firebase ID token", e)
            null
        }
    }

    /**
     * Signs out from both Firebase and Credential Manager.
     * After this, the next sign-in will show the account picker again.
     */
    suspend fun signOut() {
        firebaseAuth.signOut()
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear credential state", e)
        }
        Log.i(TAG, "Signed out")
    }
}
