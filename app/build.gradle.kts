plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Số phiên bản tăng theo mỗi lần GitHub build, để cài đè bản cũ được.
val buildNo = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toIntOrNull() ?: 1

android {
    namespace = "vn.maythu.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "vn.maythu.app"
        minSdk = 21
        targetSdk = 34
        versionCode = buildNo
        versionName = "1.0.$buildNo"
    }

    signingConfigs {
        create("maythu") {
            storeFile = file("maythu.keystore")
            storePassword = "maythu2026"
            keyAlias = "maythu"
            keyPassword = "maythu2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("maythu")
        }
        debug {
            signingConfig = signingConfigs.getByName("maythu")
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
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-session:$media3")

    implementation("io.coil-kt:coil-compose:2.7.0")
}
