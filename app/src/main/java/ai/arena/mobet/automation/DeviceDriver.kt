package ai.arena.mobet.automation

/**
 * Device operations required by WorkflowRunner.
 *
 * The runner owns workflow policy and state transitions; implementations own
 * how a screen is read and how an action reaches the device. Keeping callbacks
 * on the asynchronous operations preserves the existing Android behavior while
 * allowing a deterministic fake to complete them synchronously in JVM tests.
 */
interface DeviceDriver {
    fun root(): UiNode?
    fun launch(packageName: String): Boolean
    fun back(): Boolean
    fun home(): Boolean

    fun performPointGesture(
        startX: Double,
        startY: Double,
        endX: Double?,
        endY: Double?,
        durationMs: Long,
        callback: (Boolean) -> Unit
    )

    fun captureScreen(callback: (Boolean, String) -> Unit)

    fun findVisualText(
        query: String,
        callback: (Boolean, String, Double, Double) -> Unit
    )

    fun activePackageName(): String?
    fun appVersion(packageName: String): String?
}

/** Production driver backed by MobetAccessibilityService. */
class AccessibilityDriver(
    private val service: MobetAccessibilityService
) : DeviceDriver {
    override fun root(): UiNode? = service.root()

    override fun launch(packageName: String): Boolean = service.launch(packageName)

    override fun back(): Boolean =
        service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)

    override fun home(): Boolean =
        service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)

    override fun performPointGesture(
        startX: Double,
        startY: Double,
        endX: Double?,
        endY: Double?,
        durationMs: Long,
        callback: (Boolean) -> Unit
    ) = service.performPointGesture(startX, startY, endX, endY, durationMs, callback)

    override fun captureScreen(callback: (Boolean, String) -> Unit) =
        service.captureScreen(callback)

    override fun findVisualText(
        query: String,
        callback: (Boolean, String, Double, Double) -> Unit
    ) = service.findVisualText(query, callback)

    override fun activePackageName(): String? = service.activePackageName()

    override fun appVersion(packageName: String): String? = service.appVersion(packageName)
}
