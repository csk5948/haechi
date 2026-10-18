import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing key: android/keystore.properties + the .jks it names (both git-ignored; back them up —
// an update signed with another key cannot be installed over this app, and removing the app deletes its CA key).
val signing = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "local.haechi"
    compileSdk = 35

    defaultConfig {
        applicationId = "local.haechi"
        minSdk = 26
        targetSdk = 34
        versionCode = 21
        versionName = "0.21"
    }

    signingConfigs {
        if (signing.getProperty("storeFile") != null) create("release") {
            storeFile = rootProject.file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            // Not debuggable: no `run-as`, so app files can't be copied off over USB.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Intentionally no `dependencies { }` block: zero third-party libraries.
// Everything uses the android.* framework APIs that ship with the phone.
