# Ledger evidence export and independent verification

Mobet can export the audit ledger as a redacted `mobet.ledger.v1` JSON evidence bundle.
Open **Inspect & verify → Audit ledger → Export evidence** and choose a local destination or
share target.

The bundle includes:

- Mobet version, source commit, signing classification, policy version, and ledger schema;
- the SHA-256 of the installed APK and embedded capability manifest;
- redacted event text and timestamps;
- the original on-device source chain head and source hashes;
- a portable SHA-256 chain over the exact redacted content in the export;
- explicit declarations that screenshots and secrets are excluded.

## Verify without Mobet

The verifier needs only Python 3 and the standard library:

```sh
python3 scripts/verify-ledger.py mobet-ledger-20260923-120000.json
```

A valid bundle prints `VERIFIED Mobet ledger bundle` and exits with status 0. A malformed,
tampered, privacy-unsafe, source-unverified, or unsupported bundle prints each failure and exits
with status 1.

The verifier checks schema and field types, build digests, sequence continuity, every exported
hash link, source-chain topology, both chain heads, the export genesis binding, redaction counts,
privacy declarations, and common unredacted credential patterns. Input is capped at 10 MiB and
100,000 entries.

## Why the export has two chains

The on-device ledger hash commits to the original event text. If an old or malformed event
contains a token, email address, or other sensitive value, Mobet must remove it before export.
After redaction, the original source hash cannot be recomputed from the exported text—and claiming
otherwise would be false.

The bundle therefore preserves the original `sourceHead`, `sourceHash`, and `sourcePrev` values as
provenance while creating a second chain over exactly the redacted events in the file. The export
genesis is bound to the opaque installation-local run ID and source head. The standalone verifier
proves that the portable evidence has not changed since export and reports whether Mobet verified
the source ledger at export time.

The opaque `deviceRun` value is randomly generated and encrypted on-device. It is not an Android
ID, hardware serial, account identifier, or other device fingerprint.
