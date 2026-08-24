plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.arnold.voicetranslator"
    compileSdk = 35

    // DeepSeek API key. Read from local.properties (root, git-ignored) first so
    // the secret never has to live in a committed file; gradle.properties (via
    // findProperty) is kept as a secondary fallback.
    val deepSeekApiKey: String = run {
        var fromLocal: String? = null
        val localProps = rootProject.file("local.properties")
        if (localProps.isFile) {
            try {
                val lines = localProps.readLines()
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("deepseek.apiKey=")) {
                        val v = trimmed.substringAfter('=').trim()
                        if (v.isNotEmpty()) fromLocal = v
                        break
                    }
                }
            } catch (_: Exception) {
                fromLocal = null
            }
        }
        fromLocal
            ?: ((project.findProperty("deepseek.apiKey") as? String)?.takeIf { it.isNotBlank() })
            ?: ""
    }

    defaultConfig {
        applicationId = "com.arnold.voicetranslator"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "DEEPSEEK_API_KEY", "\"$deepSeekApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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

    debugImplementation(libs.androidx.ui.tooling)
}
