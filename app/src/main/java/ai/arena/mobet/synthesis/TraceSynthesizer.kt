package ai.arena.mobet.synthesis

import org.json.JSONArray
import org.json.JSONObject

/** Cleanup knobs for turning a recorded interaction trace into a runnable workflow. */
data class TraceOptions(
    /** Drop an immediately repeated identical tap — almost always a double-delivered event. */
    val dropRepeats: Boolean = true,
    /** Collapse a run of consecutive scrolls into at most [maxScrollRun] steps. */
    val coalesceScrolls: Boolean = true,
    val maxScrollRun: Int = 3,
    /** Precede each tap with a wait on the same selector so replay tolerates slower screens. */
    val insertWaits: Boolean = true,
    val defaultTimeoutMs: Long = 5_000,
    val defaultRetries: Int = 1,
    val maxSteps: Int = 80,
    val name: String = "Recorded workflow"
)

/**
 * Turns a raw [ai.arena.mobet.automation.InteractionRecorder] trace into a *workflow*, rather
 * than pasting the raw events into the editor.
 *
 * A literal trace replays at machine speed against a screen that has not finished loading, and
 * carries whatever event noise the platform delivered. This pass normalises selectors, removes
 * duplicate and redundant events, inserts explicit waits, and then sends the result through the
 * same risk/policy/validation back half as every other generated plan — so a recording can never
 * import a step the validator would have rejected.
 */
object TraceSynthesizer {

    private val supportedActions = setOf("tap", "scroll", "back", "home")

    fun synthesize(
        recorded: String,
        packageName: String,
        options: TraceOptions = TraceOptions()
    ): Result<SynthesizedWorkflow> = runCatching {
        val array = JSONArray(recorded)
        require(array.length() > 0) { "The recording is empty" }
        val notes = mutableListOf<SynthesisNote>()

        val normalized = mutableListOf<JSONObject>()
        var skippedRepeats = 0
        var skippedScrolls = 0
        var scrollRun = 0
        var lastAction: String? = null
        var lastTapSelector: String? = null
        for (index in 0 until array.length()) {
            val raw = array.optJSONObject(index)
                ?: throw IllegalArgumentException("Recorded entry ${index + 1} is not an object")
            val action = raw.optString("action").lowercase()
            require(action in supportedActions) {
                "Recorded entry ${index + 1} has an unsupported action “$action”"
            }
            val selector = SelectorSpec.KEYS
                .firstNotNullOfOrNull { key -> raw.optString(key).takeIf(String::isNotBlank)?.let { SelectorSpec(key, it) } }

            if (action == "scroll") {
                scrollRun += 1
                if (options.coalesceScrolls && scrollRun > options.maxScrollRun) {
                    skippedScrolls += 1
                    continue
                }
            } else {
                scrollRun = 0
            }

            val step = JSONObject().put("action", action)
            selector?.let { step.put(it.key, it.value) }
            if (action == "tap" || action == "scroll") {
                step.put("timeoutMs", options.defaultTimeoutMs)
                if (action == "tap") step.put("retries", options.defaultRetries)
            }

            if (options.dropRepeats && action == "tap" && selector != null &&
                lastAction == "tap" && lastTapSelector == selector.toString()
            ) {
                skippedRepeats += 1
                continue
            }
            lastAction = action
            if (action == "tap") lastTapSelector = selector?.toString()

            if (options.insertWaits && action == "tap" && selector != null) {
                normalized += JSONObject().put("action", "wait")
                    .put(selector.key, selector.value)
                    .put("timeoutMs", options.defaultTimeoutMs)
            }
            normalized += step
        }

        require(normalized.isNotEmpty()) { "The recording contained no usable steps" }
        notes += SynthesisNote(
            "trace",
            "${array.length()} recorded events → ${normalized.size} steps" +
                (if (skippedRepeats > 0) ", $skippedRepeats duplicate tap(s) dropped" else "") +
                (if (skippedScrolls > 0) ", $skippedScrolls redundant scroll(s) coalesced" else "") +
                (if (options.insertWaits) ", waits inserted before taps" else "")
        )
        notes += SynthesisNote(
            "trace",
            "typed text is never recorded; add fill steps with {{secret:…}} or {{var:…}} references by hand"
        )

        WorkflowAssembler.assemble(
            name = options.name,
            targetPackage = packageName,
            steps = normalized,
            notes = notes,
            maxSteps = options.maxSteps
        ).getOrThrow()
    }
}
