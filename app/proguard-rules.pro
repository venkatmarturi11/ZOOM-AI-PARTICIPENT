# ProGuard/R8 rules for ZoomRecord

# ── Zoom SDK ──────────────────────────────────────────────────────────
# Uncomment when Zoom SDK .aar is added
# -keep class us.zoom.** { *; }
# -dontwarn us.zoom.**

# ── Retrofit ──────────────────────────────────────────────────────────
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.zoomrecord.app.backend.** { *; }
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

# ── Firebase ──────────────────────────────────────────────────────────
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# ── Moshi (JSON serialization for Retrofit) ───────────────────────────
-keep class com.squareup.moshi.** { *; }
-keepclassmembers class * {
    @com.squareup.moshi.* <methods>;
    @com.squareup.moshi.* <fields>;
}
