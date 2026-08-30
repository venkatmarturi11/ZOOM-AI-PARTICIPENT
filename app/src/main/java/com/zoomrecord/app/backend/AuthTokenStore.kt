package com.zoomrecord.app.backend

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Provides a configured Retrofit [ApiService] instance.
 *
 * The base URL should point to your backend server.
 * In development, this is typically your local machine's IP (not localhost,
 * since the app runs on a phone/emulator).
 */
object AuthTokenStore {

    // TODO: Replace with your actual backend URL
    // For emulator: "http://10.0.2.2:3000/" (maps to host machine's localhost)
    // For physical device: "http://YOUR_LOCAL_IP:3000/"
    // For production: "https://your-backend.example.com/"
    private const val BASE_URL = "http://10.0.2.2:3000/"

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                }
            )
            .build()
    }

    val apiService: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
