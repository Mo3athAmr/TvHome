import java.util.Properties

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.moath.tvhome"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.moath.tvhome"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += setOf("en", "ar")
        buildConfigField("String", "INSTAGRAM_HANDLE", "\"${localProps.getProperty("INSTAGRAM_HANDLE", "")}\"")
        buildConfigField("String", "TMDB_READ_TOKEN", "\"${localProps.getProperty("TMDB_READ_TOKEN", "")}\"")
        // Assistant keys (local.properties only, never in source).
        buildConfigField("String", "GEMINI_API_KEY", "\"${localProps.getProperty("GEMINI_API_KEY", "")}\"")
        buildConfigField("String", "EXPLABS_API_KEY", "\"${localProps.getProperty("EXPLABS_API_KEY", "")}\"")
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"${localProps.getProperty("ANTHROPIC_API_KEY", "")}\"")
        buildConfigField("String", "ANTHROPIC_WORKSPACE_ID", "\"${localProps.getProperty("ANTHROPIC_WORKSPACE_ID", "")}\"")
        // Lets parallel worktrees install side by side on one emulator: -PidSuffix=.cc
        providers.gradleProperty("idSuffix").orNull?.let { applicationIdSuffix = it }
    }

    buildTypes {
        // The box is a 2 GB low-end TV box: a debuggable Compose build is visibly
        // slower there, so the installed build is release (R8) signed with the debug key.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint { abortOnError = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    // Talks to the TV's own adbd (network debugging) to press keys that accessibility cannot.
    implementation("dev.mobile:dadb:1.2.10")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation(platform("androidx.compose:compose-bom:2026.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
