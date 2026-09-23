package ai.arena.mobet.ui

/**
 * Turns a parse failure from org.json into an editor location.
 *
 * org.json's JSONException embeds an absolute character offset ("… at character 265 of …")
 * rather than a line/column, which is useless to someone editing JSON in a plain text field.
 * This converts the offset to the 1-based line/column a human navigates by, so the workflow
 * card can say where the document broke and a tap can drop the caret on it.
 *
 * Pure and JVM-tested; deliberately independent of Workflow.parse so it applies to any
 * org.json-produced message (imports, bundle validation, planner output).
 */
object JsonErrorLocator {

    /** org.json embeds the absolute, 0-based character offset of the failure. */
    private val OFFSET_PATTERN = Regex("""at character (-?\d+)""")

    data class Location(val line: Int, val column: Int, val offset: Int)

    /**
     * The failure position in [source], or null when the message carries no offset (org.json
     * does this for semantic errors like "No value for steps" — in that case there is no
     * position to point at and the UI should show the message head instead).
     */
    fun locate(source: String, errorMessage: String?): Location? {
        val offset = OFFSET_PATTERN.find(errorMessage.orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
            ?.coerceIn(0, source.length)
            ?: return null
        var line = 1
        var lineStart = 0
        for (index in 0 until offset) {
            if (source[index] == '\n') {
                line++
                lineStart = index + 1
            }
        }
        // Columns are 1-based for humans; the offset itself points at one past the last
        // character of the line when the error is at end-of-line, which is still correct.
        return Location(line, column = offset - lineStart + 1, offset = offset)
    }

    /** "line L, column C" for chip labels, or null when no position exists. */
    fun describe(source: String, errorMessage: String?): String? =
        locate(source, errorMessage)?.let { "line ${it.line}, column ${it.column}" }
}
