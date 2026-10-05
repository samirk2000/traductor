# Add project specific ProGuard rules here.

# Ktor / CIO
-keepclassmembers class io.ktor.** { *; }
-dontwarn io.ktor.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.arnold.voicetranslator.**$$serializer { *; }
-keepclassmembers class com.arnold.voicetranslator.** {
    *** Companion;
}
-keepclasseswithmembers class com.arnold.voicetranslator.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# kotlinx.coroutines
-dontwarn kotlinx.coroutines.**

# Ktor references slf4j, but the Android app does not ship a logger binding.
-dontwarn org.slf4j.impl.StaticLoggerBinder

# Drop verbose logs from release. R8 removes these calls when the return value is unused.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}
