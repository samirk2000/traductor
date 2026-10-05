# Add project specific ProGuard rules here.

# Ktor / CIO. The engine is loaded with ServiceLoader, which R8 cannot see
# from a call site, and AtomicFU updates volatile fields by name.
-keep class io.ktor.client.engine.** implements io.ktor.client.HttpClientEngineContainer
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
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers public class **$$serializer {
    private ** descriptor;
}

# Play Billing. The AAR ships the same consumer rules; keep them here so the
# AIDL interface, proxy activities, and proto fields survive R8.
-keep class com.android.vending.billing.** { *; }
-keepnames class com.android.billingclient.api.ProxyBillingActivity
-keepnames class com.android.billingclient.api.ProxyBillingActivityV2
-keepclassmembers class * extends com.google.android.gms.internal.play_billing.zzhk {
    <fields>;
}

# Kuromoji loads IPADIC *.bin with Class.getResourceAsStream relative to
# com.atilika.kuromoji.ipadic.Tokenizer. Renaming that package misses the files.
-keep class com.atilika.kuromoji.** { *; }
-dontwarn com.atilika.kuromoji.**

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
