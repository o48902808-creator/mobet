package ai.arena.mobet.automation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the reboot-restore reconciliation behind [RunReminder.BootReceiver].
 *
 * AlarmManager drops every scheduled alarm at power-off, so the boot path is the only thing
 * standing between "the phone restarted" and reminders silently never firing. The decision is
 * pure — context-free and deterministic — so it runs on the JVM; the receiver itself is a thin
 * shell that executes this plan.
 */
class RunReminderLogicTest {

    private fun plan(
        now: Long,
        entries: Map<String, Long>,
        live: Set<String>
    ): RunReminder.BootReschedulePlan = RunReminder.reschedulePlan(now, entries, live)

    @Test
    fun futureRemindersAreRearmed() {
        val now = 1_000_000L
        val result = plan(now, mapOf("morning" to now + 60_000L), setOf("morning"))
        assertEquals(listOf("morning"), result.rearm)
        assertEquals(emptyList<String>(), result.missed)
        assertEquals(emptyList<String>(), result.dropped)
    }

    @Test
    fun remindersMissedWhilePoweredOffFireAtBoot() {
        val now = 1_000_000L
        val result = plan(
            now,
            mapOf("while off" to now - 1L),
            setOf("while off")
        )
        // A late reminder is more useful than a silently dropped one: post at boot.
        assertEquals(listOf("while off"), result.missed)
        assertEquals(emptyList<String>(), result.rearm)
    }

    @Test
    fun triggersAtExactlyNowAreTreatedAsMissed() {
        // An alarm re-armed for `now` would fire arbitrarily late; the reminder was due, so
        // the boot path posts it immediately rather than gambling on delivery timing.
        val now = 1_000_000L
        val result = plan(now, mapOf("due" to now), setOf("due"))
        assertEquals(listOf("due"), result.missed)
        assertEquals(emptyList<String>(), result.rearm)
    }

    @Test
    fun remindersForDeletedWorkflowsAreDroppedWithoutFiring() {
        // Posting "Run X?" for a workflow the user deleted would open Mobet on a missing
        // entry. The record is simply forgotten — past or future makes no difference.
        val now = 1_000_000L
        val result = plan(
            now,
            mapOf("gone future" to now + 60_000L, "gone past" to now - 60_000L),
            emptySet()
        )
        assertEquals(listOf("gone future", "gone past"), result.dropped)
        assertEquals(emptyList<String>(), result.missed)
        assertEquals(emptyList<String>(), result.rearm)
    }

    @Test
    fun mixedBatchKeepsSortedDeterministicOrder() {
        val now = 1_000_000L
        val entries = linkedMapOf(
            "zeta future" to now + 5_000L,
            "alpha missed" to now - 5_000L,
            "gone" to now + 5_000L,
            "beta future" to now + 9_000L
        )
        val result = plan(now, entries, setOf("zeta future", "alpha missed", "beta future"))
        assertEquals(listOf("beta future", "zeta future"), result.rearm)
        assertEquals(listOf("alpha missed"), result.missed)
        assertEquals(listOf("gone"), result.dropped)
    }

    @Test
    fun emptyScheduleIsANoOp() {
        val result = plan(1_000_000L, emptyMap(), setOf("anything"))
        assertEquals(emptyList<String>(), result.rearm)
        assertEquals(emptyList<String>(), result.missed)
        assertEquals(emptyList<String>(), result.dropped)
    }
}
