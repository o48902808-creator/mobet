package ai.arena.mobet.automation

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SurfaceBoundaryTest {
    private val secure = setOf("com.android.systemui", "com.android.permissioncontroller")

    @Test fun accessibilityOverlayAlwaysBlocks() {
        assertNotNull(check(SurfaceWindow(SurfaceWindow.Kind.ACCESSIBILITY_OVERLAY, "hostile.overlay", false)))
    }

    @Test fun unexpectedActiveApplicationBlocks() {
        assertNotNull(check(SurfaceWindow(SurfaceWindow.Kind.APPLICATION, "other.app", true)))
    }

    @Test fun allowedApplicationAndKeyboardRemainUsable() {
        assertNull(SurfaceBoundary.unsafeReason(
            listOf(
                SurfaceWindow(SurfaceWindow.Kind.APPLICATION, "target.app", true),
                SurfaceWindow(SurfaceWindow.Kind.INPUT_METHOD, "keyboard.app", true)
            ),
            setOf("target.app"), "ai.arena.mobet", secure
        ))
    }

    @Test fun permissionControllerBlocksEvenWhenNotActive() {
        assertNotNull(check(SurfaceWindow(
            SurfaceWindow.Kind.SYSTEM, "com.android.permissioncontroller", false
        )))
    }

    private fun check(window: SurfaceWindow) = SurfaceBoundary.unsafeReason(
        listOf(window), setOf("target.app"), "ai.arena.mobet", secure
    )
}
