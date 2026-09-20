package ai.arena.mobet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field rendering on a visual step card.
 *
 * Workflows can be imported from a shared file, so these strings are untrusted input rendered
 * into a card that allots two lines to the whole summary. A multi-line or very long field would
 * otherwise push the card's controls around or crowd out every other field.
 *
 * `StepBuilder.preview` is private and the class needs an Activity, so the rule is mirrored here.
 */
class StepPreviewTest {

    private val maxFieldPreview = 60

    private fun preview(value: String): String {
        val flattened = value.replace(Regex("\\s+"), " ").trim()
        return if (flattened.length <= maxFieldPreview) flattened
        else flattened.take(maxFieldPreview).trimEnd() + "\u2026"
    }

    @Test
    fun ordinaryValuesPassThroughUnchanged() {
        // Positive control: the common case must not be mangled.
        assertEquals("Network & internet", preview("Network & internet"))
    }

    @Test
    fun newlinesAreFlattened() {
        // A value containing newlines would otherwise make one card several lines tall and
        // shove the reorder and delete controls off their expected positions.
        assertEquals("line one line two", preview("line one\nline two"))
        assertFalse(preview("a\r\nb").contains("\n"))
    }

    @Test
    fun runsOfWhitespaceCollapse() {
        assertEquals("a b", preview("a     \t   b"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals("Submit", preview("   Submit   "))
    }

    @Test
    fun longValuesAreClippedWithAnEllipsis() {
        val clipped = preview("x".repeat(500))
        assertEquals(maxFieldPreview + 1, clipped.length)
        assertTrue(clipped.endsWith("\u2026"))
    }

    @Test
    fun aValueAtExactlyTheLimitIsNotClipped() {
        val exact = "y".repeat(maxFieldPreview)
        assertEquals(exact, preview(exact))
    }

    @Test
    fun aSecretReferenceIsShownAsTheReference() {
        // Deliberate: this is a reference, not a value. The stored secret is never read here,
        // and hiding the name would leave the user unable to tell which credential a step uses.
        assertEquals("{{secret:banking_pin}}", preview("{{secret:banking_pin}}"))
    }

    @Test
    fun anEmptyValueStaysEmpty() {
        assertEquals("", preview("   \n  "))
    }

    @Test
    fun aPathologicalFieldCannotCrowdOutTheOthers() {
        // The point of the cap: one hostile field must leave room for the rest of the summary.
        val hostile = preview("A".repeat(10_000) + "\n" + "B".repeat(10_000))
        assertTrue("was ${hostile.length}", hostile.length <= maxFieldPreview + 1)
    }
}
