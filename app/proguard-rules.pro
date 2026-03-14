# StreamVibe Mobile ProGuard Rules

# ── Google ErrorProne annotations (compile-time only, not in runtime classpath) ──
# These are referenced by security-crypto / Tink but are annotation-only classes.
# R8 doesn't need them at runtime — suppress the missing class warnings.
-dontwarn com.google.errorprone.annotations.**
-keep class com.google.errorprone.annotations.** { *; }

# ── Google Tink (used by security-crypto / EncryptedSharedPreferences) ──
-dontwarn com.google.crypto.tink.**
-keep class com.google.crypto.tink.** { *; }

# ── OkHttp ──
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okio.**

# ── Kotlin Serialization ──
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class com.streamvibe.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class * { *; }

# ── Hilt / Dagger ──
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-dontwarn dagger.**

# ── Compose ──
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ── Our app models ──
-keep class com.streamvibe.mobile.domain.model.** { *; }
-keep class com.streamvibe.mobile.** { *; }

# ── Lifecycle / ViewModel ──
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel { <init>(...); }

# ── Remove TikTok SDK rules (not used in nosdk branch) ──
# -keep class com.bytedance.** { *; }
# -keep class com.tiktok.** { *; }
