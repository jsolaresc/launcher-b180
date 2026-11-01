plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Cada compilación en GitHub suma 1 a la versión, así el estéreo acepta la actualización.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

// Clave gratuita de CARTO para las teselas del mapa (secreto CARTO_KEY en GitHub).
val cartoKey = (System.getenv("CARTO_KEY") ?: rootProject.file("carto.key").takeIf { it.exists() }?.readText() ?: "").trim()

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
        buildConfigField("String", "CARTO_KEY", "\"$cartoKey\"")
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

    buildFeatures {
        buildConfig = true
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

dependencies {
    // Mapa liviano de teselas (raster): anda bien en la GPU Mali-450 y guarda caché para usar sin señal.
    implementation("org.osmdroid:osmdroid-android:6.1.18")
}
