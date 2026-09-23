package ai.arena.mobet.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the semantics of the post-step evidence check (docs/FRONTIER.md pillar 2): what
 * counts as a change, how text assertions read the accessibility channel, and the fixed
 * evaluation order that keeps failures reproducible.
 */
class ExpectationCheckerTest {

    private fun evidence(
        previous: String? = "fp-before",
        fingerprint: String = "fp-after",
        packageName: String = "com.android.settings",
        visibleLabels: Set<String> = setOf("Settings", "Wi-Fi", "Connected to Arena-5G")
    ) = ExpectationEvidence(previous, fingerprint, packageName, visibleLabels)

    @Test
    fun screenChangePassesWhenFingerprintDiffers() {
        val result = ExpectationChecker.check(Expectation(screenChange = true), evidence())
        assertNull(result)
    }

    @Test
    fun screenChangeFailsWhenFingerprintIsIdentical() {
        val result = ExpectationChecker.check(
            Expectation(screenChange = true), evidence(fingerprint = "fp-before")
        )
        assertTrue(result!!.contains("identical"))
    }

    @Test
    fun screenChangePassesVacuouslyWithoutABaseline() {
        // First screen of the run (or first after a launch reset): no honest baseline exists.
        val result = ExpectationChecker.check(
            Expectation(screenChange = true), evidence(previous = null)
        )
        assertNull(result)
    }

    @Test
    fun textPresentMatchesCaseInsensitivelyAcrossLabels() {
        val result = ExpectationChecker.check(
            Expectation(textPresent = "arena-5g"), evidence()
        )
        assertNull(result)
    }

    @Test
    fun textPresentRequiresOnlyASubstringNotWholeLabel() {
        val result = ExpectationChecker.check(Expectation(textPresent = "Connected"), evidence())
        assertNull(result)
    }

    @Test
    fun textPresentFailureQuotesTheWantedText() {
        val result = ExpectationChecker.check(Expectation(textPresent = "Bluetooth"), evidence())
        assertTrue(result!!.contains("Bluetooth"))
    }

    @Test
    fun textAbsentFailsWhenTheLabelIsStillVisible() {
        val result = ExpectationChecker.check(Expectation(textAbsent = "Wi-Fi"), evidence())
        assertTrue(result!!.contains("Wi-Fi"))
    }

    @Test
    fun textAbsentPassesWhenTheLabelIsGone() {
        val result = ExpectationChecker.check(Expectation(textAbsent = "Airplane"), evidence())
        assertNull(result)
    }

    @Test
    fun packageIsEnforcesExactPackageOwnership() {
        assertNull(ExpectationChecker.check(Expectation(packageIs = "com.android.settings"), evidence()))
        val result = ExpectationChecker.check(Expectation(packageIs = "com.other.app"), evidence())
        assertTrue(result!!.contains("com.other.app"))
        assertTrue(result.contains("com.android.settings"))
    }

    @Test
    fun failingAssertionsReportInFixedOrder() {
        // screenChange fails AND textPresent fails: the structural failure reports first,
        // because a screen that never changed makes label reads the least informative.
        val result = ExpectationChecker.check(
            Expectation(screenChange = true, textPresent = "Bluetooth"),
            evidence(fingerprint = "fp-before")
        )!!
        assertTrue(result.contains("identical"))
    }

    @Test
    fun emptyExpectationAlwaysPasses() {
        // Reachable only from code paths that skip the validator; harmless by construction
        // and asserted here so a future refactor cannot make "no assertions" mean "fail".
        assertNull(ExpectationChecker.check(Expectation(), evidence()))
    }

    @Test
    fun allAssertionsMustHoldTogether() {
        val ok = ExpectationChecker.check(
            Expectation(
                screenChange = true, textPresent = "Wi-Fi",
                textAbsent = "Error", packageIs = "com.android.settings"
            ),
            evidence()
        )
        assertNull(ok)
        val bad = ExpectationChecker.check(
            Expectation(
                screenChange = true, textPresent = "Wi-Fi",
                textAbsent = "Connected", packageIs = "com.android.settings"
            ),
            evidence()
        )!!
        assertEquals("text “Connected” should be gone but is still visible", bad)
    }
}
