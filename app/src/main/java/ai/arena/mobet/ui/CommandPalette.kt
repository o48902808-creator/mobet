package ai.arena.mobet.ui

import ai.arena.mobet.R
import android.app.Activity

/**
 * Searchable list of every action in the app.
 *
 * Mobet exposes around twenty actions across three cards and an overflow menu, all at equal visual
 * weight, which makes finding one a scanning exercise — and leaves external-keyboard users nothing
 * to drive. The palette turns that into typing: "aud" reaches the audit ledger, "gen" the plan
 * generator. It adds no capability — every entry invokes the same function its button does — so it
 * changes discoverability only, never authority.
 *
 * Presentation reuses [MobetUi.picker], which already has a search field and row rendering; only
 * the ranking is new, and it is kept pure so it can be unit-tested.
 */
object CommandPalette {

    /**
     * One entry. [keywords] carries synonyms the label does not contain (e.g. "log" for the audit
     * ledger, "undo" for the library), so searching for the word a user actually thinks of still
     * finds the action.
     */
    data class Command(
        val title: String,
        val subtitle: String,
        val keywords: String = "",
        val icon: Int = R.drawable.ic_plan,
        val run: () -> Unit = {}
    )

    /**
     * Ranking: title prefix, then title substring, then keyword, then subtitle.
     *
     * Ties keep the caller's order, which is the order the actions appear on screen — so an empty
     * query shows the app's own layout rather than an alphabetised list the user has never seen.
     */
    fun filter(commands: List<Command>, query: String): List<Command> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return commands
        return commands.mapIndexedNotNull { index, command ->
            val title = command.title.lowercase()
            val rank = when {
                title.startsWith(needle) -> 0
                title.contains(needle) -> 1
                command.keywords.lowercase().contains(needle) -> 2
                command.subtitle.lowercase().contains(needle) -> 3
                else -> return@mapIndexedNotNull null
            }
            Triple(rank, index, command)
        }.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
    }

    fun show(activity: Activity, commands: List<Command>) {
        // Pre-ranked once for the initial order; the picker's own search box filters within it,
        // and Row.searchKey carries the keywords so synonyms keep working as the user types.
        val ordered = filter(commands, "")
        MobetUi.picker(
            activity = activity,
            title = activity.getString(R.string.action_command_palette),
            subtitle = "Everything Mobet can do · nothing runs until you choose it",
            icon = R.drawable.ic_search,
            rows = ordered.map { command ->
                MobetUi.Row(
                    title = command.title,
                    subtitle = command.subtitle,
                    icon = command.icon,
                    searchKey = "${command.title} ${command.subtitle} ${command.keywords}".lowercase()
                )
            },
            emptyTitle = "No actions available",
            emptyBody = "Enable the automation service to unlock Mobet's actions."
        ) { index -> ordered[index].run() }
    }
}
