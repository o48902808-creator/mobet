#!/usr/bin/env bash
#
# Assert that an APK carries a production signature, and emit mobet-signing.json.
#
# This is the load-bearing release gate: it inspects the finished artifact rather than the build
# configuration that produced it, so it holds even when the Gradle setup changes in ways the
# earlier gates do not anticipate. It lives in a script rather than inline in release.yml so it
# can be tested against recorded apksigner output (scripts/test_release_gates.py) instead of only
# being exercised for the first time during a real release.
#
# Fails when the APK is unsigned, lacks APK Signature Scheme v2, is signed with the Android debug
# certificate, or is signed with a certificate other than the pinned one.
#
#   scripts/assert-production-signature.sh --apk mobet.apk --output mobet-signing.json \
#       [--expect-cert <sha256>] [--min-sdk 26] [--max-sdk 35]
#   scripts/assert-production-signature.sh --apksigner-output recorded.txt --output /dev/stdout
#
set -euo pipefail

APK=""
OUTPUT="mobet-signing.json"
EXPECT_CERT=""
RECORDED=""
MIN_SDK=26
MAX_SDK=35

while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK="$2"; shift 2 ;;
    --output) OUTPUT="$2"; shift 2 ;;
    --expect-cert) EXPECT_CERT="$2"; shift 2 ;;
    --apksigner-output) RECORDED="$2"; shift 2 ;;   # test seam: parse recorded output
    --min-sdk) MIN_SDK="$2"; shift 2 ;;
    --max-sdk) MAX_SDK="$2"; shift 2 ;;
    -h|--help) sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

# GitHub Actions renders ::error:: as an annotation; plain runs just see the text.
err() { echo "::error::$*" >&2; }

if [ -n "$RECORDED" ]; then
  VERIFY_STATUS=0
  VERIFY_OUTPUT="$(cat "$RECORDED")"
else
  [ -n "$APK" ] || { echo "--apk or --apksigner-output is required" >&2; exit 2; }
  [ -s "$APK" ] || { err "APK not found or empty: $APK"; exit 1; }
  APKSIGNER="${APKSIGNER:-$(command -v apksigner || true)}"
  if [ -z "$APKSIGNER" ] && [ -n "${ANDROID_HOME:-}" ]; then
    APKSIGNER="$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"
  fi
  if [ -z "$APKSIGNER" ]; then
    err "apksigner not found. The signature gate cannot be skipped; install Android SDK build-tools."
    exit 1
  fi
  echo "Using $APKSIGNER" >&2
  # --min-sdk-version matches app/build.gradle.kts minSdk, so a v1-only signature cannot pass
  # by being adequate for some narrower SDK range than the one we actually ship to.
  set +e
  VERIFY_OUTPUT="$("$APKSIGNER" verify --verbose --print-certs \
    --min-sdk-version "$MIN_SDK" --max-sdk-version "$MAX_SDK" "$APK" 2>&1)"
  VERIFY_STATUS=$?
  set -e
fi

printf '%s\n' "$VERIFY_OUTPUT"

if [ "$VERIFY_STATUS" -ne 0 ]; then
  err "apksigner rejected the APK (exit $VERIFY_STATUS). Refusing to publish."
  exit 1
fi

scheme() { printf '%s\n' "$VERIFY_OUTPUT" | grep -qi "Verified using $1 scheme.*: true"; }
field() { printf '%s\n' "$VERIFY_OUTPUT" | sed -n "s/^Signer #1 certificate $1: //p" | head -1; }
norm() { printf '%s' "$1" | tr -d ': ' | tr 'A-F' 'a-f'; }

V1=false; V2=false; V3=false
scheme "v1" && V1=true
scheme "v2" && V2=true
scheme "v3" && V3=true

if [ "$V2" != true ]; then
  err "APK is not signed with APK Signature Scheme v2 (whole-file signature). Refusing to publish."
  exit 1
fi

SIGNER_DN="$(field "DN")"
CERT_SHA256="$(norm "$(field "SHA-256 digest")")"
CERT_SHA1="$(norm "$(field "SHA-1 digest")")"

if [ -z "$SIGNER_DN" ] || [ -z "$CERT_SHA256" ]; then
  err "Could not read the signer certificate from apksigner output. Refusing to publish."
  exit 1
fi

echo "Signer DN:    $SIGNER_DN" >&2
echo "Cert SHA-256: $CERT_SHA256" >&2

# The Android debug identity is fixed and universally known ("CN=Android Debug, O=Android, C=US"),
# and its keystore password is public. An APK carrying it can be "updated" by anyone.
if printf '%s' "$SIGNER_DN" | grep -qi 'CN=Android Debug'; then
  err "APK is signed with the ANDROID DEBUG certificate ($SIGNER_DN). Refusing to publish."
  exit 1
fi

MULTI="$(printf '%s\n' "$VERIFY_OUTPUT" | sed -n 's/^Number of signers: //p' | head -1)"
if [ -n "$MULTI" ] && [ "$MULTI" != "1" ]; then
  err "Expected exactly one signer, found $MULTI. Refusing to publish."
  exit 1
fi

PINNED=false
if [ -n "$EXPECT_CERT" ]; then
  WANT="$(norm "$EXPECT_CERT")"
  if [ "$WANT" != "$CERT_SHA256" ]; then
    err "Signer certificate $CERT_SHA256 does not match the pinned fingerprint $WANT. Refusing to publish."
    exit 1
  fi
  PINNED=true
  echo "Signer certificate matches the pinned fingerprint." >&2
else
  echo "::warning::MOBET_SIGNING_CERT_SHA256 is not set; the signer certificate was not pinned. Set it to $CERT_SHA256 to detect future key substitution."
fi

python3 - "$SIGNER_DN" "$CERT_SHA256" "$CERT_SHA1" "$V1" "$V2" "$V3" "$PINNED" "$MIN_SDK" "$MAX_SDK" "$OUTPUT" <<'PYTHON'
import json, sys
(dn, sha256, sha1, v1, v2, v3, pinned, min_sdk, max_sdk, output) = sys.argv[1:11]
b = lambda s: s == "true"
report = {
    "schema": "mobet.signing.v1",
    "artifact": "mobet.apk",
    "signed": True,
    "debugKey": False,
    "schemes": {"v1": b(v1), "v2": b(v2), "v3": b(v3)},
    "signerDn": dn,
    "certSha256": sha256,
    "certSha1": sha1,
    "fingerprintPinned": b(pinned),
    "verifiedWith": "apksigner (Android SDK build-tools)",
    "minSdkVerified": int(min_sdk),
    "maxSdkVerified": int(max_sdk),
}
text = json.dumps(report, indent=2) + "\n"
if output == "/dev/stdout":
    sys.stdout.write(text)
else:
    open(output, "w").write(text)
PYTHON
