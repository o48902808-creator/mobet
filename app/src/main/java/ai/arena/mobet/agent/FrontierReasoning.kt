package ai.arena.mobet.agent

import kotlin.math.exp

/** Screen content is untrusted data. These labels may be shown, but never interpreted as policy. */
enum class ContentTrust { STRUCTURAL, USER_INTENT, UNTRUSTED_INSTRUCTION }

data class TrustAssessment(val trust: ContentTrust, val reasons: List<String>)

/**
 * Heuristic detector for screen text that is trying to act like an instruction.
 *
 * **This is a signal, not a security boundary.** A blocklist cannot win against an adversary who
 * can iterate on phrasing: translate it, misspell it, space it out, or express it in a way nobody
 * thought to enumerate. Mobet's actual defence against injection is structural and lives
 * elsewhere — screen text can never widen the action set, [ai.arena.mobet.policy.PlanValidator]
 * and [ai.arena.mobet.policy.RiskEngine] gate every step, and model output may only rank
 * pre-approved canonical action IDs. This detector exists to *raise the cost* of the easy
 * attacks and to surface suspicious content to the user, not to be the thing standing between
 * a hostile app and the user's data.
 *
 * Because the detector is best-effort, it errs toward flagging. A false positive costs a
 * skipped candidate; a false negative costs nothing extra, because the structural controls still
 * apply.
 *
 * Note that some apparent false positives are desirable. Real controls labelled "Don't ask
 * again" or "Skip this step" *do* suppress future confirmations, and an autonomous agent
 * declining to press them without the user is the behaviour we want, not a bug to tune away.
 */
object ContentTrustEngine {

    /**
     * Folds the cheap evasions into a canonical form before matching.
     *
     * Without this, `"Ignore  previous"` (two spaces) and `"1gnore previous"` both walk straight
     * past the patterns. Normalising confusable digits, stripping zero-width characters, and
     * collapsing whitespace closes the class of evasions that cost an attacker nothing.
     */
    internal fun normalize(raw: String): String {
        val folded = StringBuilder(raw.length)
        for (ch in raw) {
            when (ch) {
                // Zero-width and bidi controls: invisible, so pure evasion.
                '\u200B', '\u200C', '\u200D', '\uFEFF',
                '\u202A', '\u202B', '\u202C', '\u202D', '\u202E' -> Unit
                // Common leetspeak substitutions.
                '0' -> folded.append('o')
                '1', '!', '|' -> folded.append('i')
                '3' -> folded.append('e')
                '4', '@' -> folded.append('a')
                '5', '$' -> folded.append('s')
                '7' -> folded.append('t')
                else ->
                    // Any Unicode space (incl. NBSP) becomes a plain space.
                    if (ch.isWhitespace() || ch == '\u00A0') folded.append(' ')
                    else folded.append(ch.lowercaseChar())
            }
        }
        // Collapse runs of whitespace, and also collapse "s p a c e d   o u t" text by
        // removing spaces between single characters.
        val collapsed = folded.toString().replace(Regex("\\s+"), " ").trim()
        return collapsed.replace(Regex("(?<=\\b\\w) (?=\\w\\b)"), "")
    }

    /**
     * Patterns are matched against [normalize]d text, so they are written in canonical form:
     * lowercase, single-spaced. Keep them loose — `\s*` between words rather than a literal
     * space — since precision here buys nothing but false negatives.
     */
    private val instructionInjection = listOf(
        // Instruction-override attempts, tolerant of the words in between.
        Regex("ignor\\w*\\s+(?:\\w+\\s+){0,3}(?:previous|prior|system|above|earlier)"),
        Regex("disregard\\s+(?:\\w+\\s+){0,3}(?:previous|prior|system|above|instruction)"),
        Regex("(?:developer|system|assistant)\\s+(?:message|prompt|instruction)"),
        // "New task"/"New rule" are ordinary button labels, so require the plural or a
        // following colon — the shape an injected directive actually takes.
        Regex("new\\s+(?:instructions|rules|tasks|directives)\\b"),
        Regex("new\\s+(?:instruction|rule|task|directive)\\s*[:\\-]"),
        // Exfiltration.
        Regex("(?:reveal|exfiltrat\\w*|upload|send|share|post|transmit|email)\\s+" +
            "(?:\\w+\\s+){0,3}(?:secret|password|passcode|token|credential|otp|pin|code|data)"),
        // Control-bypass, including the polite paraphrases a blocklist usually misses.
        Regex("(?:bypass|disable|override|skip|suppress|turn\\s*off)\\s+" +
            "(?:\\w+\\s+){0,3}(?:safety|policy|confirmation|confirm|security|check|verification|step)"),
        Regex("(?:do\\s*not|don'?t|no\\s+need\\s+to|without)\\s+" +
            "(?:\\w+\\s+){0,3}(?:ask|confirm\\w*|verify\\w*|permission|prompt|check)"),
        // Urgency/authority pressure, a common wrapper for the above.
        Regex("(?:this\\s+is\\s+)?(?:urgent|immediately|right\\s+now)\\s+" +
            "(?:\\w+\\s+){0,3}(?:transfer|send|delete|approve|confirm)")
    )

    fun assess(label: String): TrustAssessment {
        val normalized = normalize(label)
        val matched = instructionInjection.any { it.containsMatchIn(normalized) }
        return if (!matched) TrustAssessment(ContentTrust.STRUCTURAL, emptyList())
        else TrustAssessment(
            ContentTrust.UNTRUSTED_INSTRUCTION,
            listOf("screen content resembles instruction injection")
        )
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

    fun update(
        evidence: List<ObservationEvidence>,
        now: Long = System.currentTimeMillis(),
        /**
         * per-source trust for this fusion, typically [StateOfThoughtPolicy]'s regime
         * weighting; defaults to the healthy static weights so existing call sites are
         * unchanged. A degraded regime tightens corroboration through [BeliefReasoner.infer],
         * never loosens it — ground-truth channels stay at 1.0.
         */
        weights: Map<EvidenceSource, Double> = BeliefReasoner.DEFAULT_WEIGHTS
    ): BeliefState {
        evidence.take(80).forEach { history.addLast(Timed(it, now)) }
        while (history.size > maxEvidence) history.removeFirst()
        while (history.firstOrNull()?.let { now - it.at > halfLifeMs * 6 } == true) history.removeFirst()
        val decayed = history.map {
            val decay = exp(-(now - it.at).coerceAtLeast(0).toDouble() / halfLifeMs)
            it.evidence.copy(confidence = it.evidence.confidence * decay)
        }.filter { it.confidence >= .03 }
        return BeliefReasoner.infer(decayed, weights)
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
