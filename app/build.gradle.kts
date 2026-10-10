import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("app.cash.paparazzi") version "2.0.0-alpha02"
}

// Optional. CI and other machines build an unsigned release bundle.
// The Play upload machine keeps keystore.properties in the project root:
//   storeFile=keystore/talkasia-upload.jks
//   storePassword=...
//   keyAlias=...
//   keyPassword=...
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use(keystoreProperties::load)
}

android {
    namespace = "com.arnold.voicetranslator"
    compileSdk = 36

    // No API keys of any kind are read or embedded here anymore. All online
    // translation (Google Translate + DeepSeek) is proxied through our own
    // Cloudflare Worker (see /worker); the Worker holds those secrets
    // server-side and this client only ever talks to our own domain.
    defaultConfig {
        applicationId = "com.arnold.voicetranslator"
        minSdk = 24
        targetSdk = 36
        versionCode = 12
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // One-time product created in Play Console. Change the id here and in the console together.
        buildConfigField("String", "PREMIUM_PRODUCT_ID", "\"premium_unlock\"")
        // Personal sideload sets this true. Play release and debug stay false.
        buildConfigField("boolean", "FORCE_PREMIUM", "false")
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            // Install next to the Play Store release (same id, Google signature).
            // namespace stays com.arnold.voicetranslator; only the install id changes.
            applicationIdSuffix = ".debug"
        }
        // Owner sideload. Installs beside the Play app as
        // com.arnold.voicetranslator.personal, signed with the debug keystore
        // so it builds without keystore.properties. Release gating is unchanged.
        create("personal") {
            isDebuggable = false
            isMinifyEnabled = false
            applicationIdSuffix = ".personal"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "FORCE_PREMIUM", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // kuromoji-core and kuromoji-ipadic both bundle identical
            // metadata/license files, which collide during resource merging.
            excludes += "/META-INF/*.md"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.mlkit.translate)
    implementation(libs.kuromoji.ipadic)
    implementation(libs.billing)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation("junit:junit:4.13.2")
}
