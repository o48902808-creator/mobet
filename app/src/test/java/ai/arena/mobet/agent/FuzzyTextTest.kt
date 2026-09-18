package ai.arena.mobet.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyTextTest {
    @Test
    fun identicalStringsScoreOne() {
        assertEquals(1.0, FuzzyText.similarity("Network & internet", "network & internet"), 1e-9)
    }

    @Test
    fun ampersandParaphraseScoresHigh() {
        assertTrue(FuzzyText.similarity("Network and internet", "Network & internet") > 0.8)
    }

    @Test
    fun typoStillMatches() {
        assertTrue(FuzzyText.similarity("Bluetoth", "Bluetooth") > 0.7)
    }

    @Test
    fun unrelatedLabelsScoreLow() {
        assertTrue(FuzzyText.similarity("Battery", "Display settings") < 0.4)
    }

    @Test
    fun containmentIsRewarded() {
        assertTrue(FuzzyText.similarity("Internet", "Network & internet") > 0.7)
    }

    @Test
    fun levenshteinBasics() {
        assertEquals(0, FuzzyText.levenshtein("abc", "abc"))
        assertEquals(1, FuzzyText.levenshtein("abc", "abd"))
        assertEquals(3, FuzzyText.levenshtein("", "abc"))
    }

    @Test
    fun emptyInputScoresZero() {
        assertEquals(0.0, FuzzyText.similarity("", "anything"), 1e-9)
        assertEquals(0.0, FuzzyText.similarity("!!!", "anything"), 1e-9)
    }
}
