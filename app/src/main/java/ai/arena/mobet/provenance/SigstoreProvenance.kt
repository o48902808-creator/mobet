package ai.arena.mobet.provenance

import android.content.Context
import dev.sigstore.KeylessVerifier
import dev.sigstore.TrustedRootProvider
import dev.sigstore.VerificationOptions
import dev.sigstore.bundle.Bundle
import dev.sigstore.strings.StringMatcher
import org.json.JSONObject
import java.io.File
import java.io.StringReader

/** Result returned only after cryptographic Sigstore verification and SLSA policy checks. */
data class ProvenanceVerification(
    val verified: Boolean,
    val signerIdentity: String?,
    val sourceRepository: String?,
    val sourceRevision: String?,
    val builderId: String?,
    val failures: List<String>
)

/**
 * Offline GitHub/Sigstore verifier. The trust root is an immutable APK asset reviewed in source;
 * no TUF, Fulcio, Rekor, GitHub, or other network request is made at verification time.
 */
object SigstoreProvenance {
    const val TRUST_ROOT_ASSET = "sigstore-trusted-root.json"
    const val OIDC_ISSUER = "https://token.actions.githubusercontent.com"
    const val SLSA_V1 = "https://slsa.dev/provenance/v1"
    private const val IN_TOTO_V1 = "https://in-toto.io/Statement/v1"
    private const val MAX_BUNDLE_BYTES = 1_048_576

    fun verifyInstalledApk(
        context: Context,
        bundleSource: String,
        manifest: BuildManifest
    ): ProvenanceVerification {
        if (bundleSource.toByteArray().size > MAX_BUNDLE_BYTES) return failed("Bundle exceeds 1 MB")
        val expectedIdentity = "https://github.com/${manifest.workflow}"
        return runCatching {
            val bundle = Bundle.from(StringReader(bundleSource))
            val rootFile = copyPinnedRoot(context)
            val matcher = VerificationOptions.CertificateMatcher.fulcio()
                .issuer(StringMatcher.string(OIDC_ISSUER))
                .subjectAlternativeName(StringMatcher.string(expectedIdentity))
                .build()
            val options = VerificationOptions.builder().addCertificateMatchers(matcher).build()
            val verifier = KeylessVerifier.builder()
                .trustedRootProvider(TrustedRootProvider.from(rootFile.toPath()))
                .build()
            verifier.verify(File(context.applicationInfo.sourceDir).toPath(), bundle, options)

            val envelope = bundle.dsseEnvelope.orElseThrow { IllegalArgumentException("DSSE envelope is required") }
            val statement = JSONObject(envelope.payloadAsString)
            val failures = verifyStatement(statement, manifest)
            val predicate = statement.getJSONObject("predicate")
            val definition = predicate.getJSONObject("buildDefinition")
            val workflow = definition.getJSONObject("externalParameters").getJSONObject("workflow")
            val dependencies = definition.optJSONArray("resolvedDependencies")
            val sourceRevision = dependencies?.let { values ->
                (0 until values.length()).asSequence().map { values.getJSONObject(it) }
                    .mapNotNull { it.optJSONObject("digest")?.optString("gitCommit") }
                    .firstOrNull(String::isNotBlank)
            }
            ProvenanceVerification(
                verified = failures.isEmpty(),
                signerIdentity = expectedIdentity,
                sourceRepository = workflow.optString("repository"),
                sourceRevision = sourceRevision,
                builderId = predicate.getJSONObject("runDetails").getJSONObject("builder").optString("id"),
                failures = failures
            )
        }.getOrElse { failed("Sigstore verification failed: ${it.message ?: it::class.simpleName}") }
    }

    /** Policy evaluation is pure and runs only on the payload authenticated above. */
    internal fun verifyStatement(statement: JSONObject, manifest: BuildManifest): List<String> = buildList {
        if (statement.optString("_type") != IN_TOTO_V1) add("Unsupported in-toto statement type")
        if (statement.optString("predicateType") != SLSA_V1) add("SLSA provenance v1 predicate is required")
        val subjects = statement.optJSONArray("subject")
        if (subjects == null || subjects.length() != 1 ||
            subjects.optJSONObject(0)?.optString("name") != "mobet.apk") {
            add("Provenance must contain exactly the mobet.apk subject")
        }
        val predicate = statement.optJSONObject("predicate")
        val definition = predicate?.optJSONObject("buildDefinition")
        val workflow = definition?.optJSONObject("externalParameters")?.optJSONObject("workflow")
        val expectedRepo = "https://github.com/" + manifest.workflow.substringBefore("/.github/workflows/")
        if (workflow?.optString("repository") != expectedRepo) add("Source repository does not match capability manifest")
        if (workflow?.optString("path") != ".github/workflows/release.yml") add("Unexpected builder workflow path")
        val commits = definition?.optJSONArray("resolvedDependencies")
        val commitMatched = commits != null && (0 until commits.length()).any { index ->
            commits.optJSONObject(index)?.optJSONObject("digest")?.optString("gitCommit") == manifest.commit
        }
        if (!commitMatched) add("Source commit does not match capability manifest")
        val builder = predicate?.optJSONObject("runDetails")?.optJSONObject("builder")?.optString("id").orEmpty()
        if (!builder.startsWith("https://github.com/actions/runner/")) add("Unexpected builder identity")
    }

    private fun copyPinnedRoot(context: Context): File {
        val target = File(context.cacheDir, "sigstore-trusted-root.json")
        context.assets.open(TRUST_ROOT_ASSET).use { input ->
            target.outputStream().use(input::copyTo)
        }
        return target
    }

    private fun failed(message: String) = ProvenanceVerification(
        false, null, null, null, null, listOf(message)
    )
}
