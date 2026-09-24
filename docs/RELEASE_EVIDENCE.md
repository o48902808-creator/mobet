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

Release CI also publishes `mobet-signing.json` (`mobet.signing.v1`): the signer certificate's
SHA-256 and SHA-1 digests, the distinguished name, which signature schemes are present, and the
SDK range verified. It is produced by parsing `apksigner verify --print-certs` output against the
finished artifact, not by restating build configuration, and the publish job refuses to create a
release unless it attests a signed, non-debug APK. Users pin the fingerprint it carries; see
`docs/RELEASE_SIGNING.md`.

Release CI also performs a clean second build and publishes `mobet-reproducibility.json`
(`mobet.reproducibility.v3`). Because Mobet builds unsigned and signs in a separate job, the
rebuild is measured on **unsigned** artifacts: `firstSha256` and `secondSha256` are whole-file
digests of two independent builds of the same commit, which any third party can reproduce
without holding the signing key. `contentSha256` digests every APK entry except the v1 signature
files (`META-INF/MANIFEST.MF`, `*.SF`, `*.RSA`, `*.DSA`, `*.EC`), so it survives signing and ties
the published signed APK back to that rebuild; `signedSha256` records the signed artifact. The
sign job refuses to attest anything whose payload digest moved during signing, and
`scripts/apk-content-digest.py` is the single implementation of that measurement. A
non-reproducible rebuild is reported honestly and raised as a workflow warning rather than
hidden.

`scripts/verify-release-apk.sh` re-checks all of this from the downloaded bytes alone: archive
integrity, published digest, `apksigner` signature and debug-key rejection, fingerprint pinning,
capability manifest self-consistency and embedded/sidecar agreement, evidence-bundle binding and
privacy flags, reproducibility status, and the Sigstore bundle's attested subject. Unavailable
tools are reported as `SKIP` — never silently counted as passes.

The evidence zip, its SHA-256 sidecar, capability manifest, signing report, reproducibility report,
Sigstore bundle, APK digest, and APK are release assets.
