plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// APK aparte, para probar el estéreo con Open Headunit como pantalla principal.
// No toca el launcher B180: tiene otro nombre de paquete y se instala al lado.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.jsolaresc.ohu"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jsolaresc.ohu"
        minSdk = 21
        targetSdk = 29
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    signingConfigs {
        create("shared") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
