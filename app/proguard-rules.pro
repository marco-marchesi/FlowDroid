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

# Our package — keep classes referenced by manifest as-is
-keep class com.flowdroid.service.** { *; }
-keep class com.flowdroid.FlowDroidApplication { *; }
-keep class com.flowdroid.MainActivity { *; }
