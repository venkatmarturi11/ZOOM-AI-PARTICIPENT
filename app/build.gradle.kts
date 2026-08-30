plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.zoomrecord.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zoomrecord.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"

        ndk {
            // Zoom SDK native libs — match what ships in the .aar
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true // Required by some Zoom SDK versions
        }
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
            )
        }
    }
}

dependencies {
    // ── AndroidX Core ──────────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // ── Lifecycle ──────────────────────────────────────────────────
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // ── Compose ────────────────────────────────────────────────────
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // ── Navigation ─────────────────────────────────────────────────
    implementation(libs.androidx.navigation.compose)

    // ── Media3 / ExoPlayer (for recording playback) ────────────────
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // ── Networking ─────────────────────────────────────────────────
    implementation(libs.squareup.retrofit)
    implementation(libs.squareup.retrofit.moshi)
    implementation(libs.squareup.okhttp.logging)

    // ── Firebase Auth ──────────────────────────────────────────────
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)

    // ── Google Sign-In (Credential Manager) ────────────────────────
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.google.id)

    // ── Zoom SDK transitive deps (required when .aar is added) ─────
    implementation(libs.google.material)
    implementation(libs.androidx.constraintlayout)

    // ── Zoom SDK .aar — uncomment after placing files in app/libs/ ─
    // implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
}
