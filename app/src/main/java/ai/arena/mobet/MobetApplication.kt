package ai.arena.mobet

import android.app.Application
import com.google.android.material.color.DynamicColors

/**
 * Applies the user's Material You palette to every activity on Android 12+.
 *
 * Deliberately partial: dynamic colour re-tints *neutral* chrome — surfaces, the primary accent,
 * containers — but Mobet's semantic colours (`mobet_success`, `mobet_warning`, `mobet_danger`) are
 * fixed literals in `colors.xml` and are never derived from the wallpaper. Those four tones encode
 * risk: "this step is destructive" must not become a pleasant lilac because the user changed their
 * wallpaper. Personalisation is welcome everywhere it cannot change the meaning of a warning.
 */
class MobetApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
