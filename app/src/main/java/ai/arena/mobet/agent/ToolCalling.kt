package ai.arena.mobet.agent

/**
 * Typed tool boundary for model and voice-driven assistance. Tools are capabilities, never
 * arbitrary code: a call names an allow-listed operation and carries bounded string arguments.
 * Deterministic schema, package, autonomy, risk, and confirmation checks all run before dispatch.
 */
data class ToolCall(val id: String, val name: String, val arguments: Map<String, String>)
data class ToolResult(val id: String, val ok: Boolean, val output: String)

enum class ToolValueType { STRING, BOOLEAN, INTEGER, ENUM }

data class ToolField(
    val name: String,
    val type: ToolValueType = ToolValueType.STRING,
    val required: Boolean = false,
    val sensitive: Boolean = false,
    val maxLength: Int = 256,
    val allowedValues: Set<String> = emptySet()
)

data class ToolInputSchema(
    val fields: List<ToolField> = emptyList(),
    val allowUnknown: Boolean = false
) {
    fun validate(arguments: Map<String, String>): String? {
        val byName = fields.associateBy(ToolField::name)
        fields.firstOrNull { it.required && it.name !in arguments }
            ?.let { return "${it.name} is required" }
        if (!allowUnknown) arguments.keys.firstOrNull { it !in byName }
            ?.let { return "Unknown argument: $it" }
        arguments.forEach { (name, value) ->
            val field = byName[name] ?: return@forEach
            if (value.length > field.maxLength.coerceIn(1, 512)) return "$name exceeds its length limit"
            when (field.type) {
                ToolValueType.STRING -> Unit
                ToolValueType.BOOLEAN -> if (value !in setOf("true", "false")) return "$name must be true or false"
                ToolValueType.INTEGER -> if (value.toLongOrNull() == null) return "$name must be an integer"
                ToolValueType.ENUM -> if (value !in field.allowedValues) return "$name is not an allowed value"
            }
        }
        return null
    }
}

data class ToolOutputSchema(
    val description: String,
    val maxLength: Int = 4_096
)

interface SafeTool {
    val name: String
    val description: String
    val inputSchema: ToolInputSchema
        get() = ToolInputSchema()
    val outputSchema: ToolOutputSchema
        get() = ToolOutputSchema("Bounded plain text result")
    val risk: ToolRisk
        get() = ToolRisk.READ_ONLY
    val requiredPackageScope: Set<String>
        get() = emptySet()
    val requiresConfirmation: Boolean
        get() = risk == ToolRisk.CONFIRM_REQUIRED
    val allowedDuringAutonomousExecution: Boolean
        get() = risk == ToolRisk.READ_ONLY || risk == ToolRisk.REVERSIBLE
    fun validate(arguments: Map<String, String>): String? = null
    fun invoke(arguments: Map<String, String>): String
}

data class ToolAuthorizationContext(
    val currentPackage: String? = null,
    val autonomous: Boolean = false,
    val confirmedCallIds: Set<String> = emptySet()
)

data class ToolAuditRecord(
    val callId: String,
    val toolName: String,
    val redactedArguments: Map<String, String>,
    val risk: ToolRisk,
    val policyDecision: String,
    val confirmationResult: String,
    val resultDigest: String,
    val ok: Boolean
) {
    /** Safe, single-line form suitable for AuditLedger.append(). */
    fun ledgerEvent(): String =
        "Tool $toolName · args ${redactedArguments.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "${it.key}=${it.value}" }}" +
            " · risk ${risk.name.lowercase()} · policy $policyDecision · confirmation $confirmationResult" +
            " · outcome ${if (ok) "succeeded" else "failed"} · result $resultDigest"
}

class ToolRegistry(
    tools: List<SafeTool>,
    private val audit: (ToolAuditRecord) -> Unit
) {
    init {
        require(tools.map { it.name }.distinct().size == tools.size) { "Tool names must be unique" }
        require(tools.all { it.name.matches(NAME) }) { "Invalid tool name" }
    }

    private val toolsByName = tools.associateBy { it.name }

    fun names(): Set<String> = toolsByName.keys

    fun specs(): List<ToolSpec> = toolsByName.values.map {
        ToolSpec(
            name = it.name,
            description = it.description,
            inputSchema = it.inputSchema,
            outputSchema = it.outputSchema,
            risk = it.risk,
            requiredPackageScope = it.requiredPackageScope,
            requiresConfirmation = it.requiresConfirmation,
            allowedDuringAutonomousExecution = it.allowedDuringAutonomousExecution
        )
    }

    /** Validate a proposed batch before any individual call is dispatched. */
    fun validatePlan(
        calls: List<ToolCall>,
        confirmed: Set<String> = emptySet(),
        currentPackage: String? = null,
        autonomous: Boolean = false
    ): String? = ToolPlanGate.validate(
        calls,
        specs(),
        ToolAuthorizationContext(currentPackage, autonomous, confirmed)
    )

    /**
     * Dispatch is independently authorized even when validatePlan was called earlier. This closes
     * the time-of-check/time-of-use gap and makes direct dispatch fail closed too.
     */
    fun dispatch(
        call: ToolCall,
        context: ToolAuthorizationContext = ToolAuthorizationContext()
    ): ToolResult {
        if (!call.id.matches(ID)) {
            return denied(call, null, "invalid_call_id", context, "Invalid tool call id")
        }
        val tool = toolsByName[call.name]
            ?: return denied(call, null, "unknown_tool", context, "Unknown tool: ${call.name}")
        val spec = specs().first { it.name == tool.name }
        val policyError = ToolPlanGate.validate(listOf(call), listOf(spec), context)
        if (policyError != null) {
            val decision = if (policyError.startsWith("Invalid ${tool.name} input:")) {
                "schema_rejected"
            } else "denied: $policyError"
            return denied(call, tool, decision, context, policyError)
        }
        if (call.arguments.size > MAX_ARGUMENTS || call.arguments.any { it.key.length > 48 || it.value.length > 512 }) {
            return denied(call, tool, "argument_limits", context, "Tool arguments exceed safety limits")
        }
        val schemaError = tool.inputSchema.validate(call.arguments)
        if (schemaError != null) return denied(call, tool, "schema_rejected", context, schemaError)
        val customError = tool.validate(call.arguments)
        if (customError != null) return denied(call, tool, "tool_validation_rejected", context, customError)

        val result = runCatching { tool.invoke(call.arguments) }
            .fold(
                onSuccess = { output ->
                    if (output.length > tool.outputSchema.maxLength.coerceIn(1, MAX_OUTPUT)) {
                        ToolResult(call.id, false, "Tool output exceeded its schema limit")
                    } else ToolResult(call.id, true, output)
                },
                onFailure = { ToolResult(call.id, false, "Tool failed safely: ${it::class.simpleName}") }
            )
        return if (record(call, tool, "allowed", context, result)) result
        else ToolResult(call.id, false, "Tool result could not be written to the audit ledger")
    }

    private fun denied(
        call: ToolCall,
        tool: SafeTool?,
        decision: String,
        context: ToolAuthorizationContext,
        message: String
    ): ToolResult {
        val result = ToolResult(call.id, false, message)
        return if (record(call, tool, decision, context, result)) result
        else ToolResult(call.id, false, "Tool denial could not be written to the audit ledger")
    }

    private fun record(
        call: ToolCall,
        tool: SafeTool?,
        decision: String,
        context: ToolAuthorizationContext,
        result: ToolResult
    ): Boolean {
        val sensitiveNames = tool?.inputSchema?.fields?.filter(ToolField::sensitive)?.map(ToolField::name).orEmpty().toSet()
        val redacted = call.arguments.entries.associate { (name, value) ->
            val safeName = name.take(48).replace(Regex("[^A-Za-z0-9_.-]"), "_")
            safeName to if (name in sensitiveNames || SENSITIVE_KEY.containsMatchIn(name)) {
                "[REDACTED]"
            } else value.take(96).replace(Regex("[\\r\\n]"), " ")
        }
        val confirmationRequired = tool != null &&
            (tool.requiresConfirmation || tool.risk == ToolRisk.CONFIRM_REQUIRED)
        val confirmation = when {
            !confirmationRequired -> "not_required"
            call.id in context.confirmedCallIds -> "approved"
            else -> "missing"
        }
        return runCatching {
            audit(
                ToolAuditRecord(
                    callId = call.id,
                    toolName = call.name.take(64),
                    redactedArguments = redacted,
                    risk = tool?.risk ?: ToolRisk.NEVER_AUTOMATIC,
                    policyDecision = decision.take(160),
                    confirmationResult = confirmation,
                    resultDigest = ScreenFingerprint.sha256(result.output).take(24),
                    ok = result.ok
                )
            )
        }.isSuccess
    }

    companion object {
        private val ID = Regex("[A-Za-z0-9_-]{1,64}")
        private val NAME = Regex("[a-z][a-z0-9_]{1,63}")
        private val SENSITIVE_KEY = Regex("(?i)secret|token|password|passcode|pin|key|authorization|value|text")
        private const val MAX_ARGUMENTS = 12
        private const val MAX_OUTPUT = 16_384
    }
}

/** A deliberately small built-in set; device mutation stays behind the existing agent policy. */
object SafeTools {
    fun forObservation(
        observation: AgentObservation,
        audit: (ToolAuditRecord) -> Unit
    ): ToolRegistry = ToolRegistry(
        listOf(
            object : SafeTool {
                override val name = "describe_screen"
                override val description = "Return a privacy-minimized description of the current screen"
                override val requiredPackageScope = setOf(observation.packageName)
                override val outputSchema = ToolOutputSchema("Package, structural fingerprint, and action count", 512)
                override fun invoke(arguments: Map<String, String>) =
                    "package=${observation.packageName}; screen=${observation.screenId}; actions=${observation.actions.size}"
            },
            object : SafeTool {
                override val name = "list_actions"
                override val description = "List deterministic-policy candidate action identifiers"
                override val requiredPackageScope = setOf(observation.packageName)
                override val outputSchema = ToolOutputSchema("Comma-separated opaque action identifiers", 8_192)
                override fun invoke(arguments: Map<String, String>) =
                    observation.actions.joinToString(",") { it.id }
            },
            object : SafeTool {
                override val name = "inspect_current_package"
                override val description = "Return the package currently bound to this observation"
                override val requiredPackageScope = setOf(observation.packageName)
                override val outputSchema = ToolOutputSchema("Android package name", 256)
                override fun invoke(arguments: Map<String, String>) = observation.packageName
            }
        ),
        audit
    )
}
