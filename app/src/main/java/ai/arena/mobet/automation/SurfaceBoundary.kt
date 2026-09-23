package ai.arena.mobet.automation

/** Privacy-minimized window metadata used by the runtime surface guard. */
data class SurfaceWindow(
    val kind: Kind,
    val ownerPackage: String?,
    val active: Boolean
) {
    enum class Kind { APPLICATION, INPUT_METHOD, ACCESSIBILITY_OVERLAY, SYSTEM, OTHER }
}

object SurfaceBoundary {
    fun unsafeReason(
        windows: List<SurfaceWindow>,
        allowedPackages: Set<String>,
        ownPackage: String,
        secureSystemPackages: Set<String>
    ): String? {
        windows.forEach { window ->
            when (window.kind) {
                SurfaceWindow.Kind.ACCESSIBILITY_OVERLAY -> return "accessibility overlay detected"
                SurfaceWindow.Kind.SYSTEM -> if (
                    window.active || window.ownerPackage in secureSystemPackages
                ) return "active system surface detected${window.ownerPackage?.let { " ($it)" }.orEmpty()}"
                SurfaceWindow.Kind.APPLICATION -> if (
                    window.active && window.ownerPackage != null &&
                    window.ownerPackage != ownPackage && window.ownerPackage !in allowedPackages
                ) return "unexpected application window detected (${window.ownerPackage})"
                SurfaceWindow.Kind.INPUT_METHOD, SurfaceWindow.Kind.OTHER -> Unit
            }
        }
        return null
    }
}
