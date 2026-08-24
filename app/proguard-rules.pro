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
