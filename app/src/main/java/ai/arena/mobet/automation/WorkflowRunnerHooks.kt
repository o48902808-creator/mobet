package ai.arena.mobet.automation

import ai.arena.mobet.agent.ExperienceStore
import ai.arena.mobet.agent.InMemoryExperienceStore

/**
 * Host callbacks that are intentionally not device operations.
 *
 * Confirmation UI, secure-surface policy and intervention telemetry belong to
 * the Android host, not to a driver such as Appium or a JVM fake.
 */
interface WorkflowRunnerHooks {
    fun unsafeSurfaceReason(allowedPackages: Set<String>): String? = null
    fun noteAutomatedAction() = Unit
    fun requestConfirmation(message: String, hardened: Boolean) = Unit

    companion object {
        val NONE: WorkflowRunnerHooks = object : WorkflowRunnerHooks {}
    }
}

/** Android host callbacks used by the production service. */
class AccessibilityWorkflowRunnerHooks(
    private val service: MobetAccessibilityService
) : WorkflowRunnerHooks {
    override fun unsafeSurfaceReason(allowedPackages: Set<String>): String? =
        service.unsafeSurfaceReason(allowedPackages)

    override fun noteAutomatedAction() = service.noteAutomatedAction()

    override fun requestConfirmation(message: String, hardened: Boolean) =
        service.requestConfirmation(message, hardened)
}

/** Persistent screen-transition storage is also a host concern. */
interface ScreenTransitionMemory {
    fun record(packageName: String, fromScreen: String, action: String, toScreen: String)

    companion object {
        val NONE: ScreenTransitionMemory = object : ScreenTransitionMemory {
            override fun record(packageName: String, fromScreen: String, action: String, toScreen: String) = Unit
        }
    }
}

/** Resolves a named secret without making the runner know how it is stored. */
fun interface SecretResolver {
    fun get(name: String): String?

    companion object {
        val NONE: SecretResolver = SecretResolver { null }
    }
}

/** Defaults used by a JVM runner when persistence is not part of the test. */
object WorkflowRunnerDefaults {
    val memory: ExperienceStore get() = InMemoryExperienceStore()
}
