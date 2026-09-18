package ai.arena.mobet.agent

import kotlin.math.exp

/** Screen content is untrusted data. These labels may be shown, but never interpreted as policy. */
enum class ContentTrust { STRUCTURAL, USER_INTENT, UNTRUSTED_INSTRUCTION }

data class TrustAssessment(val trust: ContentTrust, val reasons: List<String>)

object ContentTrustEngine {
    private val instructionInjection = listOf(
        Regex("(?i)ignore (?:all |the )?(?:previous|prior|system) (?:instructions|rules)"),
        Regex("(?i)(?:developer|system|assistant) message"),
        Regex("(?i)reveal|exfiltrate|upload|send (?:your |the )?(?:secret|password|token|data)"),
        Regex("(?i)bypass|disable|override (?:safety|policy|confirmation|security)"),
        Regex("(?i)do not ask|without (?:asking|confirmation|permission)")
    )

    fun assess(label: String): TrustAssessment {
        val matches = instructionInjection.filter { it.containsMatchIn(label) }
        return if (matches.isEmpty()) TrustAssessment(ContentTrust.STRUCTURAL, emptyList())
        else TrustAssessment(ContentTrust.UNTRUSTED_INSTRUCTION,
            listOf("screen content resembles instruction injection"))
    }
}

/** Bayesian transition quality; the prior prevents one lucky route from dominating planning. */
data class RouteReliability(
    val successProbability: Double,
    val confidence: Double,
    val attempts: Int,
    val lastSeen: Long
)

object ExperienceStatistics {
    fun reliability(items: List<TransitionExperience>, now: Long = System.currentTimeMillis()): RouteReliability {
        if (items.isEmpty()) return RouteReliability(.5, 0.0, 0, 0)
        var success = 1.0
        var failure = 1.0
        items.forEach {
            val ageDays = (now - it.observedAt).coerceAtLeast(0) / 86_400_000.0
            val weight = it.confidence.coerceIn(.05, 1.0) * exp(-ageDays / 45.0)
            if (it.progressed) success += weight else failure += weight
        }
        val attempts = items.size
        return RouteReliability(success / (success + failure),
            (attempts / 8.0).coerceIn(0.0, 1.0), attempts, items.maxOf { it.observedAt })
    }
}

/** Temporal fusion keeps recent observations while rapidly decaying unsupported hypotheses. */
class TemporalBeliefTracker(
    private val halfLifeMs: Long = 4_000,
    private val maxEvidence: Int = 160
) {
    private data class Timed(val evidence: ObservationEvidence, val at: Long)
    private val history = ArrayDeque<Timed>()

    fun update(evidence: List<ObservationEvidence>, now: Long = System.currentTimeMillis()): BeliefState {
        evidence.take(80).forEach { history.addLast(Timed(it, now)) }
        while (history.size > maxEvidence) history.removeFirst()
        while (history.firstOrNull()?.let { now - it.at > halfLifeMs * 6 } == true) history.removeFirst()
        val decayed = history.map {
            val decay = exp(-(now - it.at).coerceAtLeast(0).toDouble() / halfLifeMs)
            it.evidence.copy(confidence = it.evidence.confidence * decay)
        }.filter { it.confidence >= .03 }
        return BeliefReasoner.infer(decayed)
    }

    fun clear() = history.clear()
}

/** Immutable audit-friendly decision summary. It contains hashes/IDs, never screen or secret text. */
data class DecisionEnvelope(
    val observationId: String,
    val actionId: String,
    val policyRisk: Int,
    val confidence: Double,
    val reversible: Boolean,
    val lookaheadBudget: Int,
    val createdAt: Long = System.currentTimeMillis()
)
