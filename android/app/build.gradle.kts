plugins {
    id("com.android.application")
}

android {
    namespace = "local.haechi"
    compileSdk = 35

    defaultConfig {
        applicationId = "local.haechi"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
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
