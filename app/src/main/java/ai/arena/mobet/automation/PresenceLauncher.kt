package ai.arena.mobet.automation

import android.content.Context
import android.content.Intent
import ai.arena.mobet.MainActivity

/**
 * Local presence surfaces (docs/FRONTIER.md pillar 4): the quick-settings tile and the
 * launcher shortcut both trigger the workflow the user has pinned for the shade.
 *
 * Pinning is deliberately two-channel: the explicit "Pin to shade" action on any library
 * entry, and an automatic re-pin of whatever workflow the user just ran — the run is the
 * strongest signal of what a deep-trigger should fire, and it keeps the tile useful before
 * the user discovers the pin button. A snapshot of the source travels with the pin so the
 * tile still fires after the library copy is deleted (the library copy, when present, wins —
 * pins track edits rather than freezing history).
 *
 * Neither surface ships a new permission, and neither relaxes the run pipeline: parsing,
 * risk tiers, and every confirmation gate apply exactly as when the run button is pressed.
 */
object PresenceLauncher {

    /** Deep-trigger action handled by MainActivity; also the launcher-shortcut intent action. */
    const val ACTION_RUN_PINNED = "ai.arena.mobet.RUN_PINNED"

    /** A pinned workflow: its library name plus the source to run (library copy preferred). */
    data class Pinned(val name: String, val source: String)

    fun pin(context: Context, name: String, source: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_NAME, name)
            .putString(KEY_SOURCE, source)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** The current pin, or null when the user has never pinned or run a workflow. */
    fun resolve(context: Context): Pinned? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_NAME, null) ?: return null
        val live = context.getSharedPreferences("library", Context.MODE_PRIVATE)
            .getString(name, null)
        val source = live ?: prefs.getString(KEY_SOURCE, null) ?: return null
        return Pinned(name, source)
    }

    fun intent(context: Context): Intent =
        Intent(context, MainActivity::class.java).setAction(ACTION_RUN_PINNED)

    private const val PREFS = "presence"
    private const val KEY_NAME = "pinned_name"
    private const val KEY_SOURCE = "pinned_source"
}
