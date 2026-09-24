package ai.arena.mobet.synthesis

import ai.arena.mobet.automation.ScreenSnapshot
import org.json.JSONObject

/**
 * Re-grounds an existing workflow against the screen that is in front of the user *now*.
 *
 * Apps rename, re-order and re-id their controls, and a plan that worked last month fails at
 * run time with a timeout the user has to debug by hand. Repair does that debugging statically:
 * every selector is checked against the live snapshot, the ones that no longer exist are
 * re-grounded by fuzzy match, and the result goes back through the ordinary risk → policy →
 * validate pipeline.
 *
 * Boundaries that make this safe to offer:
 *  * it is an **authoring-time** operation with no device effects, not run-time self-healing
 *    (`policy.allowSelfHealing` stays off);
 *  * a selector is only rewritten when the new match is unambiguous and above the grounding
 *    threshold — ambiguity is reported, never resolved by picking one;
 *  * values, expectations, control flow and labels are preserved verbatim;
 *  * the snapshot must be of the plan's own target package, so repair can never retarget a
 *    workflow at a different app;
 *  * every change is listed in the report, and the user still has to insert and run it.
 */
object WorkflowRepair {

    data class RepairOptions(
        /** Also re-ground selectors that still exist, if a clearly better match appeared. */
        val aggressive: Boolean = false,
        val maxSteps: Int = 80
    )

    fun repair(
        source: String,
        snapshot: ScreenSnapshot,
        options: RepairOptions = RepairOptions()
    ): Result<SynthesizedWorkflow> = runCatching {
        val root = JSONObject(source)
        val target = root.optString("package").takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("The workflow has no target package to repair against")
        require(target == snapshot.packageName) {
            "The captured screen belongs to ${snapshot.packageName}, not the workflow's target $target"
        }
        require(snapshot.elements.isNotEmpty()) {
            "The captured screen exposes no actionable elements to re-ground against"
        }
        val array = root.optJSONArray("steps")
            ?: throw IllegalArgumentException("The workflow has no steps")
        require(array.length() > 0) { "The workflow has no steps" }

        val notes = mutableListOf<SynthesisNote>()
        val live = snapshot.elements.mapNotNull { SelectorSpec.parse(it.selector)?.toString() }.toSet()
        var repaired = 0
        var intact = 0

        val steps = (0 until array.length()).map { index ->
            val step = JSONObject(array.getJSONObject(index).toString())
            // Existing confirmations are preserved verbatim; the assembler only inserts a
            // gate where one is missing, so repair can never remove or duplicate a gate.
            if (step.optString("action").lowercase() == "confirm") return@map step
            val selector = SelectorSpec.KEYS
                .firstNotNullOfOrNull { key ->
                    step.optString(key).takeIf(String::isNotBlank)?.let { SelectorSpec.of(key, it) }
                } ?: return@map step

            val stillPresent = selector.toString() in live
            if (stillPresent && !options.aggressive) {
                intact += 1
                return@map step
            }

            when (val grounding = SnapshotGrounder.ground(selector.value, snapshot.elements)) {
                is Grounding.Resolved -> {
                    val replacement = grounding.best.selector
                    if (replacement.toString() == selector.toString()) {
                        intact += 1
                        return@map step
                    }
                    if (stillPresent && grounding.best.score <= 0.9) {
                        intact += 1
                        return@map step
                    }
                    SelectorSpec.KEYS.forEach { key -> step.remove(key) }
                    step.put(replacement.key, replacement.value)
                    repaired += 1
                    notes += SynthesisNote(
                        "repair",
                        "step ${index + 1}: $selector → $replacement (${grounding.best.label}, " +
                            "${(grounding.best.similarity * 100).toInt()}% match)"
                    )
                    step
                }
                is Grounding.Ambiguous -> throw IllegalArgumentException(
                    "Step ${index + 1}: “${selector.value}” now matches several controls " +
                        "(${grounding.candidates.joinToString { it.label }}) — pick one by hand"
                )
                is Grounding.NotFound -> throw IllegalArgumentException(
                    "Step ${index + 1}: “${selector.value}” is gone from this screen" +
                        (grounding.best?.let {
                            " (closest: “${it.label}”, ${(it.similarity * 100).toInt()}%)"
                        } ?: "") + " — edit or re-record that step"
                )
            }
        }

        notes += if (repaired == 0) {
            SynthesisNote("repair", "all $intact grounded selector(s) still resolve on this screen")
        } else {
            SynthesisNote("repair", "$repaired selector(s) re-grounded, $intact left untouched")
        }

        val variables = root.optJSONObject("variables")?.let { source0 ->
            buildMap<String, String> { source0.keys().forEach { key -> put(key, source0.getString(key)) } }
        } ?: emptyMap()
        val extraPackages = root.optJSONObject("policy")?.optJSONArray("allowedPackages")
            ?.let { allowed -> (0 until allowed.length()).map(allowed::getString).toSet() }
            ?: emptySet()

        WorkflowAssembler.assemble(
            name = root.optString("name").takeIf(String::isNotBlank) ?: "Repaired workflow",
            targetPackage = target,
            steps = steps,
            variables = variables,
            // Only packages the *original* plan was already allowed to touch are carried over;
            // repair never widens the allowlist.
            extraPackages = extraPackages.filter { it != target }.toSet(),
            notes = notes,
            maxSteps = options.maxSteps,
            snapshot = snapshot
        ).getOrThrow()
    }
}
