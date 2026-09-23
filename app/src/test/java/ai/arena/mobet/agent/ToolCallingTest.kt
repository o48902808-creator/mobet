package ai.arena.mobet.agent

import org.junit.Assert.*
import org.junit.Test

class ToolCallingTest {
    private val audit = mutableListOf<ToolAuditRecord>()
    private val registry = ToolRegistry(
        listOf(object : SafeTool {
            override val name = "echo"
            override val description = "test"
            override val inputSchema = ToolInputSchema(
                listOf(ToolField("value", required = true, sensitive = true))
            )
            override fun invoke(arguments: Map<String, String>) = arguments.getValue("value")
        }),
        { audit += it }
    )

    @Test fun validCallDispatchesAndAudits() {
        val result = registry.dispatch(ToolCall("t1", "echo", mapOf("value" to "hi")))
        assertTrue(result.ok)
        assertEquals("hi", result.output)
        assertEquals("[REDACTED]", audit.single().redactedArguments.getValue("value"))
        assertEquals("allowed", audit.single().policyDecision)
        assertEquals(24, audit.single().resultDigest.length)
        assertFalse(audit.single().ledgerEvent().contains("hi"))
    }

    @Test fun unknownToolIsRejectedAndAudited() {
        assertFalse(registry.dispatch(ToolCall("t1", "delete_everything", emptyMap())).ok)
        assertEquals(ToolRisk.NEVER_AUTOMATIC, audit.single().risk)
        assertEquals("unknown_tool", audit.single().policyDecision)
    }

    @Test fun typedSchemaRejectsUnknownAndMalformedArguments() {
        assertFalse(registry.dispatch(ToolCall("t1", "echo", emptyMap())).ok)
        assertTrue(audit.single().policyDecision.contains("schema"))

        val typed = ToolRegistry(listOf(object : SafeTool {
            override val name = "configure"
            override val description = "typed"
            override val inputSchema = ToolInputSchema(listOf(
                ToolField("enabled", ToolValueType.BOOLEAN, required = true),
                ToolField("count", ToolValueType.INTEGER),
                ToolField("mode", ToolValueType.ENUM, allowedValues = setOf("safe", "explain"))
            ))
            override fun invoke(arguments: Map<String, String>) = "ok"
        }), { audit += it })
        assertFalse(typed.dispatch(ToolCall("t2", "configure", mapOf("enabled" to "yes"))).ok)
        assertFalse(typed.dispatch(ToolCall("t3", "configure", mapOf("enabled" to "true", "extra" to "x"))).ok)
    }

    @Test fun malformedIdIsRejectedAndAudited() {
        assertFalse(registry.dispatch(ToolCall("bad id", "echo", mapOf("value" to "x"))).ok)
        assertEquals("invalid_call_id", audit.single().policyDecision)
    }

    @Test fun planGateRunsBeforeDispatch() = assertNull(
        registry.validatePlan(listOf(ToolCall("t1", "echo", mapOf("value" to "x"))))
    )

    @Test fun confirmationIsEnforcedAtDispatchNotOnlyPlanTime() {
        val risky = ToolRegistry(listOf(object : SafeTool {
            override val name = "submit"
            override val description = "submit"
            override val risk = ToolRisk.CONFIRM_REQUIRED
            override val allowedDuringAutonomousExecution = false
            override fun invoke(arguments: Map<String, String>) = "sent"
        }), { audit += it })
        val call = ToolCall("t2", "submit", emptyMap())
        assertNotNull(risky.validatePlan(listOf(call)))
        assertFalse(risky.dispatch(call).ok)
        assertTrue(risky.dispatch(call, ToolAuthorizationContext(confirmedCallIds = setOf("t2"))).ok)
    }

    @Test fun neverAutomaticToolCannotBeConfirmedIntoAuthority() {
        val forbidden = ToolRegistry(listOf(object : SafeTool {
            override val name = "grant_permission"
            override val description = "Never model authorized"
            override val risk = ToolRisk.NEVER_AUTOMATIC
            override val requiresConfirmation = true
            override val allowedDuringAutonomousExecution = false
            override fun invoke(arguments: Map<String, String>) = "should never run"
        }), { audit += it })
        val call = ToolCall("t3", "grant_permission", emptyMap())
        val result = forbidden.dispatch(call, ToolAuthorizationContext(confirmedCallIds = setOf("t3")))
        assertFalse(result.ok)
        assertTrue(result.output.contains("never model-authorized"))
    }

    @Test fun packageScopeIsEnforcedOnEveryDispatch() {
        val scoped = ToolRegistry(listOf(object : SafeTool {
            override val name = "inspect_package"
            override val description = "scoped"
            override val requiredPackageScope = setOf("com.example.safe")
            override fun invoke(arguments: Map<String, String>) = "ok"
        }), { audit += it })
        assertFalse(scoped.dispatch(
            ToolCall("t4", "inspect_package", emptyMap()),
            ToolAuthorizationContext(currentPackage = "com.example.other")
        ).ok)
        assertTrue(scoped.dispatch(
            ToolCall("t5", "inspect_package", emptyMap()),
            ToolAuthorizationContext(currentPackage = "com.example.safe")
        ).ok)
    }

    @Test fun autonomousRestrictionIsEnforced() {
        val manualOnly = ToolRegistry(listOf(object : SafeTool {
            override val name = "open_app"
            override val description = "manual only"
            override val risk = ToolRisk.REVERSIBLE
            override val allowedDuringAutonomousExecution = false
            override fun invoke(arguments: Map<String, String>) = "opened"
        }), { audit += it })
        assertFalse(manualOnly.dispatch(
            ToolCall("t6", "open_app", emptyMap()),
            ToolAuthorizationContext(autonomous = true)
        ).ok)
    }

    @Test fun auditFailureFailsClosed() {
        val unauditable = ToolRegistry(listOf(object : SafeTool {
            override val name = "read_status"
            override val description = "read"
            override fun invoke(arguments: Map<String, String>) = "status"
        }), { error("disk full") })
        val result = unauditable.dispatch(ToolCall("audit1", "read_status", emptyMap()))
        assertFalse(result.ok)
        assertTrue(result.output.contains("audit ledger"))
    }

    @Test fun outputSchemaBoundsResults() {
        val bounded = ToolRegistry(listOf(object : SafeTool {
            override val name = "bounded"
            override val description = "bounded"
            override val outputSchema = ToolOutputSchema("tiny", maxLength = 3)
            override fun invoke(arguments: Map<String, String>) = "too long"
        }), { audit += it })
        assertFalse(bounded.dispatch(ToolCall("t7", "bounded", emptyMap())).ok)
    }

    @Test fun duplicateCallIdsAndOversizedPlansAreRejected() {
        val call = ToolCall("same", "echo", mapOf("value" to "x"))
        assertNotNull(registry.validatePlan(listOf(call, call)))
        assertNotNull(registry.validatePlan(List(51) { ToolCall("id$it", "echo", mapOf("value" to "x")) }))
    }

    @Test fun builtInObservationToolsDeclareAndEnforcePackageScope() {
        val records = mutableListOf<ToolAuditRecord>()
        val tools = SafeTools.forObservation(
            AgentObservation("screen123", "com.example.safe", emptyList()),
            { records += it }
        )
        assertEquals(
            setOf("describe_screen", "list_actions", "inspect_current_package"),
            tools.names()
        )
        assertTrue(tools.specs().all { it.risk == ToolRisk.READ_ONLY })
        assertTrue(tools.specs().all { it.requiredPackageScope == setOf("com.example.safe") })
        assertFalse(tools.dispatch(
            ToolCall("p1", "describe_screen", emptyMap()),
            ToolAuthorizationContext(currentPackage = "com.attacker")
        ).ok)
        assertTrue(tools.dispatch(
            ToolCall("p2", "describe_screen", emptyMap()),
            ToolAuthorizationContext(currentPackage = "com.example.safe", autonomous = true)
        ).ok)
        assertEquals(2, records.size)
    }
}
