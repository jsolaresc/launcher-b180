plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Cada compilación en GitHub suma 1 a la versión, así el estéreo acepta la actualización.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.jsolaresc.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jsolaresc.launcher"
        minSdk = 21
        // 29 = Android 10. Evita las restricciones de visibilidad de apps de Android 11+.
        targetSdk = 29
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    // Clave fija guardada en el repo: todas las versiones se firman igual,
    // así cada APK nuevo se instala encima del anterior sin desinstalar.
    signingConfigs {
        create("shared") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
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
