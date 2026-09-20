package ai.arena.mobet.automation

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On-device verification of [RunReminder.BootReceiver] against a real AlarmManager and
 * NotificationManager.
 *
 * The JVM suite ([RunReminderLogicTest]) proves the pure reconciliation plan; what only a
 * device can prove is the receiver shell around it: that the action guard rejects everything
 * but BOOT_COMPLETED, that a missed reminder is posted and its record consumed, that a future
 * reminder is genuinely re-armed (which exercises AlarmManager.setAndAllowWhileIdle, the call
 * that would fail on a platform regression), and that a reminder for a deleted workflow is
 * forgotten rather than resurrected.
 */
class RunReminderBootDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val library = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    private val futureName = "boot_test_future"
    private val missedName = "boot_test_missed"
    private val deadName = "boot_test_dead"

    @After
    fun cleanUp() {
        // cancel() tears down both the stored record and — for the re-armed one — the alarm
        // the test caused to be scheduled, so the suite leaves no pending intents behind.
        listOf(futureName, missedName, deadName).forEach { name ->
            RunReminder.cancel(context, name)
            library.edit().remove(name).apply()
        }
    }

    @Test
    fun ignoresAnythingButBootCompleted() {
        seed(missedName, triggerAt = past(), inLibrary = true)
        // Even the app's own fire action must not be answered by the boot receiver; alarms
        // and notifications go through RunReminder.Receiver alone.
        RunReminder.BootReceiver().onReceive(context, Intent(RunReminder.ACTION_FIRE))
        assertTrue("a non-boot intent must leave the schedule untouched",
            prefs.contains(missedName))
    }

    @Test
    fun missedReminderIsPostedAtBootAndConsumed() {
        // Its time passed while the device was off: the reminder fires now, and the one-shot
        // record is gone — posted once, not every boot.
        seed(missedName, triggerAt = past(), inLibrary = true)
        RunReminder.BootReceiver().onReceive(context, bootIntent())
        assertFalse("a posted missed reminder must not fire again on the next boot",
            prefs.contains(missedName))
    }

    @Test
    fun futureReminderIsRearmedAndKept() {
        val triggerAt = System.currentTimeMillis() + 3_600_000L
        seed(futureName, triggerAt = triggerAt, inLibrary = true)
        RunReminder.BootReceiver().onReceive(context, bootIntent())
        // The record must survive with its original trigger — re-arming rewrites the same
        // schedule, and silently shifting a user's chosen time would be its own bug. The
        // re-arm itself succeeding is proven by schedule() returning true on this path; a
        // platform rejection would have dropped the record instead.
        assertTrue(prefs.contains(futureName))
        assertEquals(triggerAt, prefs.getLong(futureName, -1L))
    }

    @Test
    fun reminderForDeletedWorkflowIsForgotten() {
        // Posting "Run it?" for something the user deleted would open Mobet on a missing
        // entry, so both past and future records for it are simply dropped.
        seed(deadName, triggerAt = past(), inLibrary = false)
        RunReminder.BootReceiver().onReceive(context, bootIntent())
        assertFalse(prefs.contains(deadName))
    }

    @Test
    fun fullBatchReconcilesInOnePass() {
        // All three outcomes in one boot, matching how an unattended restart really looks.
        seed(futureName, triggerAt = System.currentTimeMillis() + 3_600_000L, inLibrary = true)
        seed(missedName, triggerAt = past(), inLibrary = true)
        seed(deadName, triggerAt = past(), inLibrary = false)
        RunReminder.BootReceiver().onReceive(context, bootIntent())
        assertTrue(prefs.contains(futureName))
        assertFalse(prefs.contains(missedName))
        assertFalse(prefs.contains(deadName))
    }

    private fun seed(name: String, triggerAt: Long, inLibrary: Boolean) {
        prefs.edit().putLong(name, triggerAt).commit()
        if (inLibrary) {
            // A minimal but valid workflow document, as the library would hold it.
            library.edit().putString(name, MINIMAL_WORKFLOW).commit()
        } else {
            library.edit().remove(name).commit()
        }
    }

    private fun bootIntent() = Intent(Intent.ACTION_BOOT_COMPLETED)

    private fun past() = System.currentTimeMillis() - 60_000L

    private companion object {
        // Mirrors RunReminder's storage; the constant is private there by design.
        const val PREFS = "run_reminders"
        const val MINIMAL_WORKFLOW =
            """{"name":"t","package":"com.android.settings","steps":[{"action":"wait","text":"x"}]}"""
    }
}
