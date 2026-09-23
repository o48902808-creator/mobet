package ai.arena.mobet.provenance

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.speech.SpeechRecognizer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** Immutable provenance payload embedded at assets/mobet-capability.json. */
data class BuildManifest(
    val schema: String,
    val applicationId: String,
    val versionName: String,
    val versionCode: Long,
    val buildType: String,
    val expectedSigning: String,
    val commit: String,
    val workflow: String,
    val attestation: String,
    val policyVersion: String,
    val ledgerSchemaVersion: String,
    val modelEngine: String,
    val voiceEngine: String,
    val invariants: List<String>,
    val manifestSha256: String
) {
    companion object {
        const val ASSET_NAME = "mobet-capability.json"
        const val SCHEMA = "mobet.capability.v1"

        fun parse(source: String): BuildManifest {
            val json = JSONObject(source)
            val invariants = json.getJSONArray("invariants")
            return BuildManifest(
                schema = json.getString("schema"),
                applicationId = json.getString("applicationId"),
                versionName = json.getString("versionName"),
                versionCode = json.getLong("versionCode"),
                buildType = json.getString("buildType"),
                expectedSigning = json.getString("expectedSigning"),
                commit = json.getString("commit"),
                workflow = json.getString("workflow"),
                attestation = json.getString("attestation"),
                policyVersion = json.getString("policyVersion"),
                ledgerSchemaVersion = json.getString("ledgerSchemaVersion"),
                modelEngine = json.getString("modelEngine"),
                voiceEngine = json.getString("voiceEngine"),
                invariants = List(invariants.length()) { invariants.getString(it) },
                manifestSha256 = json.getString("manifestSha256")
            )
        }
    }
}

data class RuntimeBuildFacts(
    val applicationId: String,
    val versionName: String,
    val versionCode: Long,
    val buildType: String,
    val signing: String,
    val requestedPermissions: Set<String>
)

data class BuildIntegrityReport(
    val manifest: BuildManifest?,
    val apkSha256: String,
    val actualSigning: String,
    val modelStatus: String,
    val voiceStatus: String,
    val failures: List<String>
) {
    val verified: Boolean get() = manifest != null && failures.isEmpty()
    val shortApkDigest: String get() = apkSha256.take(16).ifBlank { "unavailable" }
}

/** Offline verifier for the embedded manifest, installed package metadata, and APK bytes. */
object BuildIntegrity {
    val requiredInvariants = setOf(
        "no-network-permission",
        "deterministic-policy-authority",
        "hash-chained-ledger",
        "model-output-is-untrusted"
    )

    val forbiddenNetworkPermissions = setOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.CHANGE_NETWORK_STATE",
        "android.permission.CHANGE_WIFI_STATE",
        "android.permission.NEARBY_WIFI_DEVICES",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.BLUETOOTH_SCAN"
    )

    /** Pure consistency checks, kept separate so malformed/tampered manifests are JVM-testable. */
    fun verifyManifest(source: String, facts: RuntimeBuildFacts): Pair<BuildManifest?, List<String>> {
        val failures = mutableListOf<String>()
        val manifest = try {
            BuildManifest.parse(source)
        } catch (error: Exception) {
            return null to listOf("Capability manifest could not be parsed: ${error.message}")
        }

        // artifact/sha256 are sidecar-only extensions added after the APK exists. They cannot be
        // part of the embedded payload digest without making the APK hash self-referential.
        val json = JSONObject(source).apply {
            remove("manifestSha256")
            remove("artifact")
            remove("sha256")
        }
        val actualManifestDigest = sha256(canonicalJson(json).toByteArray(Charsets.UTF_8))
        if (!manifest.manifestSha256.equals(actualManifestDigest, ignoreCase = true)) {
            failures += "Embedded manifest digest mismatch"
        }
        if (manifest.schema != BuildManifest.SCHEMA) failures += "Unsupported schema: ${manifest.schema}"
        if (manifest.applicationId != facts.applicationId) failures += "Application ID does not match installed package"
        if (manifest.versionName != facts.versionName) failures += "Version name does not match installed package"
        if (manifest.versionCode != facts.versionCode) failures += "Version code does not match installed package"
        if (manifest.buildType != facts.buildType) failures += "Build type does not match installed package"
        if (manifest.expectedSigning != facts.signing) {
            failures += "Signing mismatch: expected ${manifest.expectedSigning}, found ${facts.signing}"
        }
        val missing = requiredInvariants - manifest.invariants.toSet()
        if (missing.isNotEmpty()) failures += "Missing invariant(s): ${missing.sorted().joinToString()}"
        val forbidden = facts.requestedPermissions intersect forbiddenNetworkPermissions
        if (forbidden.isNotEmpty()) failures += "Forbidden network permission(s): ${forbidden.sorted().joinToString()}"
        return manifest to failures
    }

    fun inspect(context: Context): BuildIntegrityReport {
        val source = runCatching {
            context.assets.open(BuildManifest.ASSET_NAME).bufferedReader().use { it.readText() }
        }.getOrElse { "" }
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_PERMISSIONS
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES or PackageManager.GET_PERMISSIONS
            )
        }
        val signing = signingKind(packageInfo)
        val facts = RuntimeBuildFacts(
            applicationId = context.packageName,
            versionName = packageInfo.versionName.orEmpty(),
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            },
            buildType = if (
                (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            ) "debug" else "release",
            signing = signing,
            requestedPermissions = packageInfo.requestedPermissions?.toSet().orEmpty()
        )
        val (manifest, checks) = verifyManifest(source, facts)
        val apkDigest = runCatching { sha256(File(context.applicationInfo.sourceDir)) }.getOrElse { "" }
        val failures = checks.toMutableList()
        if (apkDigest.isBlank()) failures += "Installed APK digest could not be read"

        val voice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            "Available · Android on-device recognizer"
        } else {
            "Unavailable · no cloud fallback"
        }
        return BuildIntegrityReport(
            manifest = manifest,
            apkSha256 = apkDigest,
            actualSigning = signing,
            modelStatus = "Ready · deterministic local engine; AICore is optional and device-gated",
            voiceStatus = voice,
            failures = failures
        )
    }

    private fun signingKind(packageInfo: android.content.pm.PackageInfo): String {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures.orEmpty()
        }
        if (signatures.isEmpty()) return "unsigned"
        val debug = signatures.any { signature ->
            runCatching {
                val certificate = CertificateFactory.getInstance("X.509")
                    .generateCertificate(signature.toByteArray().inputStream()) as X509Certificate
                certificate.subjectX500Principal.name.contains("CN=Android Debug", ignoreCase = true)
            }.getOrDefault(false)
        }
        return if (debug) "debug" else "release"
    }

    private fun sha256(file: File): String = file.inputStream().buffered().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /** Matches Python json.dumps(sort_keys=True, separators=(",", ":")) for this schema. */
    internal fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
            JSONObject.quote(key) + ":" + canonicalJson(value.get(key))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Boolean, is Number -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
}
