# FlowDroid ProGuard rules
# We keep generous safety because reflection is used by Room, Hilt, and serialization.

# Keep crash-source stack traces useful.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Room
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-keep @androidx.room.Database class *

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Timber
-dontwarn org.jetbrains.annotations.**

# Kotlinx serialization — keep generated $serializer companions and descriptors
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.flowdroid.**$$serializer { *; }
-keepclassmembers class com.flowdroid.** {
    *** Companion;
}
-keepclasseswithmembers class com.flowdroid.** {
    public static ** INSTANCE;
    public static ** Companion;
}

# Eclipse Paho MQTT — uses reflection internally for socket factories and callbacks
-keep class org.eclipse.paho.** { *; }
-dontwarn org.eclipse.paho.**

# OkHttp + Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }

# NanoHTTPD (embedded HTTP server for webhook trigger)
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# Our package — keep classes referenced by manifest as-is
-keep class com.flowdroid.service.** { *; }
-keep class com.flowdroid.FlowDroidApplication { *; }
-keep class com.flowdroid.MainActivity { *; }
