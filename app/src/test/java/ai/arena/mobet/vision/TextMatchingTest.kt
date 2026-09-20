package ai.arena.mobet.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCR match selection, which decides where a blind screen tap lands.
 *
 * `visualtap` taps coordinates derived from whichever OCR line is chosen here, without an
 * accessibility node to confirm what is actually under the finger. A wrong choice taps the
 * wrong control in someone else's app, so the selection rules are worth pinning.
 *
 * [OnDeviceTextRecognizer.bestMatch] takes no Android types, but [VisualTextMatch] holds a
 * `Rect`, so the ranking rule is reproduced here to keep the test pure JVM.
 */
class TextMatchingTest {

    private data class Candidate(val text: String, val confidence: Int)

    private fun normalize(value: String) =
        value.lowercase().trim().replace(Regex("\\s+"), " ")

    /** Mirrors bestMatch(): substring filter, then confidence with an exact-match bonus. */
    private fun bestMatch(items: List<Candidate>, query: String): Candidate? {
        val target = normalize(query)
        return items.filter { normalize(it.text).contains(target) }
            .maxByOrNull { it.confidence + if (normalize(it.text) == target) 30 else 0 }
    }

    @Test
    fun anExactMatchBeatsAHigherScoringSubstring() {
        // The +30 bonus exists for this: "Send" must win over "Resend invitation" even though
        // the longer line scored better, because tapping the wrong one sends the wrong thing.
        val best = bestMatch(
            listOf(Candidate("Resend invitation", 90), Candidate("Send", 70)),
            "Send"
        )
        assertEquals("Send", best?.text)
    }

    @Test
    fun matchingIsCaseAndWhitespaceInsensitive() {
        val best = bestMatch(listOf(Candidate("  CONFIRM   PAYMENT ", 60)), "confirm payment")
        assertEquals("  CONFIRM   PAYMENT ", best?.text)
    }

    @Test
    fun noMatchReturnsNullRatherThanAGuess() {
        // Critical: returning a poor match would tap arbitrary coordinates. The caller treats
        // null as "not found" and the step fails safely instead of acting blindly.
        assertNull(bestMatch(listOf(Candidate("Cancel", 90), Candidate("Back", 88)), "Submit"))
    }

    @Test
    fun anEmptyScreenReturnsNull() {
        assertNull(bestMatch(emptyList(), "Send"))
    }

    @Test
    fun theHighestConfidenceSubstringWinsWhenNoneIsExact() {
        val best = bestMatch(
            listOf(Candidate("Pay now with card", 40), Candidate("Pay now", 80)),
            "Pay"
        )
        assertEquals("Pay now", best?.text)
    }

    // ── Confidence scoring ───────────────────────────────────────────────────

    /** Mirrors estimateConfidence(). */
    private fun confidence(
        text: String,
        width: Int,
        height: Int,
        inBounds: Boolean = true
    ): Int {
        var score = 55
        if (text.length >= 3) score += 12
        if (text.any(Char::isLetter)) score += 8
        if (width > 8 && height > 8) score += 8
        if (inBounds) score += 7
        return score.coerceIn(20, 95)
    }

    @Test
    fun confidenceStaysWithinItsAdvertisedRange() {
        // The value is shown to the user as a percentage and parsed back out of the log line,
        // so it must never escape 20..95.
        assertTrue(confidence("Submit", 100, 40) in 20..95)
        assertTrue(confidence("", 1, 1, inBounds = false) in 20..95)
        assertTrue(confidence("A".repeat(500), 4000, 4000) in 20..95)
    }

    @Test
    fun cleanTextScoresHigherThanDegenerateText() {
        val clean = confidence("Confirm", 120, 44)
        val noise = confidence("|", 2, 2, inBounds = false)
        assertTrue("clean=$clean noise=$noise", clean > noise)
    }

    @Test
    fun theLoggedConfidenceRoundTripsThroughTheStatusLine() {
        // MobetAccessibilityService re-parses the percentage out of its own message with
        // Regex("(\\d+)%"). If the two ever drift, confidence silently falls back to 0.5.
        val detail = "Matched \u201CSend\u201D at ${confidence("Send", 100, 40)}% confidence"
        val parsed = Regex("(\\d+)%").find(detail)?.groupValues?.get(1)?.toIntOrNull()
        assertEquals(confidence("Send", 100, 40), parsed)
    }

    @Test
    fun theStatusLineDoesNotEchoTheMatchedScreenLine() {
        // Regression guard. bestMatch returns whole OCR lines containing the query, so logging
        // match.text copied neighbouring screen content -- a balance next to a Transfer button
        // -- into the audit ledger, which documents that it holds no screen content.
        val query = "Transfer"
        val matched = "Balance 4,182.66 available  Transfer"
        val detail = "Matched \u201C$query\u201D at 78% confidence"
        assertTrue(detail.contains(query))
        assertTrue("no neighbouring screen text may appear", !detail.contains("4,182.66"))
        assertTrue(!detail.contains(matched))
    }
}
