package com.zoomrecord.app.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel managing authentication state across the app.
 *
 * States:
 * - [AuthState.Checking] — initial state, checking if already signed in
 * - [AuthState.SignedOut] — no active session, show login screen
 * - [AuthState.SigningIn] — sign-in in progress (show loading)
 * - [AuthState.SignedIn] — authenticated with Google/Firebase
 * - [AuthState.Guest] — using app directly as guest bot (no login required)
 * - [AuthState.Error] — sign-in failed, show error with retry
 */
class AuthViewModel(private val authManager: GoogleAuthManager) : ViewModel() {

    private val _state = MutableStateFlow<AuthState>(AuthState.Checking)
    val state: StateFlow<AuthState> = _state

    init {
        checkCurrentUser()
    }

    private fun checkCurrentUser() {
        val user = authManager.currentUser
        _state.value = if (user != null) {
            AuthState.SignedIn(user)
        } else {
            AuthState.SignedOut
        }
    }

    /**
     * Initiates Google One-Tap sign-in.
     */
    fun signIn(activityContext: Context) {
        _state.value = AuthState.SigningIn

        viewModelScope.launch {
            try {
                val user = authManager.signIn(activityContext)
                _state.value = AuthState.SignedIn(user)
            } catch (e: Exception) {
                val msg = if (GoogleAuthManager.WEB_CLIENT_ID.startsWith("YOUR_WEB_CLIENT_ID")) {
                    "Google Sign-In needs Web Client ID. You can tap 'Continue as Guest Bot' below to use the app immediately!"
                } else {
                    e.localizedMessage ?: "Sign-in failed. Please try again."
                }
                _state.value = AuthState.Error(msg)
            }
        }
    }

    /**
     * Enters guest mode so the user can immediately use the bot to join & record.
     */
    fun continueAsGuest() {
        _state.value = AuthState.Guest
    }

    /**
     * Signs out and returns to the login screen.
     */
    fun signOut() {
        viewModelScope.launch {
            authManager.signOut()
            _state.value = AuthState.SignedOut
        }
    }

    class Factory(private val authManager: GoogleAuthManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AuthViewModel(authManager) as T
        }
    }
}

// ── Auth State ───────────────────────────────────────────────────────

sealed interface AuthState {
    data object Checking : AuthState
    data object SignedOut : AuthState
    data object SigningIn : AuthState
    data object Guest : AuthState
    data class SignedIn(val user: FirebaseUser) : AuthState
    data class Error(val message: String) : AuthState
}
