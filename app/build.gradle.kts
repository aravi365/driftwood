plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aravi365.driftwood"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.aravi365.driftwood"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    // Release key lives outside the repo, in ~/.gradle/gradle.properties (see README).
    // Without it, release builds fall back to the debug key so anyone can still build.
    val storeFile = providers.gradleProperty("DRIFTWOOD_STORE_FILE").orNull
    signingConfigs {
        if (storeFile != null) create("release") {
            this.storeFile = file(storeFile)
            storePassword = providers.gradleProperty("DRIFTWOOD_STORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("DRIFTWOOD_KEY_ALIAS").get()
            keyPassword = providers.gradleProperty("DRIFTWOOD_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
