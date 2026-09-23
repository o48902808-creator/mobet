# Offline SLSA and Sigstore verification

Mobet's **Verify SLSA provenance** command accepts the `mobet.sigstore.json` release asset selected
through Android's document picker. Release CI copies the exact bundle emitted by
`actions/attest-build-provenance` into every GitHub release. Evidence is capped at 1 MiB while streaming and is never treated as a workflow.
The app has no network permission.

Verification uses `sigstore-java` with the source-controlled
`assets/sigstore-trusted-root.json`. It does not use `sigstorePublicDefaults()`, update TUF, or call
Fulcio, Rekor, or GitHub. The verifier requires all of the following before displaying success:

- a valid Fulcio certificate chain under the pinned Sigstore public-good root;
- certificate-transparency and Rekor evidence accepted by the Sigstore verifier;
- a valid DSSE signature;
- exact OIDC issuer `https://token.actions.githubusercontent.com`;
- exact workflow certificate identity from the embedded capability manifest;
- an in-toto Statement v1 carrying a SLSA provenance v1 predicate;
- exactly one `mobet.apk` subject whose SHA-256 matches the installed APK bytes;
- the expected repository, release workflow path, source commit, and GitHub-hosted builder.

Parsing errors, unsupported bundles, stale/inapplicable roots, identity mismatch, unavailable build
integrity, and malformed SLSA claims all fail closed. The ordinary Build integrity screen is
careful to report capability/package consistency only; it does not call an unsigned embedded
manifest “provenance verified.”

The pinned root was obtained from `sigstore/root-signing` at
`targets/trusted_root.json`. Updating it is a security-sensitive source change and must be reviewed
alongside representative old and new attestation bundles.
