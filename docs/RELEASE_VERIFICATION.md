# v0.7.0 release verification

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
