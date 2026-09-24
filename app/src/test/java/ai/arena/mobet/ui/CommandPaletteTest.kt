package ai.arena.mobet.ui

import ai.arena.mobet.ui.CommandPalette.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ranking rules for the action search. */
class CommandPaletteTest {

    private val commands = listOf(
        Command("Generate grounded plan", "Describe a goal in plain clauses", "synthesize goal create"),
        Command("Run goal autonomously", "Bounded agent run", "agent apex"),
        Command("Audit ledger", "Tamper-evident record of what ran", "log history"),
        Command("Agent memory", "What the agent learned", "experience forget"),
        Command("Dry run", "Simulate without touching the device", "preview test")
    )

    private fun titles(query: String) = CommandPalette.filter(commands, query).map { it.title }

    @Test
    fun anEmptyQueryPreservesTheOnScreenOrder() {
        assertEquals(commands.map { it.title }, titles(""))
        assertEquals(commands.map { it.title }, titles("   "))
    }

    @Test
    fun titlePrefixesOutrankEverythingElse() {
        // "Agent memory" starts with the query; "Run goal autonomously" only mentions it.
        assertEquals("Agent memory", titles("agent").first())
    }

    @Test
    fun aSubstringOfTheTitleStillMatches() {
        assertTrue(titles("ledger").contains("Audit ledger"))
    }

    @Test
    fun keywordsFindActionsTheLabelDoesNotName() {
        // Nothing is titled "log", but the audit ledger is what the user wants.
        assertEquals(listOf("Audit ledger"), titles("log"))
        assertEquals(listOf("Generate grounded plan"), titles("synthesize"))
    }

    @Test
    fun subtitlesAreTheLastResort() {
        assertEquals(listOf("Dry run"), titles("touching"))
    }

    @Test
    fun searchIsCaseAndWhitespaceInsensitive() {
        assertEquals(titles("audit"), titles("  AUDIT  "))
    }

    @Test
    fun anUnmatchedQueryReturnsNothingRatherThanEverything() {
        assertTrue(titles("qwertyuiop").isEmpty())
    }

    @Test
    fun rankingIsStableForEquallyRankedEntries() {
        // Both match by keyword only; the on-screen order decides.
        val entries = listOf(
            Command("First", "", "shared"),
            Command("Second", "", "shared")
        )
        assertEquals(listOf("First", "Second"), CommandPalette.filter(entries, "shared").map { it.title })
    }
}
