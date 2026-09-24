package ai.arena.mobet.synthesis

import ai.arena.mobet.automation.ScreenSnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tunable, auditable knobs for generation. Every default is the conservative choice: nothing here
 * can widen authority (packages, actions, risk gates are synthesized from the plan itself), it
 * only changes how defensively the emitted steps are written.
 */
data class SynthesisOptions(
    /** Emit a `wait` for a target before acting on it, so a slow screen fails as a timeout. */
    val insertWaits: Boolean = true,
    /** Assert a screen change after navigational taps ("open X", "go to X"). */
    val assertNavigation: Boolean = true,
    /** Turn an ambiguous target into a bounded `tryAlternates` instead of rejecting the goal. */
    val allowAlternates: Boolean = true,
    /** Run the deterministic peephole optimizer over the lowered steps. */
    val optimize: Boolean = true,
    /** Hoist literal (non-credential) fill values into named workflow variables. */
    val parameterizeValues: Boolean = true,
    val defaultRetries: Int = 1,
    val defaultTimeoutMs: Long = 5_000,
    val maxSteps: Int = 80,
    /** Execution history consulted as a bounded ranking tie-break; [NoGroundingPriors] ignores it. */
    val priors: GroundingPriors = SelectorOutcomes,
    /**
     * Screens observed earlier in the session, so a multi-screen route can be planned from the
     * first screen. [ScreenMemory.EMPTY] restricts grounding to the current screen only.
     */
    val screenMemory: ScreenMemory = SessionScreenMemory,
    /**
     * Text the user has consented to recognize on screen with on-device OCR.
     *
     * OCR is used strictly as a *diagnostic* here, never as a target source: recognized pixels
     * carry no selector, so a step built from them could only be replayed through visual
     * fallbacks. Instead, when grounding fails, the engine can tell the user the difference
     * between "that control does not exist" and "that control is drawn but exposes no
     * accessibility node", which are two very different problems with different fixes.
     */
    val ocrText: List<String> = emptyList(),
    /** Optional explicit workflow name; otherwise derived from the goal text. */
    val name: String? = null
)

/**
 * Advanced offline workflow generation engine.
 *
 * Pipeline: **text → [Intent] IR → grounding against the live snapshot → lowering (with
 * robustness passes) → risk confirmations → least-privilege policy → parse → [
 * ai.arena.mobet.policy.PlanValidator]**.
 *
 * Properties that hold by construction:
 *  * *Grounded* — a step is emitted only for a target that exists on the captured screen.
 *    Ambiguity becomes an explicit `tryAlternates` (or an error), never a silent guess.
 *  * *Bounded* — control flow is lowered into the runner's existing `branch`/`repeatUntil`
 *    primitives with explicit join labels and hard iteration caps; it cannot express a loop the
 *    validator would not accept.
 *  * *Least-privilege* — packages and actions are derived from what was actually emitted.
 *  * *Explainable* — every decision (grounding score, inserted wait, alternate, confirmation)
 *    is reported before the plan can run.
 *
 * The engine is deterministic and offline: identical goal + snapshot yields identical JSON.
 */
object WorkflowSynthesizer {

    fun synthesize(
        goal: String,
        snapshot: ScreenSnapshot,
        options: SynthesisOptions = SynthesisOptions()
    ): Result<SynthesizedWorkflow> = IntentGrammar.parse(goal).mapCatching { intents ->
        synthesize(intents, snapshot, options, options.name ?: "Goal: ${goal.trim().take(60)}")
            .getOrThrow()
    }

    fun synthesize(
        intents: List<Intent>,
        snapshot: ScreenSnapshot,
        options: SynthesisOptions = SynthesisOptions(),
        name: String = options.name ?: "Generated workflow"
    ): Result<SynthesizedWorkflow> = runCatching {
        require(intents.isNotEmpty()) { "No clauses to synthesize" }
        require(snapshot.elements.isNotEmpty()) {
            "The captured screen exposes no actionable elements — open the target app first"
        }
        val lowering = Lowering(snapshot, options)
        intents.forEach(lowering::emit)
        WorkflowAssembler.assemble(
            name = name,
            targetPackage = snapshot.packageName,
            steps = lowering.steps,
            variables = emptyMap(),
            extraPackages = lowering.launchPackages,
            notes = lowering.notes,
            maxSteps = options.maxSteps,
            optimize = options.optimize,
            parameterizeValues = options.parameterizeValues,
            snapshot = snapshot
        ).getOrThrow()
    }

    /** Mutable lowering state for one synthesis run; never shared between runs. */
    private class Lowering(
        private val snapshot: ScreenSnapshot,
        private val options: SynthesisOptions
    ) {
        val steps = mutableListOf<JSONObject>()
        val notes = mutableListOf<SynthesisNote>()
        val launchPackages = mutableSetOf<String>()
        /** Selectors that came from a remembered screen rather than the live one. */
        private val rememberedFor = mutableMapOf<String, RememberedScreen>()
        /** Screen the emitted route is currently standing on; null means the live screen. */
        private var routeScreenId: String? = null
        private var labelSeq = 0

        fun emit(intent: Intent) {
            when (intent) {
                is ConditionalIntent -> emitConditional(intent)
                is RepeatIntent -> emitRepeat(intent)
                is VerifyIntent -> attachVerification(intent)
                else -> emitSimple(intent)
            }
        }

        // ── Simple intents ───────────────────────────────────────────────────

        private fun emitSimple(intent: Intent) {
            when (intent) {
                is TapIntent -> emitTap(intent)
                is FillIntent -> emitFill(intent)
                is ScrollIntent -> emitScroll(intent)
                is WaitIntent -> {
                    val candidate = resolve(intent.target, intent.source, editableOnly = false)
                    steps += step("wait").withSelector(candidate.selector)
                        .put("timeoutMs", options.defaultTimeoutMs)
                }
                is DelayIntent -> steps += JSONObject().put("action", "delay")
                    .put("delayMs", intent.millis)
                is BackIntent -> steps += JSONObject().put("action", "back")
                is HomeIntent -> steps += JSONObject().put("action", "home")
                is ConfirmIntent -> steps += JSONObject().put("action", "confirm")
                    .put("message", intent.message)
                is LaunchIntent -> {
                    launchPackages += intent.packageName
                    notes += SynthesisNote("scope", "package ${intent.packageName} added to the allowlist by an explicit launch")
                    steps += JSONObject().put("action", "launch").put("package", intent.packageName)
                }
                else -> throw IllegalArgumentException("Unsupported clause: “${intent.source}”")
            }
        }

        private fun emitTap(intent: TapIntent) {
            when (val grounding = ground(intent.target, editableOnly = false)) {
                is Grounding.Resolved -> {
                    val candidate = grounding.best
                    note(intent.source, candidate)
                    // A memory-grounded target is not on screen *now*, so its wait is mandatory:
                    // it is the check that turns an optimistic route into a verified one.
                    if (options.insertWaits || fromMemory(candidate)) emitWaitFor(candidate.selector)
                    val tap = step("tap").withSelector(candidate.selector)
                        .put("timeoutMs", options.defaultTimeoutMs)
                        .put("retries", options.defaultRetries)
                    if (intent.navigational && options.assertNavigation) {
                        tap.put("expect", JSONObject().put("screenChange", true))
                        notes += SynthesisNote(
                            "verify",
                            "navigational tap “${candidate.label}” must change the screen"
                        )
                    }
                    steps += tap
                }
                is Grounding.Ambiguous -> {
                    require(options.allowAlternates) {
                        ambiguityMessage(intent.target, grounding.candidates)
                    }
                    val options0 = JSONArray()
                    grounding.candidates.forEach { candidate ->
                        options0.put(JSONObject().put(candidate.selector.key, candidate.selector.value))
                    }
                    notes += SynthesisNote(
                        "grounding",
                        "“${intent.target}” matched ${grounding.candidates.size} equally-good controls " +
                            "(${grounding.candidates.joinToString { it.label }}) — emitted a bounded tryAlternates"
                    )
                    steps += JSONObject().put("action", "tryalternates")
                        .put("options", options0)
                        .put("timeoutMs", options.defaultTimeoutMs)
                        .put("retries", options.defaultRetries)
                }
                is Grounding.NotFound -> throw IllegalArgumentException(
                    notFoundMessage(intent.target, intent.source, grounding.best)
                )
            }
        }

        private fun emitFill(intent: FillIntent) {
            val candidate = resolve(intent.target, intent.source, editableOnly = true)
            if (options.insertWaits || fromMemory(candidate)) emitWaitFor(candidate.selector)
            steps += step("fill").withSelector(candidate.selector)
                .put("value", intent.value)
                .put("timeoutMs", options.defaultTimeoutMs)
                .put("retries", options.defaultRetries)
            if (WorkflowSynthesizer.SECRET_REFERENCE.containsMatchIn(intent.value)) {
                notes += SynthesisNote("secrets", "value for “${candidate.label}” resolves from the secret store at run time")
            }
        }

        private fun emitScroll(intent: ScrollIntent) {
            val target = intent.target
            if (target == null) {
                steps += JSONObject().put("action", "scroll")
                return
            }
            val candidate = resolve(target, intent.source, editableOnly = false)
            steps += step("scroll").withSelector(candidate.selector)
                .put("timeoutMs", options.defaultTimeoutMs)
        }

        private fun emitWaitFor(selector: SelectorSpec) {
            val previous = steps.lastOrNull()
            val duplicate = previous != null && previous.optString("action") == "wait" &&
                previous.optString(selector.key) == selector.value
            if (duplicate) return
            steps += step("wait").withSelector(selector).put("timeoutMs", options.defaultTimeoutMs)
            notes += SynthesisNote("robustness", "wait inserted for $selector before acting on it")
        }

        // ── Verification ─────────────────────────────────────────────────────

        private fun attachVerification(intent: VerifyIntent) {
            val previous = steps.lastOrNull()
                ?: throw IllegalArgumentException("Nothing to verify before “${intent.source}”")
            val expect = previous.optJSONObject("expect") ?: JSONObject()
            if (intent.present) expect.put("textPresent", intent.text) else expect.put("textAbsent", intent.text)
            previous.put("expect", expect)
            notes += SynthesisNote(
                "verify",
                "“${intent.text}” must be ${if (intent.present) "present" else "absent"} after " +
                    previous.optString("action")
            )
        }

        // ── Control flow ─────────────────────────────────────────────────────

        private fun emitConditional(intent: ConditionalIntent) {
            val id = ++labelSeq
            val thenLabel = "then_$id"
            val joinLabel = "join_$id"
            val condition = JSONObject()
            if (intent.present) condition.put("textPresent", intent.whenText)
            else condition.put("textAbsent", intent.whenText)
            steps += JSONObject().put("action", "branch")
                .put("expect", condition)
                .put("goto", thenLabel)
                .put("elseGoto", joinLabel)

            val before = steps.size
            intent.body.forEach(::emit)
            require(steps.size > before) { "The conditional body produced no steps: “${intent.source}”" }
            steps[before].put("label", thenLabel)
            // Explicit join: a zero-length delay is the cheapest addressable no-op the runner has.
            steps += JSONObject().put("action", "delay").put("delayMs", 0).put("label", joinLabel)
            notes += SynthesisNote(
                "control-flow",
                "conditional on “${intent.whenText}” lowered to a branch with an explicit join"
            )
        }

        private fun emitRepeat(intent: RepeatIntent) {
            val id = ++labelSeq
            val bodyLabel = "loop_$id"
            val before = steps.size
            intent.body.forEach(::emit)
            require(steps.size > before) { "The repeat body produced no steps: “${intent.source}”" }
            steps[before].put("label", bodyLabel)
            val condition = JSONObject()
            if (intent.present) condition.put("textPresent", intent.untilText)
            else condition.put("textAbsent", intent.untilText)
            steps += JSONObject().put("action", "repeatuntil")
                .put("expect", condition)
                .put("goto", bodyLabel)
                .put("maxIterations", intent.maxIterations)
            notes += SynthesisNote(
                "control-flow",
                "loop until “${intent.untilText}” capped at ${intent.maxIterations} iterations"
            )
        }

        // ── Helpers ──────────────────────────────────────────────────────────

        private fun resolve(target: String, source: String, editableOnly: Boolean): GroundedCandidate {
            return when (val grounding = ground(target, editableOnly)) {
                is Grounding.Resolved -> grounding.best.also { note(source, it) }
                is Grounding.Ambiguous -> throw IllegalArgumentException(
                    ambiguityMessage(target, grounding.candidates)
                )
                is Grounding.NotFound -> throw IllegalArgumentException(
                    notFoundMessage(target, source, grounding.best)
                )
            }
        }

        /**
         * Grounds against the current screen, falling back to screens seen earlier in the session.
         *
         * Memory grounding is strictly more conservative than live grounding: only an unambiguous
         * resolution counts (no alternates), and the emitted step is always preceded by a `wait`,
         * so a route that no longer holds fails as a named timeout instead of tapping blind. The
         * fallback is recorded in [rememberedFor] and reported to the user.
         */
        private fun ground(target: String, editableOnly: Boolean): Grounding {
            val live = SnapshotGrounder.ground(
                target = target,
                elements = snapshot.elements,
                editableOnly = editableOnly,
                packageName = snapshot.packageName,
                priors = options.priors
            )
            if (live !is Grounding.NotFound) return live
            candidateScreens().forEach { screen ->
                val remembered = SnapshotGrounder.ground(
                    target = target,
                    elements = screen.elements,
                    editableOnly = editableOnly,
                    packageName = snapshot.packageName,
                    priors = options.priors
                )
                if (remembered is Grounding.Resolved) {
                    rememberedFor[remembered.best.selector.toString()] = screen
                    // Grounding here means the route is now standing on that screen, so the next
                    // clause is searched from it first.
                    routeScreenId = screen.screenId
                    return remembered
                }
            }
            return live
        }

        /**
         * Screens to search, in route order: those observed to follow wherever the plan currently
         * stands, then everything else by recency. Ordering matters when two screens of an app
         * both contain a control with the same label — the one actually reachable from here is the
         * one the user meant.
         */
        private fun candidateScreens(): List<RememberedScreen> {
            val here = routeScreenId ?: SessionScreenMemory.identify(snapshot.elements)
            val predicted = options.screenMemory.successors(snapshot.packageName, here)
            val rest = options.screenMemory.screens(snapshot.packageName)
                .filterNot { screen -> predicted.any { it.screenId == screen.screenId } }
            return (predicted + rest).filterNot { it.screenId == here }
        }

        private fun note(source: String, candidate: GroundedCandidate) {
            rememberedFor[candidate.selector.toString()]?.let { screen ->
                val ageMinutes = ((System.currentTimeMillis() - screen.observedAt) / 60_000).coerceAtLeast(0)
                notes += SynthesisNote(
                    "grounding",
                    "“${source.take(60)}” → ${candidate.selector} grounded from a screen seen " +
                        "${ageMinutes}m ago, not the current one — the preceding wait verifies it at run time"
                )
                return
            }
            notes += SynthesisNote(
                "grounding",
                "“${source.take(60)}” → ${candidate.selector} (${candidate.label}, " +
                    "match ${(candidate.similarity * 100).toInt()}%, selector confidence ${candidate.confidence}%" +
                    (if (candidate.prior != 0.0) ", learned prior ${"%+.2f".format(candidate.prior)}" else "") + ")"
            )
        }

        /** True when consented OCR text contains the target but the accessibility tree does not. */
        private fun visibleOnlyToOcr(target: String): Boolean {
            if (options.ocrText.isEmpty()) return false
            val needle = target.trim().lowercase()
            if (needle.isEmpty()) return false
            return options.ocrText.any { it.trim().lowercase().contains(needle) }
        }

        private fun fromMemory(candidate: GroundedCandidate): Boolean =
            rememberedFor.containsKey(candidate.selector.toString())

        private fun ambiguityMessage(target: String, candidates: List<GroundedCandidate>): String =
            "“$target” is ambiguous on this screen — ${candidates.joinToString { it.label }}. " +
                "Name the control exactly, or allow alternates."

        private fun notFoundMessage(target: String, source: String, best: GroundedCandidate?): String {
            val hint = best?.let {
                " Closest visible control: “${it.label}” (${(it.similarity * 100).toInt()}% match)."
            }.orEmpty()
            val remembered = options.screenMemory.screens(snapshot.packageName).size
            val memoryNote = if (remembered > 0) {
                " $remembered remembered screen(s) of this app were also searched."
            } else ""
            val ocrNote = if (visibleOnlyToOcr(target)) {
                " On-device OCR does see “$target” on this screen, so the control is drawn but " +
                    "exposes no accessibility node — it cannot be targeted reliably, and no plan " +
                    "step was invented for it."
            } else ""
            return "“$target” from clause “$source” is not on the captured screen.$hint$memoryNote$ocrNote"
        }

        private fun step(action: String): JSONObject = JSONObject().put("action", action)

        private fun JSONObject.withSelector(selector: SelectorSpec): JSONObject =
            put(selector.key, selector.value)
    }

    private val SECRET_REFERENCE = Regex("\\{\\{secret:[A-Za-z0-9_.-]+}}")
}
