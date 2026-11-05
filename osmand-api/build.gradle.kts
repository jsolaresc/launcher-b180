// Interfaz pública de OsmAnd para que otras apps lo controlen (AIDL), copiada de
// github.com/osmandapp/OsmAnd (rama r5.4, carpeta OsmAnd-api). Licencia GPLv3.
plugins {
    id("com.android.library")
}

android {
    namespace = "net.osmand.aidlapi"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    buildFeatures {
        aidl = true
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            aidl.srcDirs("src")
            java.srcDirs("src")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.6.0")
}
