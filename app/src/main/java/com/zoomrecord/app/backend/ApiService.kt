package com.zoomrecord.app.backend

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Body

/**
 * Retrofit interface for communicating with our backend server.
 * All endpoints require a Firebase ID Token in the Authorization header.
 */
interface ApiService {

    /**
     * Fetches a Zoom SDK JWT for authenticating the Meeting SDK.
     * The backend generates this using ZOOM_SDK_KEY/SECRET (server-side only).
     */
    @GET("zoom/sdk-jwt")
    suspend fun getSdkJwt(
        @Header("Authorization") authToken: String,
    ): Response<SdkJwtResponse>

    /**
     * Creates a new Zoom meeting via the backend (which proxies to Zoom REST API).
     */
    @POST("zoom/meetings")
    suspend fun createMeeting(
        @Header("Authorization") authToken: String,
        @Body request: CreateMeetingRequest,
    ): Response<CreateMeetingResponse>
}

// ── Response/Request models ──────────────────────────────────────────

data class SdkJwtResponse(
    val sdkJwt: String,
    val expiresAt: Long,
)

data class CreateMeetingRequest(
    val topic: String,
    val duration: Int = 60, // minutes
)

data class CreateMeetingResponse(
    val meetingId: String,
    val passcode: String,
    val joinUrl: String,
)
