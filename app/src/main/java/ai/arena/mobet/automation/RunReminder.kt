package ai.arena.mobet.automation

import ai.arena.mobet.MainActivity
import ai.arena.mobet.R
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Time-based **reminders** for saved workflows — deliberately not auto-execution.
 *
 * Mobet's threat model requires that every run is explicitly started by the user in the
 * foreground UI, with remote and unattended triggers disabled. A scheduler that fired a
 * workflow on its own would break that invariant in a way no amount of risk scoring
 * compensates for: an unattended run has nobody present to answer a confirmation prompt, to
 * notice a mis-grounded selector, or to hit Stop. A workflow that taps "Pay" at 03:00 with the
 * user asleep is a materially different and more dangerous product.
 *
 * So this schedules a *notification*. At the chosen time Mobet reminds the user, and tapping
 * the notification opens the app with that workflow loaded and ready — the user still presses
 * Run. The convenience of "don't forget this at 9am" is preserved; the safety property that a
 * human authorizes every device action is not weakened.
 */
object RunReminder {

    const val ACTION_FIRE = "ai.arena.mobet.REMINDER_FIRE"
    const val EXTRA_WORKFLOW_NAME = "workflow_name"
    private const val CHANNEL = "mobet-run-reminders"
    private const val PREFS = "run_reminders"

    /** Receives the alarm and posts the reminder. It never starts a workflow. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_FIRE) return
            val name = intent.getStringExtra(EXTRA_WORKFLOW_NAME) ?: return
            // Only remind for workflows that still exist in the library.
            val library = context.getSharedPreferences("library", Context.MODE_PRIVATE)
            if (!library.contains(name)) {
                cancel(context, name)
                return
            }
            notify(context, name)
            // One-shot: clear the stored schedule now that it has fired.
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(name).apply()
        }
    }

    /**
     * Reconciles the stored schedule after a reboot. AlarmManager drops every pending alarm
     * when the device powers off while the records in PREFS survive, so without this receiver
     * a "9am tomorrow" reminder would silently never fire — the schedule UI would keep
     * listing it, but nothing was armed to deliver it.
     *
     * Like every path in this file it only posts notifications; it never starts a workflow.
     * That is the entire reason Mobet declares RECEIVE_BOOT_COMPLETED.
     */
    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val entries = prefs.all
                .mapNotNull { (name, value) -> (value as? Long)?.let { name to it } }
                .toMap()
            if (entries.isEmpty()) return
            val live = context.getSharedPreferences("library", Context.MODE_PRIVATE).all.keys
            val plan = reschedulePlan(System.currentTimeMillis(), entries, live)
            // Still in the future: re-arm the same alarm. schedule() also rewrites the record.
            plan.rearm.forEach { name ->
                if (!schedule(context, name, entries.getValue(name))) {
                    // The system rejected the alarm; do not leave a phantom schedule behind.
                    prefs.edit().remove(name).apply()
                }
            }
            // Its time passed while the device was off: post the reminder at boot instead of
            // dropping it silently. One-shot, matching Receiver, so the record is consumed.
            plan.missed.forEach { name ->
                notify(context, name)
                prefs.edit().remove(name).apply()
            }
            // The workflow was deleted after scheduling; the record is useless.
            plan.dropped.forEach { name -> prefs.edit().remove(name).apply() }
        }
    }

    /** The reconciliation a boot restore performs, grouped by outcome. */
    data class BootReschedulePlan(
        val rearm: List<String>,
        val missed: List<String>,
        val dropped: List<String>
    )

    /**
     * Pure decision behind [BootReceiver], split out so the reconciliation is covered by JVM
     * tests without an AlarmManager. Stored triggers fall into three groups, each sorted for
     * a deterministic result: future ones to re-arm, ones missed while powered off (including
     * a trigger at exactly `now`, whose alarm can no longer fire on its own) to post at boot,
     * and ones whose workflow no longer exists to forget.
     */
    fun reschedulePlan(
        now: Long,
        entries: Map<String, Long>,
        liveWorkflows: Set<String>
    ): BootReschedulePlan {
        val rearm = mutableListOf<String>()
        val missed = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        entries.forEach { (name, triggerAt) ->
            when {
                name !in liveWorkflows -> dropped += name
                triggerAt > now -> rearm += name
                else -> missed += name
            }
        }
        return BootReschedulePlan(rearm.sorted(), missed.sorted(), dropped.sorted())
    }

    private fun notify(context: Context, name: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Workflow reminders", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Reminds you to start a saved workflow. Never runs one by itself." }
            )
        }
        val open = PendingIntent.getActivity(
            context,
            name.hashCode(),
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_WORKFLOW)
                .putExtra(EXTRA_WORKFLOW_NAME, name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = androidx.core.app.NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_run)
            .setContentTitle("Run “$name”?")
            .setContentText("Tap to open Mobet with this workflow loaded. Nothing runs automatically.")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { manager.notify(name.hashCode(), notification) }
    }

    // ── Scheduling ───────────────────────────────────────────────────────────

    /** True when the OS will let us post an alarm that survives Doze at an exact time. */
    fun canScheduleExact(context: Context): Boolean {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return false
        return Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
    }

    /**
     * Schedules a one-shot reminder. Inexact by design — a reminder does not need
     * to-the-second accuracy, and inexact alarms avoid requesting a sensitive permission.
     */
    fun schedule(context: Context, name: String, triggerAtMillis: Long): Boolean {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return false
        val pending = PendingIntent.getBroadcast(
            context,
            name.hashCode(),
            Intent(context, Receiver::class.java)
                .setAction(ACTION_FIRE)
                .setPackage(context.packageName)
                .putExtra(EXTRA_WORKFLOW_NAME, name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return runCatching {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(name, triggerAtMillis).apply()
            true
        }.getOrDefault(false)
    }

    fun cancel(context: Context, name: String) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            name.hashCode(),
            Intent(context, Receiver::class.java)
                .setAction(ACTION_FIRE)
                .setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching { alarms?.cancel(pending) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(name).apply()
    }

    /** Pending reminders, newest first, as (workflow name, trigger time). */
    fun pending(context: Context): List<Pair<String, Long>> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            .mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }
            .filter { it.second > System.currentTimeMillis() }
            .sortedBy { it.second }

    fun format(timestamp: Long): String =
        SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(timestamp))
}
