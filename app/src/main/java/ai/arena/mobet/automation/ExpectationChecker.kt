package ai.arena.mobet.automation

/**
 * One screen observation, distilled to exactly what the post-step evidence check needs.
 *
 * Built in `WorkflowRunner.observeScreen` from the same snapshot that feeds the world model,
 * so the check adds no tree traversal of its own. `visibleLabels` carries the accessibility
 * text channel (labels and content descriptions) — the ground-truth channel, deliberately
 * preferred over OCR, which stays the lower-trust fallback everywhere else in the app.
 */
data class ExpectationEvidence(
    /** Fingerprint of the screen before the step executed; null without a baseline. */
    val previousFingerprint: String?,
    /** Fingerprint of the screen after the step executed. */
    val fingerprint: String,
    /** Package owning the observed window. */
    val packageName: String,
    /** Visible accessibility labels from the snapshot (already bounded upstream). */
    val visibleLabels: Set<String>
)

/**
 * Deterministic verifier for post-step evidence assertions (docs/FRONTIER.md pillar 2).
 *
 * `check` returns null when every declared assertion holds, otherwise a human-readable
 * failure reason the runner embeds directly in the halt message. Evaluation order is fixed
 * (screenChange, then textPresent, then textAbsent, then packageIs) so results are
 * reproducible and order-stable in tests.
 *
 * Semantics chosen for honesty over convenience:
 *  - `screenChange` with no baseline (first screen of the run, or the first observation
 *    after a `launch` reset) passes vacuously: a launch IS the change, and inventing a
 *    baseline would only manufacture a failure the author could not have predicted.
 *  - With a baseline, an identical fingerprint means nothing happened — that is the failure
 *    worth reporting, because every later step is about to act on an assumption of progress.
 *  - Text assertions are case-insensitive substring matches over the accessibility label
 *    channel, matching how `ifText`/`unlessText` read the same channel upstream.
 */
object ExpectationChecker {

    fun check(expectation: Expectation, evidence: ExpectationEvidence): String? {
        if (expectation.screenChange) {
            val previous = evidence.previousFingerprint
            if (previous != null && previous == evidence.fingerprint)
                return "expected the screen to change, but it is identical"
        }
        expectation.textPresent?.let { wanted ->
            val found = evidence.visibleLabels.any { it.contains(wanted, ignoreCase = true) }
            if (!found) return "expected text “$wanted” is not on screen"
        }
        expectation.textAbsent?.let { unwanted ->
            val present = evidence.visibleLabels.any { it.contains(unwanted, ignoreCase = true) }
            if (present) return "text “$unwanted” should be gone but is still visible"
        }
        expectation.packageIs?.let { wanted ->
            if (evidence.packageName != wanted)
                return "expected package $wanted but the active package is ${evidence.packageName}"
        }
        return null
    }
}
