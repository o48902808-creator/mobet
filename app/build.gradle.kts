plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.arena.mobet"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.arena.mobet"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.7.0"
        // The whole icon set is vector drawables; no raster assets are shipped.
        vectorDrawables.useSupportLibrary = true
    }

    buildFeatures { buildConfig = true }

    /*
     * Release signing.
     *
     * A debug-signed APK is signed with a key that differs per build machine, so users hit
     * INSTALL_FAILED_UPDATE_INCOMPATIBLE and must uninstall — which, with allowBackup=false,
     * destroys their workflow library. Supplying a real keystore keeps updates in place.
     *
     * Credentials come from environment variables (CI secrets) or local.properties, and are
     * never committed. When absent the release build simply stays unsigned rather than
     * silently falling back to the debug key.
     */
    val keystorePath = System.getenv("MOBET_KEYSTORE_PATH")
        ?: project.findProperty("mobet.keystore.path") as String?
    val keystorePassword = System.getenv("MOBET_KEYSTORE_PASSWORD")
        ?: project.findProperty("mobet.keystore.password") as String?
    val keyAliasName = System.getenv("MOBET_KEY_ALIAS")
        ?: project.findProperty("mobet.key.alias") as String?
    val keyPasswordValue = System.getenv("MOBET_KEY_PASSWORD")
        ?: project.findProperty("mobet.key.password") as String?
    val hasSigningMaterial = !keystorePath.isNullOrBlank() &&
        !keystorePassword.isNullOrBlank() &&
        !keyAliasName.isNullOrBlank() &&
        !keyPasswordValue.isNullOrBlank()

    signingConfigs {
        if (hasSigningMaterial) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keyAliasName
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasSigningMaterial) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    // Material 3 components: cards, bottom sheets, snackbars, chips, text fields.
    implementation("com.google.android.material:material:1.12.0")
    // Bundled on-device OCR model; no network connection is required at runtime.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
    // Real org.json implementation for JVM unit tests (the android.jar version is stubbed).
    testImplementation("org.json:json:20240303")
}
