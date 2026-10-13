import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing key, kept out of git in tv-client/keystore/ (back it up!). It is the key that signed
// v1.0.x; every published APK must be signed with it or it can't install over the existing app.
val releaseKeyProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.tvdiagnostics.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tvdiagnostics.app"
        minSdk = 21
        targetSdk = 34
        versionCode = 6
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (releaseKeyProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file("keystore/" + releaseKeyProps.getProperty("storeFile"))
                storePassword = releaseKeyProps.getProperty("storePassword")
                keyAlias = releaseKeyProps.getProperty("keyAlias")
                keyPassword = releaseKeyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // v1.0.x shipped as the debug variant, so TVs have "com.tvdiagnostics.app.debug"
            // installed; releases keep that package name so they update in place.
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}


dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    
    // Android TV Leanback library
    implementation("androidx.leanback:leanback:1.0.0")

    // AndroidX Media3 ExoPlayer for TV direct play streaming
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-common:1.3.1")

    // Image loading for 60s thumbnails
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // Networking & JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
}
