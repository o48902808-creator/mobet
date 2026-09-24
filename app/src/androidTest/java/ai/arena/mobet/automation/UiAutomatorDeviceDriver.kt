package ai.arena.mobet.automation

import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import java.io.File

/**
 * A second real driver backed by AndroidX UiAutomator.
 *
 * This lives in the instrumentation source set: UiAutomator is a test/device
 * capability and must not become part of the network-isolated production APK.
 * The object still implements the same DeviceDriver used by WorkflowRunner.
 */
class UiAutomatorDeviceDriver(
    private val device: UiDevice = UiDevice.getInstance(
        InstrumentationRegistry.getInstrumentation()
    ),
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
) : DeviceDriver {
    override fun root(): UiNode? =
        device.findObject(By.pkg(activePackageName() ?: context.packageName))?.let(::UiAutomatorUiNode)

    override fun launch(packageName: String): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    override fun back(): Boolean = device.pressBack()

    override fun home(): Boolean = device.pressHome()

    override fun performPointGesture(
        startX: Double,
        startY: Double,
        endX: Double?,
        endY: Double?,
        durationMs: Long,
        callback: (Boolean) -> Unit
    ) {
        val width = device.displayWidth
        val height = device.displayHeight
        val x1 = (width * startX.coerceIn(0.02, 0.98)).toInt()
        val y1 = (height * startY.coerceIn(0.02, 0.98)).toInt()
        val x2 = (width * (endX ?: startX).coerceIn(0.02, 0.98)).toInt()
        val y2 = (height * (endY ?: startY).coerceIn(0.02, 0.98)).toInt()
        val steps = (durationMs / 5L).coerceIn(1L, 200L).toInt()
        callback(runCatching { device.swipe(x1, y1, x2, y2, steps) }.getOrDefault(false))
    }

    override fun captureScreen(callback: (Boolean, String) -> Unit) {
        val file = File(context.cacheDir, "mobet-capture-${System.currentTimeMillis()}.png")
        val ok = runCatching { device.takeScreenshot(file) }.getOrDefault(false)
        callback(ok, if (ok) file.absolutePath else "UiAutomator screenshot failed")
    }

    override fun findVisualText(
        query: String,
        callback: (Boolean, String, Double, Double) -> Unit
    ) {
        val match = device.findObject(By.textContains(query))
        if (match == null) {
            callback(false, "UiAutomator text not found: $query", 0.0, 0.0)
            return
        }
        val bounds = match.visibleBounds
        callback(
            true,
            "UiAutomator matched: $query",
            bounds.centerX().toDouble() / device.displayWidth,
            bounds.centerY().toDouble() / device.displayHeight
        )
    }

    override fun activePackageName(): String? = device.currentPackageName

    override fun appVersion(packageName: String): String? = runCatching {
        @Suppress("DEPRECATION") context.packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull()
}

private class UiAutomatorUiNode(
    private val object2: UiObject2
) : UiNode {
    override val label: String?
        get() = text ?: description ?: viewId?.substringAfterLast('/')

    override val text: String?
        get() = object2.text?.trim()?.takeIf(String::isNotBlank)

    override val viewId: String?
        get() = object2.resourceName?.takeIf(String::isNotBlank)

    override val description: String?
        get() = object2.contentDescription?.toString()?.trim()?.takeIf(String::isNotBlank)

    override val role: String
        get() = object2.className?.substringAfterLast('.') ?: "View"

    override val bounds: UiBounds
        get() = object2.visibleBounds.let { UiBounds(it.left, it.top, it.right, it.bottom) }

    override val clickable: Boolean
        get() = object2.isClickable

    override val editable: Boolean
        // UiObject2 exposes the underlying class, but not an isEditable flag on all
        // supported UiAutomator versions. EditText is the stable cross-version signal.
        get() = object2.className?.contains("EditText", ignoreCase = true) == true

    override val scrollable: Boolean
        get() = object2.isScrollable

    override val children: List<UiNode>
        get() = object2.children.map(::UiAutomatorUiNode)

    override fun performClick(): Boolean {
        var current: UiObject2? = object2
        while (current != null) {
            if (current.isClickable) {
                current.click()
                return true
            }
            current = current.parent
        }
        return false
    }

    override fun focus(): Boolean = runCatching {
        object2.click()
        true
    }.getOrDefault(false)

    override fun scroll(): Boolean = runCatching {
        object2.scroll(Direction.DOWN, 0.8f)
    }.getOrDefault(false)

    override fun setText(value: String): Boolean = runCatching {
        object2.setText(value)
        true
    }.getOrDefault(false)
}
