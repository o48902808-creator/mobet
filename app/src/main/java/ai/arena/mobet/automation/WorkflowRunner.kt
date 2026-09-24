package ai.arena.mobet.automation

import ai.arena.mobet.agent.ScreenFingerprint
import ai.arena.mobet.agent.SelectorResolver
import ai.arena.mobet.agent.WorldModel
import ai.arena.mobet.policy.PlanValidator
import ai.arena.mobet.policy.RiskAssessment
import ai.arena.mobet.policy.RiskEngine
import ai.arena.mobet.synthesis.SelectorOutcomes
import ai.arena.mobet.policy.RiskTier
import ai.arena.mobet.security.SecretStore

/**
 * Sequential observe–act state machine with runtime safety rails:
 *
 *  - policy preflight through [PlanValidator] plus runtime package and time boundaries;
 *  - per-step [RiskEngine] scoring — CRITICAL steps escalate to a hardened typed confirmation;
 *  - a loop guard that fingerprints each observed screen and aborts suspected infinite loops;
 *  - opt-in self-healing: when a selector times out and policy allows it, the closest live
 *    element is substituted once, only for steps at or below LOW risk, and always logged;
 *  - passive world-model learning of screen transitions for future grounded planning.
 */
class WorkflowRunner(
    private val service: MobetAccessibilityService,
    private val emitLog: (String) -> Unit,
    private val onFinished: ((Boolean, String) -> Unit)? = null,
    private val launchTarget: Boolean = true,
    private val enforcePackageAtFirstStep: Boolean = false,
    private val emitTimeline: (ExecutionTimelineEvent) -> Unit = {},
    /**
     * Time and deferral. Defaults to the main looper; a test supplies a deterministic scheduler
     * so a whole plan can be executed without waiting for wall-clock delays.
     */
    private val scheduler: RunScheduler = HandlerScheduler()
) {
    private val secrets = SecretStore(service)
    private val worldModel = WorldModel(service)
    private val agentMemory = ai.arena.mobet.agent.PersistentExperienceStore(service)
    private var cancelled = false
    private var completionDelivered = false
    private var workflow: Workflow? = null
    private var index = 0
    private var awaitingConfirmation = false
    private var nextActionApproved = false
    /**
     * Per-gate generation for the confirmation timeout. Without it, the timer posted for one
     * `confirm` step measures silence across *later* gates too: workflows legitimately chain
     * confirmations (a confirm before every elevated step), so gate N could be sitting open,
     * answered slowly, while gate N-1's two-minute deadline fires and denies a prompt the user
     * is actively reading. Each gate gets its own deadline.
     */
    private var confirmationGeneration = 0
    private var startedAt = 0L
    private var lastFingerprint: String? = null
    /**
     * The previously dispatched step awaiting its post-state evidence check, paired with the
     * step number it was dispatched as. Set at dispatch, consumed exactly once by the first
     * observation in the next [executeCurrent] tick — skipped steps and halts never leave a
     * stale check armed because [finish]/[cancel] set `cancelled`, which mutes consumption.
     */
    private var pendingExpectation: Pair<Int, Step>? = null
    /** Jump table and dynamic rails for control-flow actions; built once per run in [start]. */
    private var controlFlow: ControlFlow? = null
    private val screenVisits = mutableMapOf<String, Int>()
    private val healedSteps = mutableSetOf<Int>()

    /**
     * Plaintext secret values resolved during this run, held only to keep them *out* of the log.
     *
     * A step may legitimately carry a secret in a selector or a fill value, and failure messages
     * quote the selector back to the user ("Timed out finding text ..."). Without this, a
     * resolved secret would reach the diagnostics log, the audit ledger and a broadcast Intent
     * in plaintext. Cleared when the run ends.
     */
    private val resolvedSecrets = mutableSetOf<String>()

    /**
     * Single chokepoint for run output. Every log line, including failure and cancellation
     * messages, is redacted here rather than at each call site, so a future message cannot
     * reintroduce the leak by forgetting to redact.
     */
    private fun log(message: String) = emitLog(redact(message))

    private fun timeline(
        state: ExecutionTimelineEvent.State,
        step: Step? = null,
        risk: RiskAssessment? = null,
        recovery: String? = null,
        stopReason: String? = null
    ) {
        val flow = workflow ?: return
        emitTimeline(
            ExecutionTimelineEvent(
                state = state,
                mode = "Workflow",
                goal = flow.name,
                subgoal = step?.label,
                step = if (step == null) null else index + 1,
                totalSteps = flow.steps.size,
                screenFingerprint = lastFingerprint,
                action = step?.action,
                evidence = step?.timelineEvidence(),
                risk = risk?.let { "${it.tier.name.lowercase()} · score ${it.score}" },
                policy = if (risk == null) "PlanValidator approved" else "Allowed by deterministic policy",
                recovery = recovery,
                stopReason = stopReason
            )
        )
    }

    private fun redact(message: String): String {
        if (resolvedSecrets.isEmpty()) return message
        var output = message
        // Longest first, so a secret that contains another as a substring still fully redacts.
        resolvedSecrets.sortedByDescending(String::length).forEach { secret ->
            if (secret.isNotEmpty()) output = output.replace(secret, SECRET_MASK)
        }
        return output
    }

    fun start(value: Workflow) {
        workflow = value
        val violations = PlanValidator.validate(value)
        if (violations.isNotEmpty()) {
            finish("Policy rejected plan: " + violations.joinToString("; ") {
                (it.step?.let { step -> "step $step: " } ?: "") + it.message
            })
            return
        }
        controlFlow = ControlFlow(value.steps)
        startedAt = scheduler.now()
        timeline(ExecutionTimelineEvent.State.PLANNING)
        val elevated = value.steps.count { riskOf(it, value.variables).tier >= RiskTier.ELEVATED }
        log(
            "Policy approved “${value.name}” (${value.steps.size}/${value.policy.maxActions} actions, " +
                "$elevated elevated-risk, self-healing ${if (value.policy.allowSelfHealing) "on" else "off"})"
        )
        if (launchTarget && value.packageName != null && !service.launch(value.packageName)) {
            finish("Could not launch ${value.packageName}")
            return
        }
        scheduler.post(700) { executeCurrent() }
    }

    fun cancel(reason: String) {
        if (cancelled) return
        cancelled = true
        awaitingConfirmation = false
        scheduler.cancelAll()
        val safe = redact(reason)
        resolvedSecrets.clear()
        emitLog(safe)
        timeline(
            ExecutionTimelineEvent.State.HALTED,
            workflow?.steps?.getOrNull(index),
            stopReason = safe
        )
        if (!completionDelivered) {
            completionDelivered = true
            onFinished?.invoke(false, safe)
        }
    }

    fun isRunning(): Boolean = !cancelled

    fun confirmationResult(approved: Boolean) {
        if (!awaitingConfirmation || cancelled) return
        awaitingConfirmation = false
        // Invalidate this gate's timeout so only the *next* gate's timer is live.
        confirmationGeneration++
        if (approved) {
            log("Confirmation approved")
            nextActionApproved = true
            advance(700)
        } else finish("Confirmation denied")
    }

    private fun executeCurrent() {
        if (cancelled) return
        val flow = workflow ?: return
        if (scheduler.now() - startedAt > flow.policy.maxRuntimeMs) {
            finish("Runtime budget exceeded (${flow.policy.maxRuntimeMs} ms)")
            return
        }
        service.unsafeSurfaceReason(flow.policy.allowedPackages)?.let { reason ->
            finish("Surface boundary blocked action: $reason")
            return
        }
        val activePackage = service.activePackageName()
        if ((index > 0 || enforcePackageAtFirstStep) && activePackage != null && activePackage !in flow.policy.allowedPackages) {
            finish("Package boundary blocked action in $activePackage")
            return
        }
        activePackage?.let { packageName ->
            flow.policy.packageVersions[packageName]?.let { required ->
                val actual = service.appVersion(packageName)
                if (actual != required) {
                    finish("App version boundary blocked $packageName: required $required, found ${actual ?: "unknown"}")
                    return
                }
            }
        }
        observeScreen(flow)
        if (cancelled) return
        if (index >= flow.steps.size) {
            finish("Completed ${flow.steps.size} steps")
            return
        }
        val raw = flow.steps[index]
        val step = expand(raw, flow.variables) ?: return
        if (step.ifText != null && !exists(Selector(text = step.ifText))) {
            log("Step ${index + 1}: skipped (ifText not present)")
            advance(0)
            return
        }
        if (step.unlessText != null && exists(Selector(text = step.unlessText))) {
            log("Step ${index + 1}: skipped (unlessText present)")
            advance(0)
            return
        }
        val risk = RiskEngine.assess(step)
        val riskNote = if (risk.tier >= RiskTier.ELEVATED)
            " · risk ${risk.tier.name.lowercase()} (${risk.reasons.joinToString(", ")})" else ""
        log("Step ${index + 1}/${flow.steps.size}: ${step.action}$riskNote")
        timeline(ExecutionTimelineEvent.State.RUNNING, step, risk)
        // Arm the post-step evidence check against the *expanded* step, so `{{var:…}}` text in
        // the expect block reads back with the same substitutions the action itself used.
        pendingExpectation = step.expect?.let { index to step }
        // Dynamic execution rails: loops multiply the counts PlanValidator checked statically,
        // so decide-only hops and device-affecting actions each consume their own budget.
        if (step.action in ControlFlow.CONTROL_ACTIONS) {
            if (controlFlow?.consumeControlHop() != true) {
                finish("Control-flow hop budget exhausted (${ControlFlow.MAX_CONTROL_HOPS}) — probable infinite loop")
                return
            }
        } else if (controlFlow?.consumeAction(flow.policy.maxActions) != true) {
            finish("Action budget exceeded (${flow.policy.maxActions})")
            return
        }
        val approved = nextActionApproved
        if (step.action != "confirm") nextActionApproved = false
        if (step.action !in ControlFlow.CONTROL_ACTIONS && step.action !in setOf("wait", "delay", "confirm", "ocrwait")) {
            service.noteAutomatedAction()
        }
        when (step.action) {
            // Cross-app switching. The destination was validated against policy.allowedPackages
            // by PlanValidator; it is re-checked here so a mutated plan cannot widen the boundary
            // at execution time. The post-launch settle delay lets the new app's window attach
            // before the next step observes the screen.
            "launch" -> {
                val target = step.packageName
                if (target.isNullOrBlank()) finish("launch requires a package")
                else if (target !in flow.policy.allowedPackages)
                    finish("launch target $target is not in policy.allowedPackages")
                else if (!service.launch(target)) finish("Could not launch $target")
                else {
                    log("Launched $target")
                    // Treat the switch as a fresh screen so the loop guard does not attribute
                    // the previous app's fingerprints to the new one.
                    lastFingerprint = null
                    screenVisits.clear()
                    advance(maxOf(step.delayMs, LAUNCH_SETTLE_MS))
                }
            }
            "back" -> complete(service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK), step)
            "home" -> complete(service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME), step)
            "delay" -> advance(step.delayMs)
            "branch" -> {
                val satisfied = evaluateControlEvidence(step)
                val targetName = if (satisfied) step.goto else step.elseGoto
                if (targetName == null) {
                    advance(step.delayMs)
                } else {
                    val target = controlFlow?.jumpTarget(targetName)
                    if (target == null) finish("branch: unknown label “$targetName”")
                    else {
                        log("Step ${index + 1}: branch ${if (satisfied) "taken" else "fallback"} → $targetName")
                        index = target - 1
                        advance(step.delayMs)
                    }
                }
            }
            "repeatuntil" -> {
                if (evaluateControlEvidence(step)) {
                    log("Step ${index + 1}: repeatUntil condition met, continuing")
                    advance(step.delayMs)
                } else if (controlFlow?.consumeIteration(index, step.maxIterations) != true) {
                    finish(
                        "repeatUntil at step ${index + 1} exhausted its maxIterations " +
                            "(${step.maxIterations}) without the condition becoming true"
                    )
                } else {
                    val target = step.goto?.let { controlFlow?.jumpTarget(it) }
                    if (target == null) finish("repeatUntil: unknown label “${step.goto}”")
                    else {
                        index = target - 1
                        advance(step.delayMs)
                    }
                }
            }
            "tryalternates" -> {
                // First-match selector fallback, probed synchronously on the settled screen:
                // authors place a wait before this step when settling is required.
                val root = rootNode()
                if (root == null) {
                    finish("tryAlternates: screen unavailable")
                } else {
                    var chosen: UiNode? = null
                    var chosenIndex = -1
                    var deadEndsSkipped = 0
                    for (i in step.options.indices) {
                        // Dead-end routing: options recorded as dead on this screen (within
                        // their TTL) are skipped without probing — the memory half of
                        // self-healing applied to alternates. Every skip is logged, so a
                        // routing decision is auditable rather than silently narrowing the
                        // fallback list.
                        if (lastFingerprint != null &&
                            agentMemory.isDeadEnd(lastFingerprint!!, serialize(step.options[i]))
                        ) {
                            deadEndsSkipped++
                            log("tryAlternates: option ${i + 1} skipped (recorded dead end)")
                            continue
                        }
                        val candidate = find(root, step.options[i])
                        if (candidate != null) {
                            chosen = candidate
                            chosenIndex = i
                            break
                        }
                    }
                    if (chosen == null) {
                        root.release()
                        val tried = step.options.joinToString(", ") { serialize(it) }
                        val deadNote = if (deadEndsSkipped > 0) " ($deadEndsSkipped skipped as recorded dead ends)" else ""
                        finish("tryAlternates: none of ${step.options.size} options matched$deadNote ($tried)")
                    } else {
                        log("tryAlternates: option ${chosenIndex + 1}/${step.options.size} matched")
                        val ok = try {
                            click(chosen)
                        } finally {
                            chosen.release()
                            root.release()
                        }
                        if (ok) advance(step.delayMs) else finish("tryAlternates: tap failed")
                    }
                }
            }
            "confirm" -> {
                awaitingConfirmation = true
                // Look ahead: a CRITICAL next step upgrades this gate to a typed confirmation.
                val nextRisk = flow.steps.getOrNull(index + 1)?.let { riskOf(it, flow.variables) }
                val hardened = nextRisk != null && nextRisk.tier == RiskTier.CRITICAL
                if (hardened) log("Critical next step — typed confirmation required")
                // Fail closed if no answer ever arrives. The prompt lives in MainActivity, so a
                // rotation or process death can destroy the (non-cancelable) dialog without an
                // answer; awaitingConfirmation would otherwise pin this run in "Waiting for
                // confirmation" forever, with the runtime budget powerless because it is only
                // checked between steps. An unanswered gate expires into a denial.
                timeline(
                    ExecutionTimelineEvent.State.WAITING,
                    step,
                    RiskEngine.assess(step),
                    recovery = "Waiting for ${if (hardened) "typed " else ""}user confirmation"
                )
                val generation = ++confirmationGeneration
                scheduler.post(CONFIRM_TIMEOUT_MS) {
                    if (awaitingConfirmation && confirmationGeneration == generation && !cancelled) {
                        finish("Confirmation timed out — action denied")
                    }
                }
                service.requestConfirmation(step.message ?: "Allow the next workflow action?", hardened)
            }
            "tappoint", "swipe" -> {
                if (!approved) finish("${step.action} requires an immediately preceding confirmation")
                else {
                    val x = step.xPercent
                    val y = step.yPercent
                    if (x == null || y == null) finish("${step.action} requires xPercent and yPercent")
                    else if (step.action == "swipe" && (step.endXPercent == null || step.endYPercent == null))
                        finish("swipe requires endXPercent and endYPercent")
                    else service.performPointGesture(
                        x, y,
                        if (step.action == "swipe") step.endXPercent else null,
                        if (step.action == "swipe") step.endYPercent else null,
                        step.durationMs
                    ) { ok -> if (ok) advance(step.delayMs) else finish("Gesture was cancelled") }
                }
            }
            "capture" -> {
                if (!approved) finish("capture requires an immediately preceding confirmation")
                else service.captureScreen { ok, result ->
                    if (ok) { log("Screenshot saved privately: $result"); advance(step.delayMs) }
                    else finish(result)
                }
            }
            "ocrwait", "visualtap" -> {
                val query = step.selector.text
                if (!approved) finish("${step.action} requires an immediately preceding confirmation")
                else if (query == null) finish("${step.action} requires text")
                else service.findVisualText(query) { found, detail, x, y ->
                    log(detail)
                    if (!found) finish(detail)
                    else if (step.action == "ocrwait") advance(step.delayMs)
                    else service.performPointGesture(x, y, null, null, 120) { tapped ->
                        if (tapped) advance(step.delayMs) else finish("Visual tap was cancelled")
                    }
                }
            }
            "wait" -> seek(step, requireAction = false)
            "tap" -> seek(step, requireAction = true) { click(it) }
            "fill" -> seek(step, requireAction = true) { node ->
                node.performFocus()
                node.setText(step.value.orEmpty())
            }
            "scroll" -> seek(step, requireAction = true) { node -> node.performScrollForward() }
            else -> finish("Unknown action: ${step.action}")
        }
    }

    /** Fingerprints the visible screen, feeds the world model, and trips the loop guard. */
    private fun observeScreen(flow: Workflow) {
        val root = service.root() ?: return
        val packageName = root.packageName?.toString() ?: return
        val snapshot = try {
            ScreenInspector.inspect(root, packageName)
        } finally {
            root.recycle()
        }
        val fingerprint = ScreenFingerprint.of(snapshot)
        val previous = lastFingerprint
        if (previous != null && previous != fingerprint) {
            val executed = flow.steps.getOrNull(index - 1)
            if (executed != null) {
                worldModel.record(packageName, previous, transitionLabel(executed), fingerprint)
            }
            screenVisits.clear()
        }
        lastFingerprint = fingerprint
        val visits = (screenVisits[fingerprint] ?: 0) + 1
        screenVisits[fingerprint] = visits
        // In the current linear runner each step observes the screen once, so a legitimate run
        // can never exceed steps + slack on one screen. The guard exists as a hard rail for
        // future bounded replanning, where revisiting the same screen indefinitely is possible.
        val limit = flow.steps.size + LOOP_GUARD_SLACK
        if (visits > limit) {
            finish("Loop guard: screen ${fingerprint.take(8)} observed $visits times without structural change")
        }
        verifyExpectation(previous, fingerprint, packageName, snapshot.visibleLabels)
    }

    /**
     * Evaluates a control-flow condition against a fresh observation of the current screen.
     * Unlike the post-step check, disagreement here is *information*, not failure: a `branch`
     * reads it as "take the fallback path" and a `repeatUntil` as "loop again".
     *
     * The baseline for `screenChange` is the runner's last recorded observation
     * ([lastFingerprint]) — control reads never move it, so decisions cannot perturb the
     * world model. `textPresent`/`textAbsent`/`package` read the live screen and are the
     * workhorses for control conditions. Returns false when no observation is possible
     * (screen temporarily unreadable) — a loop on missing evidence is safer than proceeding
     * on an assumption, and missing evidence still spends hops/budget, so it terminates.
     */
    private fun evaluateControlEvidence(step: Step): Boolean {
        val expectation = step.expect ?: return true
        val root = service.root() ?: return false
        val packageName = root.packageName?.toString().orEmpty()
        val snapshot = try {
            ScreenInspector.inspect(root, packageName)
        } finally {
            root.recycle()
        }
        return ExpectationChecker.check(
            expectation,
            ExpectationEvidence(
                lastFingerprint, ScreenFingerprint.of(snapshot), packageName, snapshot.visibleLabels
            )
        ) == null
    }

    /**
     * Consumes the armed post-step evidence check (docs/FRONTIER.md pillar 2). The previous
     * step's declared expectations are evaluated against what this observation actually sees;
     * a mismatch halts the run with the failing assertion named, so a silent no-op tap can
     * never hand an unverified screen to the next step. Unreadable screens skip the check
     * entirely (this method is only called with a captured snapshot), and an empty observation
     * window after `launch` reads as a vacuous pass inside the checker, not here.
     */
    private fun verifyExpectation(
        previous: String?,
        fingerprint: String,
        packageName: String,
        visibleLabels: Set<String>
    ) {
        val pending = pendingExpectation
        pendingExpectation = null
        if (pending == null || cancelled) return
        val (expectIndex, expectStep) = pending
        val expectation = expectStep.expect ?: return
        ExpectationChecker.check(
            expectation, ExpectationEvidence(previous, fingerprint, packageName, visibleLabels)
        )?.let { failure ->
            finish("Step ${expectIndex + 1} evidence check failed: $failure")
        }
    }

    private fun transitionLabel(step: Step): String = step.action + (
        step.selector.viewId?.let { ":$it" }
            ?: step.selector.text?.let { ":$it" }
            ?: step.selector.description?.let { ":$it" }
            ?: ""
        )

    /**
     * Risk of a step as it will actually execute, for the confirmation look-ahead.
     *
     * The look-ahead used to score the *raw* step, but execution scores the *expanded* one. A
     * step whose selector is `{{var:label}}` therefore looked like a bare tap (LOW) when the
     * preceding confirm decided whether to harden, even though `label` resolved to "Pay $500
     * now" (CRITICAL). The gate meant to protect the riskiest actions was weakest exactly when
     * the risky text arrived through a variable.
     *
     * This substitutes variables only, and never calls [expand], which aborts the run on a
     * missing name and would otherwise fire those side effects one step early. Secret
     * placeholders are deliberately left unresolved: reading a secret to score a step the user
     * has not yet approved is not worth the exposure, and a secret's *value* is not the signal
     * risk scoring looks for. Substitution failures leave the placeholder in place, which can
     * only under-resolve, never invent a lower score than the raw step would have produced.
     */
    private fun riskOf(step: Step, variables: Map<String, String>): RiskAssessment {
        fun substitute(source: String?): String? {
            if (source == null) return null
            var result: String = source
            Regex("\\{\\{var:([A-Za-z0-9_.-]+)}}").findAll(source).forEach { match ->
                variables[match.groupValues[1]]?.let { result = result.replace(match.value, it) }
            }
            return result
        }
        val previewed = step.copy(
            selector = Selector(
                substitute(step.selector.text),
                substitute(step.selector.viewId),
                substitute(step.selector.description)
            ),
            value = substitute(step.value),
            message = substitute(step.message)
        )
        // Take the worse of the two readings so a substitution can only ever raise the tier.
        val raw = RiskEngine.assess(step)
        val resolved = RiskEngine.assess(previewed)
        return if (resolved.score >= raw.score) resolved else raw
    }

    private fun expand(step: Step, variables: Map<String, String>): Step? {
        fun resolve(source: String?): String? {
            if (source == null) return null
            var result: String = source
            Regex("\\{\\{var:([A-Za-z0-9_.-]+)}}").findAll(source).forEach {
                val name = it.groupValues[1]
                val value = variables[name] ?: run {
                    finish("Missing variable: $name")
                    return null
                }
                result = result.replace(it.value, value)
            }
            Regex("\\{\\{secret:([A-Za-z0-9_.-]+)}}").findAll(result).forEach {
                val name = it.groupValues[1]
                val value = secrets.get(name) ?: run {
                    finish("Missing or unreadable secret: $name")
                    return null
                }
                if (value.isNotEmpty()) resolvedSecrets += value
                result = result.replace(it.value, value)
            }
            return result
        }
        val text = resolve(step.selector.text) ?: if (step.selector.text != null) return null else null
        val id = resolve(step.selector.viewId) ?: if (step.selector.viewId != null) return null else null
        val description = resolve(step.selector.description) ?: if (step.selector.description != null) return null else null
        val value = resolve(step.value) ?: if (step.value != null) return null else null
        val ifText = resolve(step.ifText) ?: if (step.ifText != null) return null else null
        val unlessText = resolve(step.unlessText) ?: if (step.unlessText != null) return null else null
        val message = resolve(step.message) ?: if (step.message != null) return null else null
        // Evidence assertions resolve through the same substitution (and the same secret
        // masking) as the step itself, so an expect on resolved text cannot leak into logs.
        val expect = step.expect?.let { expectation ->
            val present = resolve(expectation.textPresent)
                ?: if (expectation.textPresent != null) return null else null
            val absent = resolve(expectation.textAbsent)
                ?: if (expectation.textAbsent != null) return null else null
            expectation.copy(textPresent = present, textAbsent = absent)
        }
        return step.copy(
            selector = Selector(text, id, description), value = value,
            ifText = ifText, unlessText = unlessText, message = message, expect = expect
        )
    }

    private fun seek(step: Step, requireAction: Boolean, retry: Int = 0, action: (UiNode) -> Boolean = { true }) {
        val started = scheduler.now()
        fun attempt() {
            if (cancelled) return
            // The runtime budget is also checked between steps in executeCurrent, but this seek
            // loop can outlive it many times over on its own: one wait step with timeoutMs=60s
            // and retries=10 keeps re-entering for over ten minutes, even against a 5s budget.
            // Enforce the hard rail here as well so no step window can stretch a run past it.
            val flow = workflow ?: return
            if (scheduler.now() - startedAt > flow.policy.maxRuntimeMs) {
                finish("Runtime budget exceeded (${flow.policy.maxRuntimeMs} ms)")
                return
            }
            val node = find(rootNode(), step.selector)
            if (node != null) {
                // Execution feedback: this selector really resolved on this device, in this app
                // version. Generation consults the tally as a bounded ranking tie-break so future
                // plans prefer selectors with a track record (docs/WORKFLOW_GENERATION.md).
                noteSelectorOutcome(step, resolved = true)
                val ok = try { action(node) } finally { node.release() }
                if (ok || !requireAction) advance(step.delayMs)
                else retryOrFail(step, requireAction, action, retry, "Action failed")
            } else if (scheduler.now() - started >= step.timeoutMs) {
                noteSelectorOutcome(step, resolved = false)
                retryOrFail(step, requireAction, action, retry, "Timed out")
            } else scheduler.post(250) { attempt() }
        }
        attempt()
    }

    /**
     * Records whether a selector resolved, keyed by package and selector identity only.
     *
     * No screen text, entered value or secret is retained, and the tally is advisory: it can move
     * a future grounding candidate by at most ±0.05 score and can never admit a target that is
     * not on screen.
     */
    private fun noteSelectorOutcome(step: Step, resolved: Boolean) {
        val spec = SelectorOutcomes.specOf(step.selector) ?: return
        val target = workflow?.packageName
        if (resolved) SelectorOutcomes.recordSuccess(target, spec)
        else SelectorOutcomes.recordFailure(target, spec)
    }

    private fun retryOrFail(step: Step, requireAction: Boolean, action: (UiNode) -> Boolean, retry: Int, reason: String) {
        if (retry < step.retries) {
            log("$reason; retry ${retry + 1}/${step.retries}")
            timeline(
                ExecutionTimelineEvent.State.RECOVERING,
                step,
                RiskEngine.assess(step),
                recovery = "$reason · retry ${retry + 1}/${step.retries}"
            )
            scheduler.post(500) { seek(step, requireAction, retry + 1, action) }
            return
        }
        val healed = tryHeal(step, reason)
        if (healed != null) {
            scheduler.post(300) { seek(healed, requireAction, retry, action) }
            return
        }
        finish("$reason finding ${describe(step.selector)}")
    }

    /**
     * Attempts a one-shot, policy-gated selector heal. Refuses when self-healing is disabled,
     * the step is above LOW risk, this step was already healed once, or no live element clears
     * the resolver's confidence and margin thresholds.
     */
    private fun tryHeal(step: Step, reason: String): Step? {
        val flow = workflow ?: return null
        if (!flow.policy.allowSelfHealing) return null
        if (index in healedSteps) return null
        if (RiskEngine.assess(step).tier > RiskTier.LOW) {
            log("Self-healing skipped: step risk exceeds the healing threshold")
            return null
        }
        val root = service.root() ?: return null
        val packageName = root.packageName?.toString() ?: run { root.recycle(); return null }
        if (packageName !in flow.policy.allowedPackages) { root.recycle(); return null }
        val snapshot = try {
            ScreenInspector.inspect(root, packageName)
        } finally {
            root.recycle()
        }
        val healed = SelectorResolver.heal(step.selector, snapshot) ?: return null
        healedSteps += index
        agentMemory.recordRepair(packageName, serialize(step.selector), serialize(healed.selector), service.appVersion(packageName))
        val repairMethod = healed.selector.let {
            if (it.viewId != null) "viewId" else if (it.description != null) "description" else "text"
        }
        log("$reason; selector repaired via $repairMethod (${(healed.confidence * 100).toInt()}%)")
        timeline(
            ExecutionTimelineEvent.State.RECOVERING,
            step,
            RiskEngine.assess(step),
            recovery = "Selector repaired via $repairMethod · confidence ${(healed.confidence * 100).toInt()}%"
        )
        return step.copy(selector = healed.selector)
    }

    private fun exists(selector: Selector): Boolean {
        val node = find(rootNode(), selector) ?: return false
        node.release()
        return true
    }

    /** Breadth-first search for the first node satisfying [selector]; caller releases the result. */
    private fun find(root: UiNode?, selector: Selector): UiNode? {
        root ?: return null
        val queue = ArrayDeque<UiNode>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.matches(selector)) {
                queue.forEach { it.release() }
                return node
            }
            for (i in 0 until node.childCount) node.child(i)?.let { queue.add(it) }
            node.release()
        }
        return null
    }

    /**
     * Clicks the node, or the nearest clickable ancestor.
     *
     * Labels are frequently non-clickable children of the actual control, so a plan targeting the
     * visible text would otherwise fail on perfectly ordinary layouts.
     */
    private fun click(target: UiNode): Boolean {
        var node: UiNode? = target
        while (node != null) {
            if (node.isClickable && node.performClick()) {
                // The caller owns `target`; every ancestor obtained here is ours to release.
                if (node !== target) node.release()
                return true
            }
            val parent = node.parent()
            if (node !== target) node.release()
            node = parent
        }
        return false
    }

    /** The live screen root, as a backend-neutral node. */
    private fun rootNode(): UiNode? = service.root()?.let(::AccessibilityUiNode)

    private fun complete(ok: Boolean, step: Step) {
        if (ok) advance(step.delayMs) else finish("${step.action} failed")
    }

    private fun advance(delay: Long) {
        index++
        scheduler.post(delay) { executeCurrent() }
    }

    private fun finish(message: String) {
        // Persist what this run learned about selector reliability, once, at the end.
        SelectorOutcomes.flush()
        cancelled = true
        awaitingConfirmation = false
        scheduler.cancelAll()
        // Redact before clearing, otherwise the final message loses its protection.
        val safe = redact(message)
        resolvedSecrets.clear()
        emitLog(safe)
        val succeeded = safe.startsWith("Completed")
        timeline(
            if (succeeded) ExecutionTimelineEvent.State.SUCCEEDED else ExecutionTimelineEvent.State.HALTED,
            workflow?.steps?.getOrNull(index.coerceAtMost((workflow?.steps?.lastIndex ?: 0))),
            stopReason = if (succeeded) null else safe
        )
        if (!completionDelivered) {
            completionDelivered = true
            onFinished?.invoke(succeeded, safe)
        }
    }

    private fun serialize(selector: Selector) = listOfNotNull(
        selector.viewId?.let { "id:$it" }, selector.text?.let { "text:$it" },
        selector.description?.let { "description:$it" }
    ).joinToString("|")

    private fun describe(selector: Selector) = when {
        selector.viewId != null -> "id “${selector.viewId}”"
        selector.text != null -> "text “${selector.text}”"
        selector.description != null -> "description “${selector.description}”"
        else -> "a selector (none was supplied)"
    }

    private companion object {
        const val LOOP_GUARD_SLACK = 8

        /** Stand-in for a resolved secret value in any user-visible or persisted text. */
        const val SECRET_MASK = "[redacted secret]"


        /** Minimum settle time after switching apps, so the new window is attached. */
        const val LAUNCH_SETTLE_MS = 900L

        /**
         * How long a confirmation gate may sit unanswered before it resolves as a denial.
         * Deliberately generous — the user may be reading the exact wording of a consequential
         * step — but finite, so a lost dialog denies the run instead of freezing it.
         */
        const val CONFIRM_TIMEOUT_MS = 120_000L
    }
}
