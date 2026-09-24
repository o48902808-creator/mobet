package ai.arena.mobet.synthesis

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The advisor explains rejections; it must never turn one into an acceptance. */
class ClauseAdvisorTest {

    private fun rejection(goal: String): String =
        IntentGrammar.parse(goal).exceptionOrNull()!!.message!!

    @Test
    fun aFamiliarSynonymIsMappedToTheAcceptedVerb() {
        // "swipe" is not in the grammar; "scroll" is.
        val message = rejection("swipe \"Save\"")
        assertTrue(message, message.contains("not a clause verb"))
        assertTrue(message, message.contains("scroll"))
    }

    @Test
    fun verbsTheGrammarAlreadyAcceptsAreNotSecondGuessed() {
        // These parse today; the advisor must never claim otherwise.
        listOf("click \"Save\"", "enter \"Email\" with \"a\"", "check \"Saved\" appears")
            .forEach { assertTrue("“$it” should parse", IntentGrammar.parse(it).isSuccess) }
        // A clause that parses is never described as a wrong verb.
        assertTrue(ClauseAdvisor.advise("tap \"Save\"")!!.contains("Accepted clause forms"))
    }

    @Test
    fun aTranspositionIsRecognised() {
        val advice = ClauseAdvisor.advise("tpa \"Save\"")!!
        assertTrue(advice, advice.contains("Did you mean"))
        assertTrue(advice, advice.contains("tap"))
    }

    @Test
    fun aDroppedLetterIsRecognised() {
        assertTrue(ClauseAdvisor.advise("scrol")!!.contains("scroll"))
        assertTrue(ClauseAdvisor.advise("verfy \"Saved\"")!!.contains("verify"))
    }

    @Test
    fun anOddQuoteIsDiagnosedBeforeAnythingElse() {
        assertTrue(ClauseAdvisor.advise("tap \"Save")!!.contains("odd number of quotation marks"))
    }

    @Test
    fun aDelayWithoutAUnitIsExplained() {
        val advice = ClauseAdvisor.advise("wait 3")!!
        assertTrue(advice.contains("wait 3s"))
        assertTrue(advice.contains("wait 3ms"))
    }

    @Test
    fun aFillWithoutAValueIsExplained() {
        assertTrue(ClauseAdvisor.advise("fill \"Email\"")!!.contains("with \"value\""))
    }

    @Test
    fun anythingElseFallsBackToTheAcceptedForms() {
        val advice = ClauseAdvisor.advise("qwertyuiop asdfgh")!!
        assertTrue(advice.contains("Accepted clause forms"))
        assertTrue(advice.contains("repeat scroll until"))
    }

    @Test
    fun adviceIsNeverGivenForAnEmptyClause() {
        assertNull(ClauseAdvisor.advise("   "))
    }

    @Test
    fun twoLetterFragmentsGetNoVerbGuess() {
        // One edit relates almost any pair of very short words, so no verb is suggested.
        assertTrue(ClauseAdvisor.advise("ta")!!.contains("Accepted clause forms"))
    }

    @Test
    fun guidanceDoesNotMakeABadClauseAcceptable() {
        listOf("swipe \"Save\"", "tpa \"Save\"", "wait 3", "fill \"Email\"").forEach { goal ->
            assertTrue("“$goal” must stay rejected", IntentGrammar.parse(goal).isFailure)
        }
    }

    @Test
    fun acceptedClausesAreUntouched() {
        listOf("tap \"Save\"", "fill \"Email\" with \"a@b.com\"", "wait 3s", "back")
            .forEach { assertTrue("“$it” must parse", IntentGrammar.parse(it).isSuccess) }
    }
}
