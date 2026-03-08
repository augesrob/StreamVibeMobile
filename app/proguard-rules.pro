# StreamVibe Mobile ProGuard Rules

# Keep TikTok SDK
-keep class com.bytedance.** { *; }
-keep class com.tiktok.** { *; }

# Keep OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Keep Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class com.streamvibe.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Hilt
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }

# Keep our models
-keep class com.streamvibe.mobile.domain.model.** { *; }

# OkIO
-dontwarn okio.**
