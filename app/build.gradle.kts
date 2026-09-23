import com.android.build.api.artifact.SingleArtifact

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Fails the build if the *merged* manifest declares a network permission.
 *
 * "Mobet cannot reach the network" is the one security property a user can verify without
 * trusting us, and it is the property that collapses screen-reading + exfiltration into an
 * inert capability. Checking the source manifest would be pointless: the risk is a transitive
 * dependency contributing `INTERNET` through manifest merging, which is invisible in our own
 * file and silent at runtime. So this reads the merged output, after AGP has combined every
 * library manifest.
 *
 * To intentionally add network access, remove the permission from [FORBIDDEN_PERMISSIONS] in a
 * reviewed commit and update docs/THREAT_MODEL.md. That is the point: it should be a deliberate,
 * visible decision rather than something a dependency bump can do by accident.
 */
abstract class VerifyNoNetworkPermission : DefaultTask() {

    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun verify() {
        val text = mergedManifest.get().asFile.readText()
        // Match the permission name as a whole attribute value so that, e.g.,
        // a custom "com.example.INTERNET_THING" cannot be mistaken for the platform permission.
        val declared = FORBIDDEN_PERMISSIONS.filter { permission ->
            Regex("""android:name\s*=\s*"${Regex.escape(permission)}"""").containsMatchIn(text)
        }
        if (declared.isEmpty()) return

        throw GradleException(
            buildString {
                appendLine("Merged manifest declares forbidden network permission(s):")
                declared.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("Mobet holds an accessibility service that can read every screen the")
                appendLine("user visits. Combined with network access that is an exfiltration")
                appendLine("channel, so the absence of these permissions is a load-bearing")
                appendLine("invariant documented in docs/THREAT_MODEL.md.")
                appendLine()
                appendLine("A dependency most likely contributed this via manifest merging. Run")
                appendLine("  gradle :app:processDebugMainManifest --info")
                appendLine("and inspect the merger report to find the contributing library. Remove")
                appendLine("it, or strip the permission with tools:node=\"remove\".")
                appendLine()
                appendLine("If network access is genuinely intended, this must be a deliberate,")
                appendLine("reviewed change: edit FORBIDDEN_PERMISSIONS in app/build.gradle.kts")
                appendLine("and update the threat model in the same commit.")
            }
        )
    }

    companion object {
        val FORBIDDEN_PERMISSIONS = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_NETWORK_STATE",
            "android.permission.CHANGE_WIFI_STATE",
            "android.permission.NEARBY_WIFI_DEVICES",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_SCAN"
        )
    }
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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    // KGP 2.3 DSL: kotlinOptions is deprecated/removed under the compiler the GenAI Prompt
    // client requires (its jars carry 2.3.0 metadata), so the target is set here.
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

/*
 * Register the merged-manifest network check for every variant and make `check` depend on it,
 * so it runs in CI alongside the unit tests rather than needing a bespoke workflow step.
 */
androidComponents {
    onVariants { variant ->
        val capitalized = variant.name.replaceFirstChar(Char::uppercase)
        val verify = tasks.register<VerifyNoNetworkPermission>(
            "verify${capitalized}NoNetworkPermission"
        ) {
            group = "verification"
            description = "Fails if the merged ${variant.name} manifest declares network permissions."
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        // assemble* must not succeed without this having run.
        //
        // The wiring is deferred with afterEvaluate because `onVariants` runs before AGP has
        // registered the per-variant lifecycle tasks, so resolving "assembleDebug" eagerly here
        // throws UnknownTaskException.
        //
        // Deliberately NOT wired into `check`: `check` already depends on `test`, and adding a
        // manifest-merge dependency there risks a cycle. CI invokes the verify tasks directly.
        afterEvaluate {
            tasks.named("assemble$capitalized") { dependsOn(verify) }
        }
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
    // Gemini Nano via AICore (ML Kit GenAI Prompt API — a thin client; the model lives in the
    // AICore system app, not the APK). Used only for device-gated, opt-in model assistance
    // (docs/FRONTIER.md pillar 1A); inference runs in system processes and the merged-manifest
    // audit still forbids any network permission landing in the app.
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")

    testImplementation("junit:junit:4.13.2")
    // Real org.json implementation for JVM unit tests (the android.jar version is stubbed).
    testImplementation("org.json:json:20260814")

    // On-device tests: the Android Keystore boundary (SecretStore, EncryptedStateStore) and
    // encrypted persistence (AuditLedger) cannot be meaningfully simulated on the JVM.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    // View-level verification of the Material 3 control surface and rotation-safe draft
    // persistence — the behaviour the walkthrough in docs/TESTING_WALKTHROUGH.md describes.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
