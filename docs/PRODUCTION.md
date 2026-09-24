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

**Prerequisite (one time):** the `release` environment must exist, carry the four signing
secrets, and restrict deployments to `v*` tags with a required reviewer — otherwise the sign
job fails by design. See [`docs/RELEASE_SIGNING.md`](RELEASE_SIGNING.md).

The APK asset is built by *Actions → Release APK → Run workflow* (or by pushing a
`v*` tag). The current line is **1.0.0 / versionCode 10** — the first production-signed
release, which is why it takes the 1.0.0 tag rather than 0.9.0:

```
workflow_dispatch: Release APK
  tag:   v1.0.0
  title: v1.0.0
```

The tag does not need to exist beforehand: the publish job creates it at `--target` the
built commit. Pushing a `v*` tag yourself triggers the same workflow.

Three jobs with disjoint privileges. **build** runs `test assembleRelease` with no secrets and
no write token, producing an *unsigned* APK plus the evidence bundle and a clean-rebuild
reproducibility report (measured on unsigned artifacts, so anyone can reproduce the number).
**sign** — gated on the `release` environment, running no project build code — zipaligns, signs
with apksigner, asserts the signature is production and not the debug key, confirms signing did
not alter a single byte of the built payload, and attests the result with Sigstore. **publish**
holds `contents: write` and no secrets; it re-verifies the checksum and the signing evidence
before creating the GitHub Release. The `releases/latest/download/mobet.apk` URL above then
rotates to it automatically.

Release assets: `mobet.apk`, `mobet.apk.sha256`, `mobet-capability.json`,
`mobet-signing.json`, `mobet-evidence.zip` (+ `.sha256`), `mobet-reproducibility.json`,
`mobet.sigstore.json`.

After the run, validate it independently rather than reading the log:

```sh
bash scripts/verify-release-apk.sh --tag v1.0.0 --expect-cert <signer-fingerprint>
```

## 4. Signing and what it means for upgrades (read before shipping)

From **v1.0.0** releases are signed with a managed production key held in GitHub Actions
secrets ([`docs/RELEASE_SIGNING.md`](RELEASE_SIGNING.md)). Up to and including v0.8.0 they
were debug-signed with a per-build-host key. Consequences:

- **The v0.8.0 → v1.0.0 upgrade requires an uninstall.** The signature changes, so Android
  reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Because `android:allowBackup="false"` is
  deliberate (workflow JSON may reference secret names), uninstalling **destroys the saved
  workflow library**. Tell users in the release notes to export their library (overflow menu
  → *Export library*) first and re-import after. The workflow's generated notes already say
  this.
- **From v1.0.0 onward, updates install in place** — same key, same identity, no uninstall,
  library preserved.
- The key is now a top-tier secret. An in-place update inherits Mobet's accessibility grant
  without re-consent, so key compromise is equivalent to handing over every user's screen.
  Rotation is an emergency procedure, not maintenance; see the signing doc.

There is no debug-signing fallback left in the release path. Four gates enforce it (secrets
preflight, `MOBET_REQUIRE_RELEASE_SIGNING` in Gradle, no `signingConfigs["debug"]` assignment
anywhere, and an independent `apksigner` check that rejects `CN=Android Debug` and pins the
fingerprint). The last one validates the artifact rather than the intent, which is why it is
the one that actually holds.

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
