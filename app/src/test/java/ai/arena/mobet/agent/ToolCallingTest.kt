package ai.arena.mobet.agent

import org.junit.Assert.*
import org.junit.Test

class ToolCallingTest {
    private val registry = ToolRegistry(listOf(object : SafeTool {
        override val name = "echo"
        override val description = "test"
        override fun validate(arguments: Map<String, String>) =
            if ("value" !in arguments) "value is required" else null
        override fun invoke(arguments: Map<String, String>) = arguments.getValue("value")
    }))

    @Test fun validCallDispatches() = assertEquals("hi", registry.dispatch(ToolCall("t1", "echo", mapOf("value" to "hi"))).output)
    @Test fun unknownToolIsRejected() = assertFalse(registry.dispatch(ToolCall("t1", "delete_everything", emptyMap())).ok)
    @Test fun invalidArgumentsNeverInvoke() = assertFalse(registry.dispatch(ToolCall("t1", "echo", emptyMap())).ok)
    @Test fun malformedIdIsRejected() = assertFalse(registry.dispatch(ToolCall("bad id", "echo", mapOf("value" to "x"))).ok)
    @Test fun planGateRunsBeforeDispatch() = assertNull(registry.validatePlan(listOf(ToolCall("t1", "echo", mapOf("value" to "x")))))
    @Test fun highRiskPlanNeedsConfirmation() {
        val risky = ToolRegistry(listOf(object : SafeTool {
            override val name = "submit"
            override val description = "submit"
            override val risk = ToolRisk.CONFIRM_REQUIRED
            override fun invoke(arguments: Map<String, String>) = "sent"
        }))
        assertNotNull(risky.validatePlan(listOf(ToolCall("t2", "submit", emptyMap()))))
        assertNull(risky.validatePlan(listOf(ToolCall("t2", "submit", emptyMap())), setOf("t2")))
    }
}
