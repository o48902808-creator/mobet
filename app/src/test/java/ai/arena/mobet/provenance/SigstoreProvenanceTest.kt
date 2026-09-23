package ai.arena.mobet.provenance

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class SigstoreProvenanceTest {
    private val manifest = BuildManifest(
        schema = BuildManifest.SCHEMA,
        applicationId = "ai.arena.mobet",
        versionName = "0.8.0",
        versionCode = 9,
        buildType = "debug",
        expectedSigning = "debug",
        commit = "abc123",
        workflow = "o48902808-creator/mobet/.github/workflows/release.yml@refs/tags/v0.8.0",
        attestation = "ref",
        policyVersion = "v1",
        ledgerSchemaVersion = "v1",
        modelEngine = "local",
        voiceEngine = "local",
        invariants = emptyList(),
        manifestSha256 = "digest"
    )

    @Test fun authenticatedSlsaClaimsMustMatchPinnedBuildIdentity() {
        assertTrue(SigstoreProvenance.verifyStatement(statement(), manifest).isEmpty())
    }

    @Test fun wrongRepositoryCommitAndSubjectFailClosed() {
        val source = statement()
        source.getJSONArray("subject").getJSONObject(0).put("name", "other.apk")
        val definition = source.getJSONObject("predicate").getJSONObject("buildDefinition")
        definition.getJSONObject("externalParameters").getJSONObject("workflow")
            .put("repository", "https://github.com/attacker/repo")
        definition.getJSONArray("resolvedDependencies").getJSONObject(0)
            .getJSONObject("digest").put("gitCommit", "attacker")
        val failures = SigstoreProvenance.verifyStatement(source, manifest)
        assertTrue(failures.any { it.contains("mobet.apk") })
        assertTrue(failures.any { it.contains("repository") })
        assertTrue(failures.any { it.contains("commit") })
    }

    private fun statement() = JSONObject()
        .put("_type", "https://in-toto.io/Statement/v1")
        .put("subject", JSONArray().put(JSONObject()
            .put("name", "mobet.apk")
            .put("digest", JSONObject().put("sha256", "bound-by-sigstore-java"))))
        .put("predicateType", SigstoreProvenance.SLSA_V1)
        .put("predicate", JSONObject()
            .put("buildDefinition", JSONObject()
                .put("externalParameters", JSONObject().put("workflow", JSONObject()
                    .put("repository", "https://github.com/o48902808-creator/mobet")
                    .put("path", ".github/workflows/release.yml")
                    .put("ref", "refs/tags/v0.8.0")))
                .put("resolvedDependencies", JSONArray().put(JSONObject()
                    .put("uri", "git+https://github.com/o48902808-creator/mobet")
                    .put("digest", JSONObject().put("gitCommit", "abc123")))))
            .put("runDetails", JSONObject()
                .put("builder", JSONObject().put("id", "https://github.com/actions/runner/github-hosted"))))
}
