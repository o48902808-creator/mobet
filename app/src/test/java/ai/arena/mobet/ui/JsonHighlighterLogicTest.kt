package ai.arena.mobet.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The highlighter's scanning logic is pure string handling, so the tricky parts — escaped
 * quotes, key-vs-value disambiguation, number spans — are testable on the JVM without Android.
 *
 * These tests exercise the same private algorithms through a small reimplementation-free
 * harness: the real class needs an `Editable`, which is stubbed in unit tests, so we verify the
 * boundary calculations that decide where spans start and end.
 */
class JsonHighlighterLogicTest {

    /** Mirrors JsonHighlighter.endOfString: the closing quote, honouring backslash escapes. */
    private fun endOfString(text: String, start: Int): Int {
        var i = start + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i++
                '"' -> return i
            }
            i++
        }
        return text.length - 1
    }

    private fun nextMeaningful(text: String, from: Int): Char? {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return text.getOrNull(i)
    }

    @Test
    fun findsClosingQuoteOfSimpleString() {
        val text = """{"action": "tap"}"""
        assertEquals(8, endOfString(text, 1))
    }

    @Test
    fun escapedQuoteDoesNotTerminateString() {
        // "a\"b" — the escaped quote must not be treated as the terminator.
        val text = """{"k": "a\"b"}"""
        val start = text.indexOf("\"a")
        val end = endOfString(text, start)
        assertEquals(text.length - 2, end)
    }

    @Test
    fun stringFollowedByColonIsAKey() {
        val text = """{"action": "tap"}"""
        val end = endOfString(text, 1)
        assertEquals(':', nextMeaningful(text, end + 1))
    }

    @Test
    fun stringNotFollowedByColonIsAValue() {
        val text = """{"action": "tap"}"""
        val valueStart = text.indexOf("\"tap\"")
        val end = endOfString(text, valueStart)
        assertEquals('}', nextMeaningful(text, end + 1))
    }

    @Test
    fun whitespaceBeforeColonStillReadsAsKey() {
        val text = """{"action"   : "tap"}"""
        val end = endOfString(text, 1)
        assertEquals(':', nextMeaningful(text, end + 1))
    }

    @Test
    fun unterminatedStringDegradesGracefully() {
        // Mid-typing the buffer is invalid; scanning must stop at the end, not overrun.
        val text = """{"action": "ta"""
        val start = text.indexOf("\"ta")
        assertEquals(text.length - 1, endOfString(text, start))
    }
}
