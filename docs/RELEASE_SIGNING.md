# Release signing

Mobet releases from v1.0.0 onward are **production-signed** with a single, long-lived key held
by the maintainer and loaded into CI as GitHub Actions secrets. Releases up to and including
v0.8.0 were debug-signed.

This matters more for Mobet than for a typical app. Mobet holds an accessibility service that
can read and drive every screen on the device. Android grants an in-place update the *same*
identity as the app it replaces — no re-consent, no re-grant. The signing key is therefore the
only thing standing between a user's granted accessibility service and anyone who can get an
APK in front of them. A debug key is generated per build host and its password is public
(`android`), so a debug-signed Mobet is, in the precise sense, an app anyone can update.

## The key

| Property | Value |
| --- | --- |
| Type | PKCS#12, RSA 4096, SHA256withRSA |
| Validity | 30 years (a key must outlive the app; expiry breaks the upgrade path) |
| Alias | `mobet-release` |
| Signature schemes | v1 + v2 + v3, all enabled and asserted in CI |
| Certificate SHA-256 | _publish the fingerprint here after generating the key_ |

Fill in the fingerprint once the key exists. It is the value users pin when verifying a
download, and the value CI compares against on every release.

## One-time setup

### 1. Generate the keystore on a machine you control

```sh
bash scripts/create-release-keystore.sh --out ~/keys/mobet-release.p12
```

The script refuses to write inside a git work tree, refuses to overwrite an existing keystore,
demands a 12+ character password, and prints the certificate fingerprint plus the exact
`gh secret set` commands. **Never** generate the key in CI, in a sandbox, in a chat session, or
anywhere the private key transits a third party.

Back up the keystore file *and* its password to two offline locations before continuing:

- **Lose the key** → no future release can ever update an installed Mobet. Every user must
  uninstall (destroying their workflow library, since `allowBackup=false`) and reinstall.
- **Leak the key** → an attacker can publish an update that Android installs in place over a
  running Mobet, inheriting its accessibility grant. This is the worst failure in the project.

### 2. Create the `release` environment and protect it

*Settings → Environments → New environment → `release`*, then set its protection rules:

- **Deployment branches and tags:** selected refs only — `v*`. The signing job then cannot run
  from an arbitrary branch.
- **Required reviewers:** yourself. Releasing the key becomes an explicit, logged approval.

This repository is **public**, which makes the rule matter: anyone can open a PR, and while
secrets are never exposed to fork PRs, a repository with more than one collaborator otherwise
allows a branch that rewrites `release.yml` to print the keystore. Environment protection turns
that into something that needs an approval you would notice.

### 3. Load the secrets onto that environment

*Settings → Environments → release → Environment secrets*, or `gh secret set --env release` with
an admin-scoped login (a CI token cannot write secrets). Environment-scoped, not repository-wide,
so no other workflow in the repository can read them:

| Environment secret | Contents | Required |
| --- | --- | --- |
| `MOBET_KEYSTORE_BASE64` | `base64 -w0` of the keystore file | yes |
| `MOBET_KEYSTORE_PASSWORD` | keystore password | yes |
| `MOBET_KEY_ALIAS` | `mobet-release` | yes |
| `MOBET_KEY_PASSWORD` | private-key password (same as the store password for PKCS#12) | yes |
| `MOBET_SIGNING_CERT_SHA256` | expected signer certificate fingerprint | strongly recommended |

`MOBET_SIGNING_CERT_SHA256` is the pin. With it set, a release whose APK is signed by *any*
other certificate fails the build — which is what catches a swapped secret, a wrong alias in a
multi-key keystore, or an attacker who can write secrets but not this one. Without it CI prints
a warning and the fingerprint it observed, so the pin can be added afterwards.

Then delete the base64 copy: `shred -u ~/keys/mobet-release.p12.base64`.

### 4. Verify the wiring before announcing anything

Run *Actions → Release APK* against a throwaway tag (e.g. `v1.0.0-rc1`), then:

```sh
bash scripts/verify-release-apk.sh --tag v1.0.0-rc1 --expect-cert <fingerprint>
gh release delete v1.0.0-rc1 --repo o48902808-creator/mobet --cleanup-tag
```

Any tag containing a hyphen is treated as a semver pre-release: the workflow publishes it with
`--prerelease --latest=false`, so a dry run cannot rotate
`releases/latest/download/mobet.apk` — the documented install path — to a release candidate.

This is cheaper than discovering a wrong `MOBET_KEY_ALIAS` on the real tag. The parts of the
pipeline that do not need the secrets are already covered by `scripts/test_release_gates.py`,
which runs on every PR and exercises the gate against recorded apksigner transcripts
(production, debug key, v1-only, fingerprint mismatch, multiple signers, unreadable
certificate).

## Why the key never meets project build code

The pipeline is three jobs with disjoint privileges, because a public repository's release
pipeline should not hand the signing key to everything in its dependency graph:

| Job | Runs | Holds | Cannot |
| --- | --- | --- | --- |
| `build` | Gradle, AGP, 9 third-party dependencies | nothing | sign, publish |
| `sign` | apksigner/zipalign + this repo's stdlib Python | the signing key (via the `release` environment) | build project code |
| `publish` | `gh release create` | `contents: write` | see the key, run project code |

`build` therefore produces an **unsigned** APK. A malicious dependency executing there has no
key to steal and no write token to abuse — which is the same reasoning the repository already
applies to `android-ci.yml`, extended to the one job that previously would have broken it.

Two properties fall out of the split:

- **Reproducibility becomes checkable by strangers.** The rebuild is measured on unsigned
  artifacts, which are byte-comparable by anyone; nobody needs the key to confirm the number.
- **Signing is provably payload-preserving.** `scripts/apk-content-digest.py` digests every APK
  entry except the signature files, so the signed artifact can be tied back to the independently
  rebuilt payload. The sign job asserts this before attesting, and
  `scripts/verify-release-apk.sh` re-checks it from the downloaded bytes.

## How the pipeline refuses to ship a debug-signed APK

Four independent gates, in order — any one of them failing stops the release:

1. **Gradle cannot sign at all in CI.** The build job has no keystore, so AGP has no signing
   config to apply. The `release` build type is assigned the real config or `null` — never
   `signingConfigs["debug"]` — so there is no code path from "no key" to "debug key".
2. **Secrets preflight** in the sign job fails with an explicit message if any of the four
   secrets is missing, rather than proceeding toward an unsigned artifact.
3. **Explicit signing flags.** `apksigner sign` is invoked with the key from the environment and
   `--v1/--v2/--v3-signing-enabled true`; there is no fallback keystore to fall back to.
4. **Independent verification.** `scripts/assert-production-signature.sh` runs `apksigner verify
   --print-certs` against the finished APK and fails if the signature is absent, if APK Signature
   Scheme v2 is missing, if there is more than one signer, if the signer DN contains
   `CN=Android Debug`, or if the fingerprint differs from `MOBET_SIGNING_CERT_SHA256`. Its
   findings are published as `mobet-signing.json`, and the publish job re-reads that file and
   refuses to create the release unless it attests a signed, non-debug artifact.

Gate 4 is the load-bearing one: it validates the artifact rather than the intent, so it holds
even if the build configuration changes in ways gates 1–3 do not anticipate. It is covered by
`scripts/test_release_gates.py` against recorded apksigner transcripts — including the case where
the pin is set *to* the debug certificate, which must still be rejected.

For local development `app/build.gradle.kts` still accepts `MOBET_KEYSTORE_*` from the
environment or `local.properties`, and `MOBET_REQUIRE_RELEASE_SIGNING=true` makes an incomplete
setup fail loudly instead of silently producing an unsigned APK.

## Key handling in CI

- The keystore is decoded to `$RUNNER_TEMP/signing/` with mode `600` — never into the workspace,
  where it could be swept into an artifact or the APK.
- An `if: always()` step shreds it, so the key does not survive a failed run either.
- The sign job checks out `scripts/` only (sparse checkout) and never runs Gradle.
- Passwords reach apksigner as `env:` references, so they never appear in the process table.
- Secrets are referenced only as `env:` on the specific steps that need them, never job-wide.
- The certificate DN carries the project identity only. It is embedded in every published APK
  and is world-readable, so it should not contain a personal name or address.

## Rotation

Rotation is not a routine operation: a new key means a new app identity, so **every installed
copy must be uninstalled and reinstalled**, destroying local workflow libraries. Only rotate if
the key is believed compromised. If you must:

1. Generate the new key, update all five secrets, and update the fingerprint in this document.
2. Release under a new major version and state the required uninstall in the release notes.
3. Consider Android key rotation via APK Signature Scheme v3 lineage (`apksigner rotate`) — it
   preserves in-place updates, but only for devices on Android 9+, and the lineage file then
   becomes as security-critical as the key itself.
