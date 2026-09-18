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

    fun toStep(action: AgentAction): Step? = when (action.kind) {
        AgentActionKind.BACK -> Step("back")
        AgentActionKind.SCROLL -> Step("scroll", parseSelector(action.selector) ?: return null, message = action.label)
        AgentActionKind.TAP -> Step("tap", parseSelector(action.selector) ?: return null, message = action.label)
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
            confidence = element.confidence / 100.0)
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
    private val deliberator = Deliberator(memory)
    private var goal: AgentGoal? = null
    private var plan: HierarchicalPlan? = null
    private var subgoalIndex = 0
    private var cancelled = true
    private var cycles = 0
    private val frames = ArrayDeque<Pair<String, String>>()
    private val recoveryAttempts = mutableMapOf<FailureKind, Int>()

    fun start(value: AgentGoal) {
        cancel("Autonomous run replaced", quiet = true)
        require(value.maxCycles in 1..50) { "Cycle budget must be 1–50" }
        goal = value; plan = HierarchicalPlanner.decompose(value); subgoalIndex = 0
        cycles = 0; frames.clear(); recoveryAttempts.clear(); cancelled = false
        emit("Apex autonomous run started · ${plan?.subgoals?.size} subgoals · ${value.maxCycles} cycle budget")
        if (!service.launchTarget(value.allowedPackage)) finish(AgentStatus.BLOCKED, "could not launch target package")
        else handler.postDelayed(::tick, 800)
    }

    fun cancel(reason: String = "Autonomous run stopped", quiet: Boolean = false) {
        if (!cancelled) service.stopGuardedExecution()
        cancelled = true; handler.removeCallbacksAndMessages(null)
        if (!quiet) emit(reason)
    }

    private fun tick() {
        if (cancelled) return
        val target = goal ?: return
        if (cycles >= target.maxCycles) { finish(AgentStatus.EXHAUSTED, "cycle budget exhausted"); return }
        val snapshot = service.currentSnapshot()
        if (snapshot == null) { handler.postDelayed(::tick, 400); return }
        val observation = AccessibilityObservationAdapter.adapt(snapshot, service.appVersion(snapshot.packageName))
        memory.invalidate(observation.packageName, observation.appVersion)
        if (goalReached(target.successFact, observation.facts)) { finish(AgentStatus.SUCCEEDED, "completion evidence verified"); return }
        if (observation.packageName != target.allowedPackage) { finish(AgentStatus.BLOCKED, "package boundary crossed"); return }

        val path = frames.map { it.first }.toSet() + observation.screenId
        val activeSubgoal = plan?.subgoals?.getOrNull(subgoalIndex)
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
        emit("Apex cycle $cycles/${target.maxCycles}: ${action.kind.name.lowercase()} ${action.id.substringAfter(':').take(8)} · confidence ${(confidence * 100).toInt()}%")
        service.runGuardedAgentAction(action, target) { accepted, detail ->
            if (cancelled) return@runGuardedAgentAction
            handler.postDelayed({
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
                        val subgoals = plan?.subgoals.orEmpty()
                        if (subgoalIndex < subgoals.lastIndex && semanticOverlap(action.label, subgoals[subgoalIndex].description)) {
                            emit("Apex subgoal ${subgoalIndex + 1}/${subgoals.size} completed with observed screen transition")
                            subgoalIndex++
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

    private fun semanticOverlap(label: String, description: String): Boolean {
        fun terms(value: String) = value.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
        return terms(label).intersect(terms(description)).isNotEmpty()
    }

    private fun goalReached(successFact: String, facts: Set<String>): Boolean {
        val expected = successFact.trim().lowercase()
        return facts.any { it.lowercase() == expected || it.lowercase() == "text:$expected" }
    }
    private fun finish(status: AgentStatus, detail: String) {
        cancelled = true; handler.removeCallbacksAndMessages(null)
        emit("Apex ${status.name.lowercase()}: $detail · $cycles cycles")
    }
}
