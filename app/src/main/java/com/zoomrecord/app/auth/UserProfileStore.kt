package com.zoomrecord.app.auth

import android.content.Context
import android.content.SharedPreferences

/**
 * Data model for Zoom account user profile credentials.
 */
data class UserProfile(
    val firstName: String = "venkateswarlu",
    val lastName: String = "marturi",
    val email: String = "venkatmarturi11@gmail.com",
    val phone: String = "8074038968",
    val zoomPassword: String = "naniv401",
    val autoLoginZoomFirst: Boolean = true,
) {
    val fullName: String
        get() = "$firstName $lastName".trim().ifEmpty { firstName.ifEmpty { "venkateswarlu marturi" } }

    val isConfigured: Boolean
        get() = true

    val hasZoomCredentials: Boolean
        get() = email.isNotBlank() && zoomPassword.isNotBlank()
}

/**
 * Persistent SharedPreferences store for user credentials.
 */
class UserProfileStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "zoom_user_profile"
        private const val KEY_FIRST_NAME = "first_name"
        private const val KEY_LAST_NAME = "last_name"
        private const val KEY_EMAIL = "email"
        private const val KEY_PHONE = "phone"
        private const val KEY_ZOOM_PASSWORD = "zoom_password"
        private const val KEY_AUTO_LOGIN = "auto_login_zoom_first"

        const val FIXED_EMAIL = "venkatmarturi11@gmail.com"
        const val FIXED_PASSWORD = "naniv401"
        const val FIXED_FIRST_NAME = "venkateswarlu"
        const val FIXED_LAST_NAME = "marturi"
    }

    /**
     * Gets the currently saved user profile, defaulting to the fixed Zoom account.
     */
    fun getProfile(): UserProfile {
        val email = prefs.getString(KEY_EMAIL, FIXED_EMAIL)?.ifBlank { FIXED_EMAIL } ?: FIXED_EMAIL
        val zoomPassword = prefs.getString(KEY_ZOOM_PASSWORD, FIXED_PASSWORD)?.ifBlank { FIXED_PASSWORD } ?: FIXED_PASSWORD
        val firstName = prefs.getString(KEY_FIRST_NAME, FIXED_FIRST_NAME)?.ifBlank { FIXED_FIRST_NAME } ?: FIXED_FIRST_NAME
        val lastName = prefs.getString(KEY_LAST_NAME, FIXED_LAST_NAME)?.ifBlank { FIXED_LAST_NAME } ?: FIXED_LAST_NAME

        return UserProfile(
            firstName = firstName,
            lastName = lastName,
            email = email,
            phone = prefs.getString(KEY_PHONE, "8074038968") ?: "8074038968",
            zoomPassword = zoomPassword,
            autoLoginZoomFirst = prefs.getBoolean(KEY_AUTO_LOGIN, true),
        )
    }

    /**
     * Saves user profile credentials persistently.
     */
    fun saveProfile(
        firstName: String,
        lastName: String,
        email: String,
        phone: String = "",
        zoomPassword: String = "",
        autoLoginZoomFirst: Boolean = true,
    ) {
        prefs.edit()
            .putString(KEY_FIRST_NAME, firstName.trim())
            .putString(KEY_LAST_NAME, lastName.trim())
            .putString(KEY_EMAIL, email.trim())
            .putString(KEY_PHONE, phone.trim())
            .putString(KEY_ZOOM_PASSWORD, zoomPassword.trim())
            .putBoolean(KEY_AUTO_LOGIN, autoLoginZoomFirst)
            .apply()
    }

    /**
     * Clears saved profile credentials.
     */
    fun clear() {
        prefs.edit().clear().apply()
    }
}

