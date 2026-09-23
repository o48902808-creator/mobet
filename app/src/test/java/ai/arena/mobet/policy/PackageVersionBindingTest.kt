package ai.arena.mobet.policy

import ai.arena.mobet.automation.Workflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageVersionBindingTest {
    @Test fun workflowParsesExactPackageVersionBindings() {
        val workflow = Workflow.parse("""
            {"name":"bound","package":"com.example.app",
             "policy":{"allowedPackages":["com.example.app"],
                       "packageVersions":{"com.example.app":"4.2.1"}},
             "steps":[{"action":"wait","text":"Ready"}]}
        """.trimIndent())
        assertEquals("4.2.1", workflow.policy.packageVersions["com.example.app"])
        assertTrue(PlanValidator.validate(workflow).isEmpty())
    }

    @Test fun bindingOutsideAllowlistIsRejected() {
        val workflow = Workflow.parse("""
            {"name":"bad","package":"com.example.app",
             "policy":{"allowedPackages":["com.example.app"],
                       "packageVersions":{"com.other.app":"1"}},
             "steps":[{"action":"wait","text":"Ready"}]}
        """.trimIndent())
        assertTrue(PlanValidator.validate(workflow).any { it.message.contains("Version-bound") })
    }
}
