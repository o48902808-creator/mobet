package ai.arena.mobet.agent

/** Offline capability card shown by the app and embedded in evidence exports. */
data class CapabilityCard(
    val schema: String = "mobet.capability.v1",
    val appVersion: String,
    val policyVersion: String = "deterministic-policy.v1",
    val ledgerVersion: String = "hash-chain.v2",
    val voiceMode: String = "on-device-only",
    val modelMode: String = "device-gated-untrusted-output",
    val networkPermission: Boolean = false
) {
    fun verify(): String? = when {
        schema != "mobet.capability.v1" -> "Unsupported capability schema"
        networkPermission -> "Invalid capability card: network permission declared"
        voiceMode != "on-device-only" -> "Invalid capability card: voice may use network"
        else -> null
    }
}

enum class ToolRisk { READ_ONLY, REVERSIBLE, CONFIRM_REQUIRED, NEVER_AUTOMATIC }

data class ToolSpec(
    val name: String,
    val description: String,
    val risk: ToolRisk,
    val requiredPackage: String? = null,
    val requiresConfirmation: Boolean = risk == ToolRisk.CONFIRM_REQUIRED
)

/** A plan is executable only when every requested tool is known and its risk is acknowledged. */
object ToolPlanGate {
    fun validate(calls: List<ToolCall>, specs: List<ToolSpec>, confirmed: Set<String>): String? {
        val byName = specs.associateBy { it.name }
        calls.forEach { call ->
            val spec = byName[call.name] ?: return "Unknown tool: ${call.name}"
            if (spec.risk == ToolRisk.NEVER_AUTOMATIC) return "Tool is never automatic: ${call.name}"
            if (spec.requiresConfirmation && call.id !in confirmed) return "Confirmation required: ${call.name}"
        }
        return null
    }
}

interface VoiceEngine {
    val name: String
    val onDevice: Boolean
    val available: Boolean
}

/** Android's platform recognizer is selected only when it advertises an on-device backend. */
data class VoiceStatus(val engine: VoiceEngine, val transcript: String? = null, val reviewed: Boolean = false) {
    fun canCreateGoal(): Boolean = engine.available && engine.onDevice && reviewed && !transcript.isNullOrBlank()
}
