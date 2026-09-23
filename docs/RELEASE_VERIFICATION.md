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

## Milestone 2: provenance + ledger

Release provenance pairs naturally with the existing hash-chained audit ledger. The next release-hardening increment should publish a signed SLSA provenance attestation alongside `mobet.apk`, binding the artifact digest to the immutable tag, workflow, repository, and builder identity. The release verification then checks both the APK digest and the attestation before installation; the on-device ledger records the verification result and digest so the supply-chain claim and runtime evidence share one auditable trail.
