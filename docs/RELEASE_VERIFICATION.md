# Release verification

## How to verify a release yourself (v1.0.0 onward)

One command, run from a clone of this repository:

```sh
bash scripts/verify-release-apk.sh --tag v1.0.0 --expect-cert <signer-fingerprint>
```

The fingerprint is published in [RELEASE_SIGNING.md](RELEASE_SIGNING.md). Omit `--expect-cert`
and the script falls back to comparing against the release's own `mobet-signing.json`, which
detects corruption but not a maintainer-side key substitution — pin the fingerprint if you can.

It downloads the assets with `gh` (or use `--dir` on a directory you downloaded by hand) and
recomputes every published claim locally:

| Check | What a PASS means |
| --- | --- |
| Archive integrity | The APK is a complete, well-formed zip — not a truncated download. |
| Published digest | The bytes hash to the value in `mobet.apk.sha256`. |
| Signature | `apksigner` accepts it, APK Signature Scheme v2 is present, the signer is **not** `CN=Android Debug`, and the certificate matches the pin. |
| Capability manifest | The sidecar binds this exact APK, declares `release`/`release`, matches the copy embedded in the APK, and its `manifestSha256` recomputes. |
| Evidence bundle | `mobet-evidence.zip` matches its sidecar digest, binds this APK digest, and declares no screenshots or secrets. |
| Reproducibility | `mobet-reproducibility.json` references this APK and reports the independent rebuild as reproducible. |
| Sigstore provenance | `mobet.sigstore.json` carries a signature envelope and verification material, its attested subject digest equals this APK, and `gh attestation verify` accepts it. |

Checks whose tooling is absent print `SKIP`, not `PASS`. A `SKIP` on the signature row means the
signature was **not** verified — install `apksigner` from the Android SDK build-tools and re-run
before trusting the APK.

Manual equivalents, if you prefer not to run the script:

```sh
sha256sum mobet.apk                       # compare with mobet.apk.sha256 and the release notes
apksigner verify --print-certs -v mobet.apk
gh attestation verify mobet.apk --repo o48902808-creator/mobet --bundle mobet.sigstore.json
```

On the device itself, Mobet's **Verify SLSA provenance** command checks the same
`mobet.sigstore.json` offline against its pinned trust root — see
[SLSA_VERIFICATION.md](SLSA_VERIFICATION.md).

---

## v0.7.0 release verification (historical)

Checked 2026-09-23 from a clean session using read-only GitHub queries.

## Phone-side completion checks

- **Branch protection:** the repository API returned `403 Resource not accessible by integration` for the read-only protection query. This is an authentication/permission limitation, not evidence that protection is absent; the settings page or an admin-scoped `gh` session must be used to confirm it.
- **Pin dependency checksums:** the `Pin dependency checksums` workflow is present and active. No checksum-pinning pull request is visible in the repository's current PR list, so this item remains unconfirmed until the generated PR is visible (or its merge commit is supplied).
- **Release:** `v0.7.0` exists and is marked Latest, published 2026-09-20, with the `mobet.apk` asset attached. The release metadata records SHA-256 `df8b8895bf5b3aee6716ada5ae920ecd3036f0794a49632fe38813f094d744f6`.

## APK smoke check

The release asset metadata is healthy (`uploaded`, `application/vnd.android.package-archive`, 49,913,426 bytes). A direct asset download was attempted three times, but the GitHub release-assets endpoint closed each transfer with `EOF` in this environment. Therefore archive/install validation and an independently downloaded hash are **blocked**, not passed. Re-run:

```sh
tmp=$(mktemp -d)
gh release download v0.7.0 -p mobet.apk -D "$tmp"
unzip -tq "$tmp/mobet.apk"
sha256sum "$tmp/mobet.apk" # should match the hash above
```

## Current provenance + ledger path

Release CI now publishes `mobet.sigstore.json`, the exact Sigstore bundle emitted by GitHub's
artifact-attestation action for `mobet.apk`. The app verifies that bundle offline against its pinned
Sigstore trust root and records a digest-only success receipt in the local ledger. Portable ledger
exports distinguish `capabilityVerified` (embedded manifest/package consistency) from
`provenanceVerifiedOnDevice` (a successful cryptographic bundle check for the same installed APK).
The standalone ledger verifier treats the latter as a reported boolean, not independent proof; an
independent reviewer must verify the accompanying Sigstore bundle against the exported APK digest.

## v1.0.0 — production signing (status)

v1.0.0 is the first release built by the signing-enabled workflow: `assembleRelease` with a
keystore from repository secrets, an `apksigner` gate that rejects unsigned and debug-signed
artifacts, and a published `mobet-signing.json`. Recording the outcome here is part of cutting
the release — paste the `scripts/verify-release-apk.sh` output and the signer fingerprint once
the run completes, the same way the v0.7.0 section records what was and was not confirmed.
