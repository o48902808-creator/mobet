package ai.arena.mobet.provenance

import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildIntegrityTest {
    private val facts = RuntimeBuildFacts(
        applicationId = "ai.arena.mobet",
        versionName = "0.8.0",
        versionCode = 9,
        buildType = "debug",
        signing = "debug",
        requestedPermissions = setOf("android.permission.RECORD_AUDIO")
    )

    private fun validManifest(): String {
        val json = JSONObject()
            .put("schema", "mobet.capability.v1")
            .put("applicationId", "ai.arena.mobet")
            .put("versionName", "0.8.0")
            .put("versionCode", 9)
            .put("buildType", "debug")
            .put("expectedSigning", "debug")
            .put("commit", "development")
            .put("workflow", "local-build")
            .put("attestation", "unavailable")
            .put("policyVersion", "deterministic-policy.v1")
            .put("ledgerSchemaVersion", "mobet.ledger.v1/hash-chain.v2")
            .put("modelEngine", "local")
            .put("voiceEngine", "on-device")
            .put("invariants", org.json.JSONArray(BuildIntegrity.requiredInvariants.sorted()))
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(BuildIntegrity.canonicalJson(json).toByteArray())
            .joinToString("") { "%02x".format(it) }
        return json.put("manifestSha256", digest).toString()
    }

    @Test fun validManifestMatchesRuntime() {
        val (manifest, failures) = BuildIntegrity.verifyManifest(validManifest(), facts)
        assertNotNull(manifest)
        assertTrue(failures.toString(), failures.isEmpty())
    }

    @Test fun tamperedManifestDigestIsRejected() {
        val tampered = JSONObject(validManifest()).put("commit", "attacker").toString()
        val (_, failures) = BuildIntegrity.verifyManifest(tampered, facts)
        assertTrue(failures.any { it.contains("digest mismatch") })
    }

    @Test fun packageAndSigningMismatchesAreRejected() {
        val (_, failures) = BuildIntegrity.verifyManifest(
            validManifest(),
            facts.copy(versionCode = 10, signing = "release")
        )
        assertTrue(failures.any { it.contains("Version code") })
        assertTrue(failures.any { it.contains("Signing mismatch") })
    }

    @Test fun forbiddenNetworkPermissionFailsClosed() {
        val (_, failures) = BuildIntegrity.verifyManifest(
            validManifest(),
            facts.copy(requestedPermissions = setOf("android.permission.INTERNET"))
        )
        assertTrue(failures.any { it.contains("Forbidden network") })
    }

    @Test fun malformedManifestDoesNotCrashVerifier() {
        val (manifest, failures) = BuildIntegrity.verifyManifest("not-json", facts)
        assertEquals(null, manifest)
        assertTrue(failures.single().contains("could not be parsed"))
    }
}
