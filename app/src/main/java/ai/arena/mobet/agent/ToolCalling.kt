package ai.arena.mobet.agent

/**
 * Typed tool boundary for model and voice-driven assistance. Tools are capabilities, never
 * arbitrary code: a call names an allow-listed operation and carries bounded JSON-like arguments.
 * Validation happens before dispatch and every dispatcher remains responsible for policy gates.
 */
data class ToolCall(val id: String, val name: String, val arguments: Map<String, String>)
data class ToolResult(val id: String, val ok: Boolean, val output: String)

interface SafeTool {
    val name: String
    val description: String
    fun validate(arguments: Map<String, String>): String? = null
    fun invoke(arguments: Map<String, String>): String
}

class ToolRegistry(tools: List<SafeTool>) {
    private val toolsByName = tools.associateBy { it.name }

    fun names(): Set<String> = toolsByName.keys

    /** Rejects unknown tools, malformed identifiers, oversized calls, and invalid arguments. */
    fun dispatch(call: ToolCall): ToolResult {
        val tool = toolsByName[call.name]
            ?: return ToolResult(call.id, false, "Unknown tool: ${call.name}")
        if (!call.id.matches(ID)) return ToolResult(call.id, false, "Invalid tool call id")
        if (call.arguments.size > MAX_ARGUMENTS || call.arguments.any { it.key.length > 48 || it.value.length > 512 }) {
            return ToolResult(call.id, false, "Tool arguments exceed safety limits")
        }
        val error = tool.validate(call.arguments)
        if (error != null) return ToolResult(call.id, false, error)
        return runCatching { ToolResult(call.id, true, tool.invoke(call.arguments)) }
            .getOrElse { ToolResult(call.id, false, "Tool failed safely: ${it::class.simpleName}") }
    }

    companion object {
        private val ID = Regex("[A-Za-z0-9_-]{1,64}")
        private const val MAX_ARGUMENTS = 12
    }
}

/** A deliberately small built-in set; device mutation stays behind the existing agent policy. */
object SafeTools {
    fun forObservation(observation: AgentObservation): ToolRegistry = ToolRegistry(listOf(
        object : SafeTool {
            override val name = "describe_screen"
            override val description = "Return a privacy-minimized description of the current screen"
            override fun invoke(arguments: Map<String, String>) =
                "package=${observation.packageName}; screen=${observation.screenId}; actions=${observation.actions.size}"
        },
        object : SafeTool {
            override val name = "list_actions"
            override val description = "List allow-listed action identifiers"
            override fun invoke(arguments: Map<String, String>) = observation.actions.joinToString(",") { it.id }
        }
    ))
}
