package ai.arena.mobet.ui

import android.text.Editable
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import androidx.annotation.ColorInt

/**
 * Lightweight JSON colouriser for the workflow editor.
 *
 * Workflow JSON is the primary authoring surface, and an undifferentiated wall of monospaced
 * text makes it easy to misread a key as a value or miss an unquoted token. This applies
 * colour spans in place on the existing [Editable] so the user's cursor, selection and undo
 * history are preserved — re-setting the text would destroy all three on every keystroke.
 *
 * It is deliberately a tokeniser rather than a parser: the buffer is invalid JSON for most of
 * the time the user is typing, so highlighting must degrade gracefully instead of throwing.
 */
class JsonHighlighter(
    @ColorInt private val keyColor: Int,
    @ColorInt private val stringColor: Int,
    @ColorInt private val numberColor: Int,
    @ColorInt private val literalColor: Int,
    @ColorInt private val punctuationColor: Int
) {

    /** Applies colour to [editable], replacing any spans from a previous pass. */
    fun apply(editable: Editable) {
        val text = editable.toString()
        if (text.length > MAX_LENGTH) {
            clear(editable)
            return
        }
        clear(editable)

        var i = 0
        while (i < text.length) {
            when (val c = text[i]) {
                '"' -> {
                    val end = endOfString(text, i)
                    // A string followed by ':' is a key; anything else is a value.
                    val isKey = nextMeaningful(text, end + 1) == ':'
                    span(editable, i, minOf(end + 1, text.length), if (isKey) keyColor else stringColor)
                    i = end + 1
                }
                in '0'..'9', '-' -> {
                    val end = endOfNumber(text, i)
                    span(editable, i, end, numberColor)
                    i = end
                }
                't', 'f', 'n' -> {
                    val literal = LITERALS.firstOrNull { text.startsWith(it, i) }
                    if (literal != null) {
                        span(editable, i, i + literal.length, literalColor)
                        i += literal.length
                    } else i++
                }
                '{', '}', '[', ']', ',', ':' -> {
                    span(editable, i, i + 1, punctuationColor)
                    i++
                }
                else -> i++
            }
            if (c == '\u0000') break
        }
    }

    private fun clear(editable: Editable) {
        editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
            .forEach(editable::removeSpan)
    }

    private fun span(editable: Editable, start: Int, end: Int, @ColorInt color: Int) {
        if (start >= end || end > editable.length) return
        editable.setSpan(
            ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    /** Index of the closing quote, honouring backslash escapes. */
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

    private fun endOfNumber(text: String, start: Int): Int {
        var i = start
        if (i < text.length && text[i] == '-') i++
        while (i < text.length && (text[i].isDigit() || text[i] == '.' || text[i] == 'e' ||
                text[i] == 'E' || text[i] == '+' || text[i] == '-')
        ) i++
        return i
    }

    private fun nextMeaningful(text: String, from: Int): Char? {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return text.getOrNull(i)
    }

    private companion object {
        val LITERALS = listOf("true", "false", "null")

        /** Above this size the cost per keystroke outweighs the readability gain. */
        const val MAX_LENGTH = 20_000
    }
}
