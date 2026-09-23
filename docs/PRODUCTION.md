# Production readiness & release runbook

Mobet ships as a sideloaded APK from GitHub Releases — no Play Store, no F-Droid.
"Production" here means: the artifact at the stable download URL always reflects
the newest reviewed state of `main`, the pipeline that produced it is visible, and
every control below has an owner and a click path.

> **Stable download URL (after the first release):**
> `https://github.com/o48902808-creator/mobet/releases/latest/download/mobet.apk`

## 1. Merge evidence (what the PR carries)

Before merging, confirm the two checks on the pull request are green:

| Check | What it proves |
| --- | --- |
| **Android CI** | JVM unit tests (deterministic, 216 cases), merged-manifest network check (debug + release), Android security lint with findings surfaced as annotations, debug APK assembles, and the 21 on-device/on-emulator tests (Keystore boundary, encrypted persistence, Espresso control surface) pass on API 29. |
| **Security analysis** | CodeQL over Java/Kotlin is clean and the dependency review finds no new vulnerable, unlicensed, or malicious packages. |

The build additionally self-verifies a load-bearing invariant: `assemble*` for every
variant runs `verify*NoNetworkPermission` against the **merged** manifest, so a
dependency bump can never smuggle `INTERNET` into a shipped artifact. See
`app/build.gradle.kts` and `docs/THREAT_MODEL.md`.

## 2. Post-merge repository settings (3 one-time actions, automated)

All three are automated — two paths, both idempotent (re-running verifies rather than
re-changes):

**A. One click** — *Actions → Post-merge repo setup → Run workflow.* With the built-in
`GITHUB_TOKEN` it verifies the dependency graph and dispatches Pin dependency checksums;
branch protection needs an admin scope that token never has, so the job prints the exact
command instead of failing.

**B. One command** — `bash scripts/post-merge-setup.sh` with your own `gh` login applies
**all three**: dependency graph (verified/enabled), branch protection on `main`, and the
checksum-pinning dispatch (skipped if it has ever run).

The equivalent manual settings, as a fallback:

1. **Dependency graph** — public repositories have it on by default, so this is normally a
   verification, not a change: *Settings → Code security and analysis → Dependency graph.*
2. **Branch protection for `main`** — require a pull request and the four status checks
   (`Build & JVM unit tests`, `Connected device tests`, `CodeQL Java and Kotlin`,
   `Dependency review`); block force pushes and deletions.
3. **Pin dependency checksums** — *Actions → Pin dependency checksums → Run workflow*, then
   merge its output so tampered artifacts fail every later build.

## 3. Cutting the release

> **Doing this from a phone?** [`docs/GO_LIVE_PHONE.md`](GO_LIVE_PHONE.md) is the
> same process as a tap-by-tap walkthrough — merge, settings, release, install,
> first run — designed for the GitHub mobile layout.

The APK asset is built by *Actions → Release APK → Run workflow* (or by pushing a
`v*` tag). The current line is **0.8.0 / versionCode 9**; the next release tag is:

```
workflow_dispatch: Release APK
  tag:   v0.8.0
  title: v0.8.0
```

The job builds `assembleDebug`, verifies it is non-empty, records the SHA-256, and a
separate, no-project-code publish job re-verifies the checksum before creating the
GitHub Release with the build-log link. The `releases/latest/download/mobet.apk`
URL above then rotates to it automatically.

## 4. What "debug-signed" means for upgrades (read before shipping)

Releases are intentionally signed with the build's debug key — no managed keystore,
no CI secrets, by design. Debug keys are generated **per build host**, so two
releases are not guaranteed to share a signature. Consequence for users:

- **Treat every upgrade as a fresh install.** If Android reports
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, uninstall the previous APK first.
- Because `android:allowBackup="false"` is deliberate (workflow JSON may reference
  secret names), uninstalling **destroys the saved workflow library**. Users should
  export their library (overflow menu → *Export library*) before upgrading and
  re-import afterwards.

An optional future hardening path is already wired: `app/build.gradle.kts` creates a
real `release` signing config automatically when keystore material is provided via
environment or `local.properties`. Switching release.yml to `assembleRelease` with
a managed keystore is a deliberate, reviewed change — exactly the kind of decision
this document exists to slow down, not to block.

## 5. Rollback

- Bad release published: delete the GitHub Release **and** its tag
  (`gh release delete vX.Y.Z && git push origin :refs/tags/vX.Y.Z`), then fix on a
  branch and re-release with the next tag. `releases/latest` follows the newest
  non-deleted release.
- Bad merge on `main`: revert the merge commit through a normal PR (branch
  protection requires it) rather than force-pushing history.

## 6. Standing posture (don't regress these)

- No network permission in the merged manifest — enforced at build time, every variant.
- `FLAG_SECURE` on the main window: workflow JSON and secrets metadata never appear
  in recents thumbnails or third-party screenshots.
- Accessibility service exported settings stay off; nothing runs until the user
  enables the service and taps Run.
- Secrets live in Android Keystore-backed storage; the app never asks for them to be
  typed into workflows (masked refs only).
- Every user-visible animation gates on the system animator-scale setting.
