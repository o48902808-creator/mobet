package ai.arena.mobet.synthesis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentGrammarTest {

    private fun parse(goal: String): List<Intent> = IntentGrammar.parse(goal).getOrThrow()

    @Test
    fun sequentialClausesBecomeOrderedIntents() {
        val intents = parse("open \"Network & internet\" then tap \"Wi-Fi\" then go back")
        assertEquals(3, intents.size)
        assertTrue((intents[0] as TapIntent).navigational)
        assertEquals("Wi-Fi", (intents[1] as TapIntent).target)
        assertTrue(intents[2] is BackIntent)
    }

    @Test
    fun fillCaptureTargetAndValue() {
        val intent = parse("fill \"Email\" with \"you@example.com\"").single() as FillIntent
        assertEquals("Email", intent.target)
        assertEquals("you@example.com", intent.value)
    }

    @Test
    fun conditionalParsesIntoBoundedBody() {
        val intent = parse("if \"Not now\" appears then tap \"Not now\"").single() as ConditionalIntent
        assertEquals("Not now", intent.whenText)
        assertTrue(intent.present)
        assertEquals(1, intent.body.size)
    }

    @Test
    fun absentConditionIsRecognised() {
        val intent = parse("if \"Signed in\" is missing then tap \"Sign in\"").single() as ConditionalIntent
        assertTrue(!intent.present)
    }

    @Test
    fun repeatCarriesIterationCap() {
        val intent = parse("repeat scroll until \"Terms\" appears max 7").single() as RepeatIntent
        assertEquals("Terms", intent.untilText)
        assertEquals(7, intent.maxIterations)
        assertTrue(intent.body.single() is ScrollIntent)
    }

    @Test
    fun delayUnitsAreNormalisedToMillis() {
        assertEquals(2_000L, (parse("wait 2s").single() as DelayIntent).millis)
        assertEquals(750L, (parse("wait 750ms").single() as DelayIntent).millis)
    }

    @Test
    fun verificationAttachesToAPrecedingStep() {
        val intents = parse("tap \"Save\"; verify \"Saved\" appears")
        assertEquals(2, intents.size)
        val verify = intents[1] as VerifyIntent
        assertEquals("Saved", verify.text)
        assertTrue(verify.present)
    }

    @Test
    fun leadingVerificationIsRejected() {
        val result = IntentGrammar.parse("verify \"Saved\" appears")
        assertTrue(result.isFailure)
    }

    @Test
    fun unknownClauseIsAnErrorNotASilentDrop() {
        val result = IntentGrammar.parse("tap \"Save\" then teleport to orbit")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not understood"))
    }

    @Test
    fun nestedControlFlowIsRejected() {
        val result = IntentGrammar.parse("repeat if \"A\" appears then tap \"A\" until \"B\" appears")
        assertTrue(result.isFailure)
    }

    @Test
    fun clauseBudgetIsEnforced() {
        val goal = (1..IntentGrammar.MAX_INTENTS + 1).joinToString("; ") { "tap \"Item $it\"" }
        val result = IntentGrammar.parse(goal)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("limit"))
    }
}
