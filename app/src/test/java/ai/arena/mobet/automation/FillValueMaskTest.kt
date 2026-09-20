package ai.arena.mobet.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The one masking rule used by every read-only surface that renders a `fill` value (dry-run
 * report, visual step cards). A field muted in one place and shown in another would defeat
 * the point, so the rule is centralized in [FillValueMask] and locked here.
 */
class FillValueMaskTest {

    @Test
    fun literalValuesRenderAsBullets() {
        assertEquals("••••••••", FillValueMask.mask("correct horse battery staple"))
    }

    @Test
    fun maskLengthIsCoarsened() {
        // Short and long literals must be indistinguishable beyond the cap — the mask must
        // not encode the real length of what might be a password.
        assertEquals("•", FillValueMask.mask("x"))
        assertEquals("••••••••", FillValueMask.mask("x".repeat(64)))
        assertEquals("••••••••", FillValueMask.mask("12345678"))
        assertEquals(FillValueMask.mask("1234567").length, 7)
    }

    @Test
    fun secretAndVariableReferencesStayVerbatim() {
        // References are not values; hiding the name would leave the user unable to tell which
        // credential a step uses, and no plaintext is touched either way.
        assertEquals("{{secret:banking_pin}}", FillValueMask.mask("{{secret:banking_pin}}"))
        assertEquals("{{var:label}}", FillValueMask.mask("{{var:label}}"))
    }

    @Test
    fun emptyAndWhitespaceValuesAreHandled() {
        assertEquals("", FillValueMask.mask(""))
        assertEquals("•••", FillValueMask.mask("   "))
    }

    @Test
    fun maskedOutputNeverContainsTheLiteral() {
        val secret = "Hunter2!"
        assertFalse(FillValueMask.mask(secret).contains(secret))
    }
}
