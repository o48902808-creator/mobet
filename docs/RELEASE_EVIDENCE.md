# Release and device-test evidence

The release workflow produces `mobet-evidence.zip`, a deterministic evidence envelope containing
the APK digest, source commit/workflow identity, embedded-manifest digest, attestation reference,
capability invariants, test-report digests, and optional redacted-ledger chain heads. It explicitly
records that screenshots and secrets are absent. The bundle does not duplicate the APK.

`scripts/build-evidence-bundle.py` fails if the external capability manifest's APK digest differs
from the supplied APK. Test artifacts are represented by relative path, byte count, and SHA-256 so
independent reviewers can match device-lab output without receiving screen content.

Android CI creates `device-lab-evidence-api29` after successful connected tests. The deterministic
envelope binds the exact emulator-built APK, pinned capability identity, API 29/x86_64 environment,
and every connected-test report digest without embedding the reports, screenshots, or screen data.
A SHA-256 sidecar accompanies it.

Release CI also performs a clean second build and publishes `mobet-reproducibility.json` with both
APK hashes and an honest `reproducible` or `non-reproducible` result. A mismatch is reported rather
than hidden; it does not replace the separately attested first artifact. The evidence zip, its
SHA-256 sidecar, capability manifest, reproducibility report, APK digest, and APK are release
assets.
