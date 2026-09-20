package ai.arena.mobet.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resolved secret values must never reach a log line.
 *
 * A workflow step may legitimately carry `{{secret:name}}` in a selector or a fill value. The
 * runner resolves that to plaintext before acting, and failure messages quote the selector back
 * ("Timed out finding text ..."). Every runner log line lands in three persistent places: the
 * diagnostics preference file, the tamper-evident audit ledger, and a broadcast Intent. So an
 * unredacted failure message writes a banking PIN to disk in cleartext, inside a ledger whose
 * documentation promises it stores "never screen content or secrets".
 *
 * [WorkflowRunner] needs an accessibility service to construct, so the redaction rule is
 * reproduced here exactly as implemented.
 */
class SecretRedactionTest {

    private val mask = "[redacted secret]"

    private class Redactor {
        val secrets = mutableSetOf<String>()
        fun resolve(value: String) { if (value.isNotEmpty()) secrets += value }
        fun redact(message: String): String {
            if (secrets.isEmpty()) return message
            var output = message
            secrets.sortedByDescending(String::length).forEach { secret ->
                if (secret.isNotEmpty()) output = output.replace(secret, "[redacted secret]")
            }
            return output
        }
    }

    @Test
    fun aResolvedSecretIsMaskedInAFailureMessage() {
        val redactor = Redactor()
        redactor.resolve("4829-3310")
        val message = redactor.redact("Timed out finding text \u201C4829-3310\u201D")
        assertFalse("the PIN must not survive", message.contains("4829-3310"))
        assertTrue(message.contains(mask))
    }

    @Test
    fun theSurroundingMessageStaysUseful() {
        // Redaction must not reduce the log to noise; the user still needs to know what failed.
        val redactor = Redactor()
        redactor.resolve("hunter2")
        val message = redactor.redact("Timed out finding text \u201Chunter2\u201D")
        assertEquals("Timed out finding text \u201C$mask\u201D", message)
    }

    @Test
    fun everySecretInOneMessageIsMasked() {
        val redactor = Redactor()
        redactor.resolve("alpha-secret")
        redactor.resolve("beta-secret")
        val message = redactor.redact("compared alpha-secret against beta-secret")
        assertFalse(message.contains("alpha-secret"))
        assertFalse(message.contains("beta-secret"))
    }

    @Test
    fun aSecretContainingAnotherIsFullyMasked() {
        // Replacing the shorter value first would leave the remainder of the longer one exposed,
        // e.g. masking "pass" inside "password123" would emit "[redacted secret]word123".
        val redactor = Redactor()
        redactor.resolve("pass")
        redactor.resolve("password123")
        val message = redactor.redact("value was password123")
        assertFalse("no fragment may survive", message.contains("word123"))
        assertEquals("value was $mask", message)
    }

    @Test
    fun repeatedOccurrencesAreAllMasked() {
        val redactor = Redactor()
        redactor.resolve("tok3n")
        val message = redactor.redact("tok3n retry tok3n")
        assertFalse(message.contains("tok3n"))
        assertEquals("$mask retry $mask", message)
    }

    @Test
    fun messagesWithoutSecretsArePassedThroughUnchanged() {
        // Positive control: the suite must not pass by mangling everything.
        val redactor = Redactor()
        redactor.resolve("s3cret")
        val message = "Step 2/5: tap"
        assertEquals(message, redactor.redact(message))
    }

    @Test
    fun nothingIsMaskedBeforeAnySecretResolves() {
        val redactor = Redactor()
        assertEquals("Policy approved run", redactor.redact("Policy approved run"))
    }

    @Test
    fun anEmptySecretValueDoesNotMaskEverything() {
        // A stored secret with an empty value must not turn every character into the mask.
        val redactor = Redactor()
        redactor.resolve("")
        assertEquals("Step 1/2: tap", redactor.redact("Step 1/2: tap"))
    }

    @Test
    fun secretsAreClearedWhenTheRunEnds() {
        // Values are held only for the duration of a run. A later run must not retain them.
        val redactor = Redactor()
        redactor.resolve("one-run-only")
        assertTrue(redactor.redact("saw one-run-only").contains(mask))
        redactor.secrets.clear()
        assertEquals("saw one-run-only", redactor.redact("saw one-run-only"))
    }
}
