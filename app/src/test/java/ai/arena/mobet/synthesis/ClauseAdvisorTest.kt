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
        val message = rejection("click \"Save\"")
        assertTrue(message.contains("not a clause verb"))
        assertTrue(message.contains("tap"))
    }

    @Test
    fun aSingleCharacterTypoIsRecognised() {
        assertTrue(ClauseAdvisor.advise("tpa \"Save\"")!!.contains("Did you mean"))
        assertTrue(ClauseAdvisor.advise("scrol")!!.contains("scroll"))
    }

    @Test
    fun anUnquotedTargetIsShownQuoted() {
        val advice = ClauseAdvisor.advise("tap Save")!!
        assertTrue(advice.contains("tap \"Save\""))
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
    fun guidanceDoesNotMakeABadClauseAcceptable() {
        listOf("click \"Save\"", "tap Save", "wait 3", "fill \"Email\"").forEach { goal ->
            assertTrue("“$goal” must stay rejected", IntentGrammar.parse(goal).isFailure)
        }
    }

    @Test
    fun acceptedClausesAreUntouched() {
        listOf("tap \"Save\"", "fill \"Email\" with \"a@b.com\"", "wait 3s", "back")
            .forEach { assertTrue("“$it” must parse", IntentGrammar.parse(it).isSuccess) }
    }
}
