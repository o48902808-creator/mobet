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

### 2. Load the secrets

*Settings → Secrets and variables → Actions → New repository secret*, or `gh secret set` with an
admin-scoped login (a CI token cannot write secrets):

| Secret | Contents | Required |
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

### 3. Verify the wiring before announcing anything

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

## How the pipeline refuses to ship a debug-signed APK

Four independent gates, in order — any one of them failing stops the release:

1. **Secrets preflight** (`release.yml`, first step, before any project code runs). Missing any
   of the four required secrets fails the job with an explicit message.
2. **Gradle strict mode.** The release workflow sets `MOBET_REQUIRE_RELEASE_SIGNING=true`;
   `app/build.gradle.kts` then throws if signing material is incomplete, instead of emitting
   `app-release-unsigned.apk`. Locally the flag is unset, so `assembleRelease` still works for
   development and simply produces an unsigned APK.
3. **No fallback path exists.** The `release` build type is assigned either the real signing
   config or `null` — never `signingConfigs["debug"]`. AGP only debug-signs a release variant if
   you assign it that config, and nothing in this repository does. The staging step additionally
   fails if `app-release-unsigned.apk` appears in the output directory.
4. **Independent verification.** `apksigner verify --print-certs` runs against the finished APK
   and fails if the signature is absent, if APK Signature Scheme v2 is missing, if the signer DN
   contains `CN=Android Debug`, or if the fingerprint differs from `MOBET_SIGNING_CERT_SHA256`.
   Its findings are published as `mobet-signing.json`, and the publish job re-reads that file and
   refuses to create the release unless it attests a signed, non-debug artifact.

Gate 4 is the load-bearing one: it validates the artifact rather than the intent, so it holds
even if the Gradle configuration is changed in a way gates 1–3 do not anticipate.

## Key handling in CI

- The keystore is decoded to `$RUNNER_TEMP/signing/` with mode `600` — never into the workspace,
  where it could be swept into an artifact or the APK.
- An `if: always()` step shreds it, so the key does not survive a failed build either.
- The build job has `contents: read` only. The publish job, which holds `contents: write`, never
  sees the signing secrets and runs no project code.
- Secrets are referenced only as `env:` on the specific steps that need them, never job-wide.

## Rotation

Rotation is not a routine operation: a new key means a new app identity, so **every installed
copy must be uninstalled and reinstalled**, destroying local workflow libraries. Only rotate if
the key is believed compromised. If you must:

1. Generate the new key, update all five secrets, and update the fingerprint in this document.
2. Release under a new major version and state the required uninstall in the release notes.
3. Consider Android key rotation via APK Signature Scheme v3 lineage (`apksigner rotate`) — it
   preserves in-place updates, but only for devices on Android 9+, and the lineage file then
   becomes as security-critical as the key itself.
