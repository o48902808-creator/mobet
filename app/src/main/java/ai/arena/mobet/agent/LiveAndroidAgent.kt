package ai.arena.mobet.agent

import ai.arena.mobet.automation.InspectedElement
import ai.arena.mobet.automation.MobetAccessibilityService
import ai.arena.mobet.automation.ScreenSnapshot
import ai.arena.mobet.automation.Selector
import ai.arena.mobet.automation.Step
import ai.arena.mobet.policy.RiskEngine
import android.os.Handler
import android.os.Looper

/** Converts a privacy-minimized accessibility snapshot into the platform-neutral agent contract. */
object AccessibilityObservationAdapter {
    fun adapt(snapshot: ScreenSnapshot, appVersion: String? = null): AgentObservation {
        val actions = snapshot.elements.mapNotNull(::toAction).take(80)
        val labels = (snapshot.visibleLabels + snapshot.elements.map { it.label }).take(120)
        val facts = labels.flatMap { listOf(it, "text:${normalize(it)}") }.toSet()
        val evidence = labels.map {
            ObservationEvidence("text:${normalize(it)}", EvidenceSource.ACCESSIBILITY,
                snapshot.elements.firstOrNull { element -> element.label == it }?.confidence?.div(100.0) ?: 0.9)
        }
        return AgentObservation(ai.arena.mobet.agent.ScreenFingerprint.of(snapshot), snapshot.packageName,
            actions, facts, evidence, appVersion, snapshot.capturedAt)
    }

    fun toStep(action: AgentAction): Step? {
        return when (action.kind) {
            AgentActionKind.BACK -> Step("back")
            AgentActionKind.SCROLL -> Step("scroll", parseSelector(action.selector) ?: return null, message = action.label)
            AgentActionKind.TAP -> Step("tap", parseSelector(action.selector) ?: return null, message = action.label)
        }
    }

    private fun toAction(element: InspectedElement): AgentAction? {
        val selector = element.selector.takeIf { ':' in it } ?: return null
        val kind = if (element.role.contains("scroll", true)) AgentActionKind.SCROLL else AgentActionKind.TAP
        val step = Step(if (kind == AgentActionKind.SCROLL) "scroll" else "tap",
            parseSelector(selector) ?: return null, message = element.label)
        val assessment = RiskEngine.assess(step)
        val stableId = "${kind.name.lowercase()}:${ScreenFingerprint.sha256(selector).take(16)}"
        return AgentAction(stableId, element.label, assessment.score,
            reversible = assessment.score < 30, kind = kind, selector = selector,
            confidence = element.confidence / 100.0,
            trust = ContentTrustEngine.assess(element.label).trust)
    }

    private fun parseSelector(value: String?): Selector? {
        value ?: return null
        val key = value.substringBefore(':').trim()
        val content = value.substringAfter(':').trim().takeIf(String::isNotBlank) ?: return null
        return when (key) {
            "viewId" -> Selector(viewId = content)
            "description" -> Selector(description = content)
            "text" -> Selector(text = content)
            else -> null
        }
    }
    private fun normalize(value: String) = value.trim().lowercase().replace(Regex("\\s+"), " ")
}

/**
 * Main-thread live controller. Reasoning can only request a typed [AgentAction]; each request is
 * independently translated, risk-scored, policy-validated, confirmed when needed, and executed by
 * WorkflowRunner. It has no direct AccessibilityNodeInfo authority.
 */
class LiveAndroidAgent(
    private val service: MobetAccessibilityService,
    private val memory: ExperienceStore,
    private val emit: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var deliberator = Deliberator(memory)
    private val stabilizer = ObservationStabilizer()
    private val checkpoints = RunCheckpointStore(service)
    private val resources = ResourceGovernor(service)
    private var goal: AgentGoal? = null
    private var plan: HierarchicalPlan? = null
    private var hierarchy: HierarchicalExecutor? = null
    private var cancelled = true
    private var cycles = 0
    private var startedAt = 0L
    private var runGeneration = 0L
    private var completionEvidenceHits = 0
    private var ocrCheckInFlight = false
    private val beliefTracker = TemporalBeliefTracker()
    private val frames = ArrayDeque<Pair<String, String>>()
    private val recoveryAttempts = mutableMapOf<FailureKind, Int>()

    fun start(value: AgentGoal) {
        cancel("Autonomous run replaced", quiet = true)
        require(value.maxCycles in 1..50) { "Cycle budget must be 1–50" }
        require(AgentPlanValidator.validate(value, emptyList()).isEmpty()) { "Invalid autonomous safety budget" }
        goal = value
        val assistant = if (value.allowModelAssistance) LocalStructuredModelAssistant() else null
        val hints = assistant?.proposeSubgoals(value)?.let(ModelOutputValidator::validateSubgoals).orEmpty()
        plan = HierarchicalPlanner.decompose(value, hints); hierarchy = plan?.let(::HierarchicalExecutor)
        deliberator = Deliberator(memory, assistant)
        cycles = 0; completionEvidenceHits = 0; ocrCheckInFlight = false; startedAt = android.os.SystemClock.uptimeMillis()
        runGeneration++; beliefTracker.clear(); stabilizer.reset(); frames.clear(); recoveryAttempts.clear(); cancelled = false
        checkpoints.start(value)
        service.showAutonomyNotification()
        emit("Apex autonomous run started · ${plan?.subgoals?.size} subgoals · ${value.maxCycles} cycle budget · OCR ${if (value.allowOcrEvidence) "consented" else "off"} · model ${if (value.allowModelAssistance) "local structured" else "off"}")
        if (!service.launchTarget(value.allowedPackage)) finish(AgentStatus.BLOCKED, "could not launch target package")
        else handler.postDelayed(::tick, 800)
    }

    fun cancel(reason: String = "Autonomous run stopped", quiet: Boolean = false) {
        if (!cancelled) service.stopGuardedExecution()
        runGeneration++
        service.hideAutonomyNotification()
        cancelled = true; handler.removeCallbacksAndMessages(null)
        if (!quiet) emit(reason)
    }

    private fun tick() {
        if (cancelled) return
        val target = goal ?: return
        if (android.os.SystemClock.uptimeMillis() - startedAt > target.maxRuntimeMs) {
            finish(AgentStatus.EXHAUSTED, "runtime budget exhausted"); return
        }
        val snapshot = service.currentSnapshot()
        if (snapshot == null) { handler.postDelayed(::tick, 400); return }
        val observation = AccessibilityObservationAdapter.adapt(snapshot, service.appVersion(snapshot.packageName))
        beliefTracker.update(observation.evidence)
        memory.invalidate(observation.packageName, observation.appVersion)
        if (observation.packageName != target.allowedPackage) {
            finish(AgentStatus.BLOCKED, "package boundary crossed"); return
        }
        val settling = stabilizer.offer(observation)
        if (settling.observation == null) { handler.postDelayed(::tick, 180); return }
        if (!settling.settled) { finish(AgentStatus.ABSTAINED, settling.reason); return }
        if (goalReached(target.successFact, observation.facts)) {
            completionEvidenceHits++
            if (completionEvidenceHits >= COMPLETION_QUORUM) {
                finish(AgentStatus.SUCCEEDED, "accessibility completion evidence verified across $COMPLETION_QUORUM observations")
            } else handler.postDelayed(::tick, 350)
            return
        }
        if (target.allowOcrEvidence && !ocrCheckInFlight) {
            val generation = runGeneration
            ocrCheckInFlight = true
            service.verifyOcrEvidence(target.successFact) { found, confidence ->
                if (cancelled || generation != runGeneration) return@verifyOcrEvidence
                ocrCheckInFlight = false
                beliefTracker.update(listOf(ObservationEvidence(
                    "text:${target.successFact.lowercase().trim()}", EvidenceSource.OCR, confidence
                )))
                if (found && confidence >= OCR_COMPLETION_CONFIDENCE) completionEvidenceHits++ else completionEvidenceHits = 0
                if (completionEvidenceHits >= COMPLETION_QUORUM) {
                    finish(AgentStatus.SUCCEEDED, "consented OCR completion evidence verified across $COMPLETION_QUORUM observations")
                } else if (found) handler.postDelayed(::tick, 350)
                else continuePlanning(observation, target)
            }
            return
        }
        completionEvidenceHits = 0
        continuePlanning(observation, target)
    }

    private fun continuePlanning(observation: AgentObservation, target: AgentGoal) {
        if (cancelled) return
        if (cycles >= target.maxCycles) { finish(AgentStatus.EXHAUSTED, "cycle budget exhausted"); return }
        val resource = resources.check()
        if (!resource.allowed) { finish(AgentStatus.BLOCKED, resource.reason); return }

        val path = frames.map { it.first }.toSet() + observation.screenId
        val activeSubgoal = hierarchy?.current
        val localGoal = if (activeSubgoal == null) target else target.copy(description = activeSubgoal.description)
        val decision = deliberator.choose(observation, localGoal, path)
        val action = decision.action
        if (action == null) {
            if (decision.reason.contains("ambiguous")) { finish(AgentStatus.ABSTAINED, decision.reason); return }
            val failed = frames.removeLastOrNull()
            if (failed == null) { finish(AgentStatus.EXHAUSTED, decision.reason); return }
            memory.markDeadEnd(failed.first, failed.second)
            execute(observation, AgentAction("back", "Back", kind = AgentActionKind.BACK), true)
            return
        }
        execute(observation, action, false, decision.confidence)
    }

    private fun execute(before: AgentObservation, action: AgentAction, backtrack: Boolean, confidence: Double = 1.0) {
        val target = goal ?: return
        val violations = AgentPlanValidator.validate(target, listOf(action))
        if (violations.isNotEmpty()) { finish(AgentStatus.BLOCKED, violations.joinToString { it.message }); return }
        cycles++
        checkpoints.beforeAction(target, cycles, action)
        stabilizer.reset()
        val generation = runGeneration
        emit("Apex cycle $cycles/${target.maxCycles}: ${action.kind.name.lowercase()} ${action.id.substringAfter(':').take(8)} · confidence ${(confidence * 100).toInt()}%")
        service.runGuardedAgentAction(action, target) { accepted, detail ->
            if (cancelled || generation != runGeneration) return@runGuardedAgentAction
            // Once the gateway returns, an irreversible operation is never considered replayable,
            // even if postcondition verification later fails.
            checkpoints.afterAction(target, cycles, action)
            handler.postDelayed({
                if (cancelled || generation != runGeneration) return@postDelayed
                val snapshot = service.currentSnapshot()
                val after = snapshot?.let { AccessibilityObservationAdapter.adapt(it, service.appVersion(it.packageName)) }
                val progressed = accepted && after != null &&
                    (after.screenId != before.screenId || after.facts != before.facts)
                if (!progressed) {
                    val kind = FailureClassifier.classify(before, after, detail, 500)
                    memory.record(TransitionExperience(before.screenId, action.id, after?.screenId ?: before.screenId,
                        false, before.packageName, before.appVersion, confidence = confidence, failure = kind))
                    if (recover(kind, before)) return@postDelayed
                    memory.markDeadEnd(before.screenId, action.id)
                } else if (after != null) {
                    recoveryAttempts.clear()
                    memory.record(TransitionExperience(before.screenId, action.id, after.screenId, true,
                        before.packageName, before.appVersion, confidence = confidence))
                    if (after.screenId in frames.map { it.first }) memory.markDeadEnd(before.screenId, action.id)
                    else if (!backtrack) {
                        frames.addLast(before.screenId to action.id)
                        hierarchy?.progress(after.facts, observedTransition = true, actionLabel = action.label)?.let { progress ->
                            if (progress.completed) emit("Apex subgoal ${progress.index + 1}/${plan?.subgoals?.size} completed with verified evidence")
                        }
                    }
                }
                tick()
            }, 550)
        }
    }

    /** Returns true when recovery scheduled or terminated the run. */
    private fun recover(kind: FailureKind, before: AgentObservation): Boolean {
        val policy = RecoveryPolicies.forFailure(kind)
        val attempts = recoveryAttempts[kind] ?: 0
        if (attempts >= policy.maxAttempts) {
            if (policy.action == RecoveryAction.ASK_USER || policy.action == RecoveryAction.ABSTAIN) {
                finish(AgentStatus.ABSTAINED, "$kind requires user intervention")
                return true
            }
            return false
        }
        recoveryAttempts[kind] = attempts + 1
        emit("Apex recovery ${policy.action.name.lowercase()} ${attempts + 1}/${policy.maxAttempts} for ${kind.name.lowercase()}")
        when (policy.action) {
            RecoveryAction.WAIT -> handler.postDelayed(::tick, 1_200)
            RecoveryAction.RETURN_TO_APP -> {
                service.launchTarget(goal?.allowedPackage ?: return false)
                handler.postDelayed(::tick, 800)
            }
            RecoveryAction.DISMISS_MODAL -> execute(before,
                AgentAction("back", "Back", kind = AgentActionKind.BACK), backtrack = true)
            RecoveryAction.ASK_USER, RecoveryAction.ABSTAIN ->
                finish(AgentStatus.ABSTAINED, "$kind requires user intervention")
            RecoveryAction.REPAIR_SELECTOR, RecoveryAction.BACKTRACK -> return false
        }
        return true
    }

    private fun goalReached(successFact: String, facts: Set<String>): Boolean {
        // Facts are stored through AccessibilityObservationAdapter.normalize, which collapses
        // whitespace runs; a goal typed with double spaces or a line break must compare equal
        // to the same words with normal spacing, or completion could never be observed.
        val expected = successFact.trim().lowercase().replace(Regex("\\s+"), " ")
        return facts.any { it.lowercase() == expected || it.lowercase() == "text:$expected" }
    }
    private fun finish(status: AgentStatus, detail: String) {
        checkpoints.finish()
        service.hideAutonomyNotification()
        cancelled = true; runGeneration++; handler.removeCallbacksAndMessages(null)
        emit("Apex ${status.name.lowercase()}: $detail · $cycles cycles")
    }

    private companion object {
        const val COMPLETION_QUORUM = 2
        const val OCR_COMPLETION_CONFIDENCE = 0.78
    }
}
