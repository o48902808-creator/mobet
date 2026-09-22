package ai.arena.mobet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks the org.json offset → line/column conversion the workflow editor chips rely on.
 * The messages here mirror real org.json output shapes.
 */
class JsonErrorLocatorTest {

    private val source = "{\n  \"name\": \"demo\",\n  \"steps\": [}\n}"

    @Test
    fun `locates an error on a later line`() {
        // Line 2 occupies offsets 2..18 (17 chars), so line 3 starts at offset 20 and an
        // error at character 30 sits in its twelfth position.
        val location = JsonErrorLocator.locate(source, "Unterminated array at character 30 of $source")
        assertEquals(JsonErrorLocator.Location(line = 3, column = 11, offset = 30), location)
    }

    @Test
    fun `locates an error on the first line`() {
        // Deliberately newline-free: the multi-line fixture has its newline at offset 1, so
        // no positive offset on it sits on line 1.
        val single = "{\"steps\": [}"
        val location = JsonErrorLocator.locate(single, "Expected literal value at character 9 of $single")
        assertEquals(JsonErrorLocator.Location(line = 1, column = 10, offset = 9), location)
    }

    @Test
    fun `offset at document start is line 1 column 1`() {
        assertEquals(
            JsonErrorLocator.Location(1, 1, 0),
            JsonErrorLocator.locate(source, "A JSONObject text must begin with '{' at character 0 of $source")
        )
    }

    @Test
    fun `offset beyond the document is clamped to the end`() {
        // A truncated paste can make org.json report the very end as the failure point; the
        // caret must still land somewhere valid rather than past the text.
        val location = JsonErrorLocator.locate(source, "End of input at character 999 of {")
        assertEquals(JsonErrorLocator.Location(4, 2, source.length), location)
    }

    @Test
    fun `messages without an offset yield no location`() {
        assertNull(JsonErrorLocator.locate(source, "No value for steps"))
        assertNull(JsonErrorLocator.locate(source, null))
        assertNull(JsonErrorLocator.locate(source, ""))
    }

    @Test
    fun `describe formats the human label`() {
        assertEquals(
            "line 2, column 3",
            JsonErrorLocator.describe(source, "Duplicate key at character 4 of $source")
        )
        assertNull(JsonErrorLocator.describe(source, "No value for steps"))
    }
}
