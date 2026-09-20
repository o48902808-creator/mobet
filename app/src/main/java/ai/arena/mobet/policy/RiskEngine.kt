package ai.arena.mobet.policy

import ai.arena.mobet.automation.Step

/** Ordered risk tiers. ELEVATED and above require an adjacent user confirmation. */
enum class RiskTier { NONE, LOW, ELEVATED, CRITICAL }

data class RiskAssessment(val tier: RiskTier, val score: Int, val reasons: List<String>)

/**
 * Deterministic, explainable risk scorer for individual workflow steps.
 *
 * Signals are additive so a "Pay $9.99" tap scores higher than a plain "Submit" tap and can cross
 * into CRITICAL, which the runner escalates to a hardened (typed) confirmation. The engine is pure
 * and unit-testable; it never consults the network or a model, so plan gating stays reproducible.
 */
object RiskEngine {
    private val consequential = Regex(
        "(?i)\\b(submit|send|pay|buy|purchase|order|book|transfer|post|publish|sign|accept|confirm|checkout|donate|subscribe|upgrade)\\b"
    )
    private val destructive = Regex(
        "(?i)\\b(delete|remove|erase|wipe|deactivate|unsubscribe|uninstall|format|reset|revoke|discard)\\b"
    )
    private val financial = Regex(
        "(?i)(?:[${'$'}€£¥₦₵]\\s?\\d|\\b(?:usd|eur|gbp|ngn|ghs|kes|zar|inr)\\s?\\d|\\b(?:iban|wire transfer|routing number|card number)\\b)"
    )
    private val credential = Regex(
        "(?i)\\b(password|passcode|passphrase|otp|2fa|mfa|cvv|cvc|verification code|security code)\\b"
    )
    private val visualBase = mapOf(
        "tappoint" to 30, "swipe" to 30, "capture" to 30, "ocrwait" to 30, "visualtap" to 35
    )

    fun assess(step: Step): RiskAssessment {
        // A confirm step is itself the safety mechanism; it never requires another confirm.
        if (step.action == "confirm") return RiskAssessment(RiskTier.NONE, 0, emptyList())

        var score = visualBase[step.action] ?: when (step.action) {
            "tap" -> 5
            "fill" -> 5
            // Switching apps is reversible and already bounded by policy.allowedPackages, so it
            // stays below the confirmation threshold — but it is not free, because it changes
            // which app subsequent steps act on.
            "launch" -> 10
            else -> 0
        }
        val reasons = mutableListOf<String>()
        if (step.action in visualBase) reasons += "low-confidence visual action"

        // Language signals only matter for steps that act on a target. Passively waiting for the
        // word "Delete" is harmless; tapping it is not.
        val actsOnTarget = step.action in setOf("tap", "visualtap", "tappoint", "fill", "swipe")
        if (actsOnTarget) {
            val corpus = listOfNotNull(
                step.selector.text, step.selector.description, step.selector.viewId,
                step.value, step.message
            ).joinToString(" ")
            if (consequential.containsMatchIn(corpus)) { score += 35; reasons += "consequential language" }
            if (destructive.containsMatchIn(corpus)) { score += 45; reasons += "destructive language" }
            if (financial.containsMatchIn(corpus)) { score += 40; reasons += "financial signal" }
            if (credential.containsMatchIn(corpus)) { score += 20; reasons += "credential signal" }
        }

        val tier = when {
            score <= 0 -> RiskTier.NONE
            score < 30 -> RiskTier.LOW
            score < 70 -> RiskTier.ELEVATED
            else -> RiskTier.CRITICAL
        }
        return RiskAssessment(tier, score, reasons)
    }
}
