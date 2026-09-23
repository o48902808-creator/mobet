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
    val inputSchema: ToolInputSchema = ToolInputSchema(),
    val outputSchema: ToolOutputSchema = ToolOutputSchema("Bounded plain text result"),
    val requiredPackageScope: Set<String> = emptySet(),
    val requiresConfirmation: Boolean = risk == ToolRisk.CONFIRM_REQUIRED,
    val allowedDuringAutonomousExecution: Boolean =
        risk == ToolRisk.READ_ONLY || risk == ToolRisk.REVERSIBLE
)

/** A plan is executable only when every requested tool is known and its risk is acknowledged. */
object ToolPlanGate {
    fun validate(
        calls: List<ToolCall>,
        specs: List<ToolSpec>,
        context: ToolAuthorizationContext
    ): String? {
        if (calls.size > 50) return "Tool plan exceeds 50 calls"
        if (calls.map(ToolCall::id).distinct().size != calls.size) return "Tool call ids must be unique"
        val byName = specs.associateBy { it.name }
        calls.forEach { call ->
            if (!call.id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return "Invalid tool call id"
            if (call.arguments.size > 12 || call.arguments.any { it.key.length > 48 || it.value.length > 512 }) {
                return "Tool arguments exceed safety limits"
            }
            val spec = byName[call.name] ?: return "Unknown tool: ${call.name}"
            if (spec.risk == ToolRisk.NEVER_AUTOMATIC) return "Tool is never model-authorized: ${call.name}"
            if (context.autonomous && !spec.allowedDuringAutonomousExecution) {
                return "Tool is not allowed during autonomous execution: ${call.name}"
            }
            if (spec.requiredPackageScope.isNotEmpty() && context.currentPackage !in spec.requiredPackageScope) {
                return "Package scope blocked ${call.name}: ${context.currentPackage ?: "no active package"}"
            }
            if ((spec.requiresConfirmation || spec.risk == ToolRisk.CONFIRM_REQUIRED) &&
                call.id !in context.confirmedCallIds
            ) {
                return "Confirmation required: ${call.name}"
            }
            spec.inputSchema.validate(call.arguments)?.let { return "Invalid ${call.name} input: $it" }
        }
        return null
    }

    fun validate(calls: List<ToolCall>, specs: List<ToolSpec>, confirmed: Set<String>): String? =
        validate(calls, specs, ToolAuthorizationContext(confirmedCallIds = confirmed))
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
