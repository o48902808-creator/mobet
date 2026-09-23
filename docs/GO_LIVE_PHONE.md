# Going live from your phone — step by step

Everything below is designed to be done **on one Android phone**: GitHub in the
browser (or the GitHub app), then the released APK on the same device. Total time
is about 20 minutes, most of it waiting on CI. If a GitHub page hides a Settings
tab in the mobile layout, use the browser menu → *Desktop site* for that page.

## Phase 1 — merge the production PR (≈3 min)

1. Open **https://github.com/o48902808-creator/mobet/pull/16**.
2. Wait for the four checks to be green: *Build & JVM unit tests*,
   *Connected device tests*, *CodeQL Java and Kotlin*, *Dependency review*.
3. Tap **Merge pull request** → **Confirm merge**.
4. When offered "delete branch", **skip it** — keep the branch for reference.

## Phase 2 — the three one-time settings (≈3 min)

Two of the three are fully automated; run that first:

5. Go to **Actions** (top tab) → **Post-merge repo setup** → **Run workflow** →
   **Run workflow** again in the dialog. Wait ~30 seconds until the run is green.
   This verifies the dependency graph (on by default for public repos) and
   dispatches **Pin dependency checksums**.
6. Open **Actions → Pin dependency checksums** → its newest run. When it finishes
   green, check whether it opened a pull request or commit — if so, **merge that
   too**. From now on a tampered dependency fails every build instead of compiling.
7. **Branch protection** is the one setting automation cannot grant itself. Do it
   once by hand (*Desktop site* helps here): **Settings → Branches → Add branch
   protection rule** and set:
   - *Branch name pattern:* `main`
   - ☑ *Require a pull request before merging* (leave approvals at 0)
   - ☑ *Require status checks to pass* — in the search box, add each of:
     `Build & JVM unit tests`, `Connected device tests`,
     `CodeQL Java and Kotlin`, `Dependency review`
   - Leave force-push and deletion blocking at their defaults (blocked)
   - **Create** at the bottom.
   *(On a computer instead, `bash scripts/post-merge-setup.sh` does all three in
   one command — handy next time, not required now.)*

## Phase 3 — cut the release (≈6 min, mostly waiting)

8. **Actions → Release APK → Run workflow**. Fill in:
   - *Tag:* `v0.7.0`
   - *Title:* `v0.7.0`
   → **Run workflow**.
9. Wait for the green check (~5 min: it runs the full test suite, builds the APK,
   hashes it, and the publish job re-verifies the hash before publishing).
10. Open the **Code** tab → **Releases** (right column) → confirm **v0.7.0** is
    there with a `mobet.apk` asset. Your permanent download link is now live:
    **https://github.com/o48902808-creator/mobet/releases/latest/download/mobet.apk**

## Phase 4 — install on this phone (≈3 min)

11. Open the link from step 10 **in your phone's browser**; the APK downloads.
12. Tap the finished download. Android asks to *install unknown apps* for your
    browser — this is a per-app, revocable permission, not a global switch:
    allow it for the browser, tap back, and confirm **Install**.
13. If an older Mobet is already installed and the installer refuses (signature
    mismatch — expected, debug keys differ per build host): in Mobet's overflow
    menu use **Export library** first (uninstall wipes saved workflows, secrets,
    and captures — `allowBackup=false` is deliberate), then uninstall the old
    copy and install fresh.

## Phase 5 — grant the automation capability safely (≈4 min)

Mobet does nothing until the accessibility service is on — that is by design.
The order below matters: **unlock restricted settings first, then enable, then
prove the deny path works.**

14. **Unlock (Android 13+):** *Settings → Apps → Mobet → ⋮ menu (top right) →
    Allow restricted settings* → confirm with your fingerprint/PIN. Without this,
    the accessibility toggle stays greyed out for sideloaded apps.
15. **Enable:** open Mobet → the top card says the service is off → tap
    **Open accessibility settings** → *Installed services / Downloaded apps →
    Mobet* → switch **On** → tap **Allow** in the system dialog. Back in Mobet,
    the service card turns green and the dot starts breathing.
16. **Required deny test:** open that system dialog again (toggle off/on) and
    this time tap **Deny**. Verify the card still shows *service disabled* and
    **Run workflow** stays greyed out — the app must fail closed, never open.
    Then enable once more and tap **Allow**.

## Phase 6 — first controlled run (≈5 min)

17. The editor preloads the bundled demo workflow for the Settings app. Tap
    **Choose target app…** and pick **Settings** (or keep the demo default).
18. Tap **Validate policy** (sheet must pass) and **Dry run** — the simulated
    plan appears in the activity card, with secret placeholders masked.
19. Tap **▶ Run workflow**. Mobet opens the target app and performs the steps;
    the activity card narrates each action. **■ Stop** halts instantly at any
    point — try it once so your hands know where it is.
20. Optional: **◉ Record taps and scrolls in an app** captures your own actions
    into a workflow; **Secrets** stores masked values that never enter the JSON;
    overflow menu → **Export library** backs up before any future reinstall.

## Notes worth knowing on a phone

- Mobet's window is **FLAG_SECURE**: screenshots and screen recordings of the app
  are blacked out by the system. That is a feature — workflow JSON may reference
  secret names — not a broken phone.
- Every future release appears at the same `releases/latest/download/mobet.apk`
  link; upgrades are uninstall-then-install (export the library first, step 13).
- If a run ever misbehaves on your device: overflow → **Diagnostics**, and the
  activity card keeps a rolling log. `docs/TESTING_WALKTHROUGH.md` has the
  troubleshooting table.
