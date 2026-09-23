package ai.arena.mobet.automation

import ai.arena.mobet.R
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick-settings tile for the pinned workflow (docs/FRONTIER.md pillar 4).
 *
 * The tile never acts on the device itself: a click is an explicit user gesture that opens
 * the app on the pinned workflow, and the run proceeds through the ordinary pipeline —
 * planning, risk tiers, confirmations — with the user now present for them. The tile service
 * holds no state beyond a label refresh; the pin lives in [PresenceLauncher].
 */
class MobetTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val target = PresenceLauncher.intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this, 0, target,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            launchLegacy(target)
        }
    }

    /** The only route on API 26–33; the PendingIntent overload exists from API 34. */
    @android.annotation.SuppressLint("Deprecated")
    private fun launchLegacy(target: Intent) = startActivityAndCollapse(target)

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val pinned = PresenceLauncher.resolve(this)
        tile.state = Tile.STATE_INACTIVE
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            tile.subtitle = pinned?.name ?: getString(R.string.tile_unpinned)
        }
        tile.updateTile()
    }
}
