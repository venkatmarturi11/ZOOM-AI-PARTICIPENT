# ProGuard/R8 rules for ZoomRecord

# ── Zoom SDK ──────────────────────────────────────────────────────────
# Uncomment when Zoom SDK .aar is added
# -keep class us.zoom.** { *; }
# -dontwarn us.zoom.**

# ── Retrofit / Network ────────────────────────────────────────────────
-keepattributes Signature
-keepattributes *Annotation*
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
