# PhoneAgent ProGuard Rules

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.phoneagent.**$$serializer { *; }
-keepclassmembers class com.phoneagent.** {
    *** Companion;
}
-keepclasseswithmembers class com.phoneagent.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# Keep Netty
-keep class io.netty.** { *; }
-dontwarn io.netty.**

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep Room entities
-keep class com.phoneagent.memory.** { *; }
-keep class com.phoneagent.scheduler.ScheduledTask { *; }

# Keep Shizuku
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
