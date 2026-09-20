package ai.arena.mobet.security

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the manifest-level security posture that the rest of the threat model rests on.
 *
 * These assertions are deliberately blunt. Mobet holds an accessibility service that can read
 * every label on every screen the user visits; several properties here are the difference
 * between a local automation tool and a surveillance capability. They are easy to erase with a
 * one-line edit and hard to notice in review, so they are asserted rather than assumed.
 *
 * The Gradle task `verifyDebugNoNetworkPermission` checks the *merged* manifest, which is what
 * a malicious dependency would target. This test checks the manifest we author, so the two
 * cover different attack paths: accidental hand-edit here, transitive contribution there.
 */
class ManifestPostureTest {

    private val manifest: String by lazy {
        // Unit tests run with the module directory as CWD, but allow for a repo-root run too.
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        )
        val file = candidates.firstOrNull(File::exists)
            ?: error("AndroidManifest.xml not found; looked in ${candidates.map(File::getAbsolutePath)}")
        file.readText()
    }

    /** Permission names that would give a screen-reading app an exfiltration channel. */
    private val forbidden = listOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.CHANGE_NETWORK_STATE",
        "android.permission.CHANGE_WIFI_STATE",
        "android.permission.NEARBY_WIFI_DEVICES",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.BLUETOOTH_SCAN"
    )

    /**
     * Every `<uses-permission>` entry, paired with whether it is a declaration or a
     * `tools:node="remove"` directive stripping a dependency-contributed permission.
     */
    private fun permissionEntries(): List<Pair<String, Boolean>> =
        Regex("""<uses-permission\b([^>]*)/>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifest)
            .mapNotNull { match ->
                val attrs = match.groupValues[1]
                val name = Regex("""android:name="([^"]+)"""").find(attrs)?.groupValues?.get(1)
                name?.to(attrs.contains("""tools:node="remove""""))
            }
            .toList()

    @Test
    fun declaresNoNetworkPermission() {
        val declared = permissionEntries().filter { !it.second }.map { it.first }
        val found = forbidden.filter(declared::contains)
        assertEquals(
            "Mobet must not declare network permissions: an accessibility service that can " +
                "read every screen plus network access is an exfiltration channel. If this is " +
                "intentional, update docs/THREAT_MODEL.md and FORBIDDEN_PERMISSIONS in " +
                "app/build.gradle.kts in the same commit.",
            emptyList<String>(),
            found
        )
    }

    @Test
    fun declaresOnlyTheExpectedPermissions() {
        // Catches a *new* permission of any kind, including ones not yet on the forbidden list.
        // The full set today: POST_NOTIFICATIONS (run reminders + the active-run indicator) and
        // RECEIVE_BOOT_COMPLETED (re-arming those reminders after a reboot — see
        // RunReminder.BootReceiver, which can only post notifications, never start a workflow).
        val declared = permissionEntries().filter { !it.second }.map { it.first }
        assertEquals(
            "Mobet's permission set changed. Every permission is a capability an accessibility " +
                "service can abuse; justify it in the threat model before adding it here.",
            listOf(
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.RECEIVE_BOOT_COMPLETED"
            ),
            declared
        )
    }

    @Test
    fun stripsTheNetworkPermissionsMlKitContributes() {
        // ML Kit's datatransport dependency declares INTERNET and ACCESS_NETWORK_STATE for
        // telemetry. On-device OCR needs neither, so they are removed at merge time. Without
        // these directives the shipped APK holds network access while the docs claim otherwise.
        val removed = permissionEntries().filter { it.second }.map { it.first }
        listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE"
        ).forEach {
            assertTrue(
                "$it must be stripped with tools:node=\"remove\"; a dependency contributes it.",
                removed.contains(it)
            )
        }
    }

    @Test
    fun backupStaysDisabled() {
        // allowBackup=true would copy the encrypted secret store and audit ledger off-device.
        assertTrue(
            "allowBackup must remain false: backups would carry secrets and the audit ledger " +
                "off the device, defeating the on-device-only posture.",
            manifest.contains("android:allowBackup=\"false\"")
        )
    }

    @Test
    fun cleartextTrafficStaysDisabled() {
        // Defence in depth: even with no INTERNET permission, this keeps the posture explicit
        // so that adding network access later cannot silently permit plaintext.
        assertTrue(
            "usesCleartextTraffic must remain false.",
            manifest.contains("android:usesCleartextTraffic=\"false\"")
        )
    }

    @Test
    fun accessibilityServiceIsNotExported() {
        // An exported accessibility service could be bound by another app.
        val service = manifest.substringAfter(".automation.MobetAccessibilityService")
            .substringBefore("</service>")
        assertTrue(
            "The accessibility service must not be exported.",
            service.contains("android:exported=\"false\"")
        )
        assertTrue(
            "The accessibility service must require BIND_ACCESSIBILITY_SERVICE so only the " +
                "system can bind it.",
            service.contains("android.permission.BIND_ACCESSIBILITY_SERVICE")
        )
    }

    @Test
    fun reminderReceiverIsNotExported() {
        // An exported receiver would let any app fire Mobet's reminder intent.
        val receiver = manifest.substringAfter(".automation.RunReminder\$Receiver")
            .substringBefore("</receiver>")
        assertTrue(
            "The reminder receiver must not be exported.",
            receiver.contains("android:exported=\"false\"")
        )
    }

    @Test
    fun bootReceiverIsNotExported() {
        // BOOT_COMPLETED is a protected broadcast only the system can send, and the system can
        // still deliver it to a non-exported receiver. exported=false adds nothing for the
        // system path but blocks a malicious app from invoking the receiver with a spoofed
        // explicit intent to deliver reminders out of schedule.
        val receiver = manifest.substringAfter(".automation.RunReminder\$BootReceiver")
            .substringBefore("</receiver>")
        assertTrue(
            "The boot receiver must not be exported.",
            receiver.contains("android:exported=\"false\"")
        )
    }

    @Test
    fun fileProviderIsNotExported() {
        // The provider is scoped to files/exports/; exporting it would widen that scope.
        val provider = manifest.substringAfter("androidx.core.content.FileProvider")
            .substringBefore("</provider>")
        assertTrue(
            "The FileProvider must not be exported; access is granted per-URI at share time.",
            provider.contains("android:exported=\"false\"")
        )
    }
}
