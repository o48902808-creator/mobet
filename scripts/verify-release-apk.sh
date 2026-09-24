#!/usr/bin/env bash
#
# Independently validate a published Mobet release: the APK, its signature, the evidence bundle,
# the reproducibility report, and the Sigstore provenance bundle.
#
# Nothing here trusts the release notes or the CI logs. Every claim is recomputed locally from
# the downloaded bytes.
#
#   bash scripts/verify-release-apk.sh --tag v1.0.0
#   bash scripts/verify-release-apk.sh --dir ./downloads --expect-cert <sha256>
#
# Requires: python3, sha256sum (or shasum), unzip. apksigner (Android SDK build-tools) and gh are
# used when available; the script reports each check as PASS, FAIL, or SKIPPED and never pretends
# a missing tool is a passing check.
#
set -uo pipefail

REPO="o48902808-creator/mobet"
TAG=""
DIR=""
EXPECT_CERT=""
FAILURES=0
SKIPS=0

while [ $# -gt 0 ]; do
  case "$1" in
    --tag) TAG="$2"; shift 2 ;;
    --dir) DIR="$2"; shift 2 ;;
    --repo) REPO="$2"; shift 2 ;;
    --expect-cert) EXPECT_CERT="$(printf '%s' "$2" | tr -d ': ' | tr 'A-F' 'a-f')"; shift 2 ;;
    -h|--help) sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

pass() { printf '  PASS  %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
skip() { printf '  SKIP  %s\n' "$1"; SKIPS=$((SKIPS + 1)); }
head2() { printf '\n== %s\n' "$1"; }

sha256() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | awk '{print $1}';
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}

if [ -z "$DIR" ]; then
  [ -n "$TAG" ] || { echo "Pass --tag <tag> or --dir <directory>." >&2; exit 2; }
  command -v gh >/dev/null || { echo "gh is required to download a release; use --dir instead." >&2; exit 2; }
  DIR="$(mktemp -d)"
  echo "Downloading $TAG assets into $DIR"
  gh release download "$TAG" --repo "$REPO" --dir "$DIR" \
    -p 'mobet.apk' -p 'mobet.apk.sha256' -p 'mobet-capability.json' -p 'mobet-signing.json' \
    -p 'mobet-evidence.zip' -p 'mobet-evidence.zip.sha256' -p 'mobet-reproducibility.json' \
    -p 'mobet.sigstore.json' || { echo "Download failed." >&2; exit 1; }
fi

cd "$DIR" || exit 2
APK="mobet.apk"
[ -s "$APK" ] || { echo "No mobet.apk in $DIR" >&2; exit 1; }
APK_SHA="$(sha256 "$APK")"
echo "Verifying $DIR/$APK"
echo "SHA-256: $APK_SHA"

head2 "Archive integrity"
if command -v unzip >/dev/null; then
  if unzip -tq "$APK" >/dev/null 2>&1; then pass "APK is a well-formed, non-truncated zip"
  else fail "APK archive is corrupt or truncated"; fi
else
  python3 - "$APK" <<'PY' && pass "APK is a well-formed zip (python zipfile)" || fail "APK archive is corrupt"
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
sys.exit(1 if z.testzip() is not None else 0)
PY
fi

head2 "Published digest"
if [ -s mobet.apk.sha256 ]; then
  EXPECTED="$(awk '{print $1}' mobet.apk.sha256)"
  [ "$EXPECTED" = "$APK_SHA" ] && pass "APK matches mobet.apk.sha256" \
    || fail "APK digest $APK_SHA != published $EXPECTED"
else
  skip "mobet.apk.sha256 not present"
fi

head2 "Signature"
APKSIGNER="$(command -v apksigner || true)"
if [ -z "$APKSIGNER" ] && [ -n "${ANDROID_HOME:-}" ]; then
  APKSIGNER="$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"
fi
if [ -n "$APKSIGNER" ]; then
  if OUTPUT="$("$APKSIGNER" verify --verbose --print-certs --min-sdk-version 26 "$APK" 2>&1)"; then
    pass "apksigner verifies the APK signature"
    printf '%s\n' "$OUTPUT" | grep -q "v2 scheme (APK Signature Scheme v2): true" \
      && pass "APK Signature Scheme v2 present" || fail "no v2 signature"
    DN="$(printf '%s\n' "$OUTPUT" | sed -n 's/^Signer #1 certificate DN: //p' | head -1)"
    CERT="$(printf '%s\n' "$OUTPUT" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1 | tr -d ': ' | tr 'A-F' 'a-f')"
    echo "        signer DN:   $DN"
    echo "        cert sha256: $CERT"
    if printf '%s' "$DN" | grep -qi 'CN=Android Debug'; then
      fail "signed with the ANDROID DEBUG KEY — this is not a production release"
    else
      pass "not signed with the Android debug key"
    fi
    if [ -n "$EXPECT_CERT" ]; then
      [ "$CERT" = "$EXPECT_CERT" ] && pass "signer certificate matches the pinned fingerprint" \
        || fail "signer certificate $CERT != pinned $EXPECT_CERT"
    elif [ -s mobet-signing.json ]; then
      CLAIMED="$(python3 -c 'import json;print(json.load(open("mobet-signing.json")).get("certSha256",""))')"
      [ "$CERT" = "$CLAIMED" ] && pass "signer certificate matches mobet-signing.json" \
        || fail "signer certificate $CERT != mobet-signing.json $CLAIMED"
    else
      skip "no expected fingerprint supplied (pass --expect-cert to pin)"
    fi
  else
    fail "apksigner rejected the APK:"
    printf '%s\n' "$OUTPUT" | sed 's/^/        /'
  fi
else
  skip "apksigner not found (install Android SDK build-tools) — signature NOT verified"
fi

head2 "Capability manifest"
if [ -s mobet-capability.json ]; then
  python3 - "$APK_SHA" <<'PY'
import hashlib, json, sys, zipfile
sidecar = json.load(open("mobet-capability.json"))
ok = True
if sidecar.get("sha256") != sys.argv[1]:
    print(f"  FAIL  capability sidecar sha256 {sidecar.get('sha256')} != APK {sys.argv[1]}"); ok = False
else:
    print("  PASS  capability sidecar binds this exact APK")
for field, want in (("buildType", "release"), ("expectedSigning", "release")):
    got = sidecar.get(field)
    print(f"  {'PASS' if got == want else 'FAIL'}  capability {field} = {got!r} (want {want!r})")
    ok &= got == want
# The embedded copy inside the APK must be the same manifest, minus the recursive digest fields.
with zipfile.ZipFile("mobet.apk") as z:
    embedded = json.loads(z.read("assets/mobet-capability.json"))
canonical = {k: v for k, v in sidecar.items() if k not in ("artifact", "sha256")}
print(f"  {'PASS' if embedded == canonical else 'FAIL'}  embedded manifest matches the sidecar")
ok &= embedded == canonical
recomputed = hashlib.sha256(json.dumps(
    {k: v for k, v in embedded.items() if k != "manifestSha256"},
    sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()
same = recomputed == embedded.get("manifestSha256")
print(f"  {'PASS' if same else 'FAIL'}  embedded manifestSha256 is self-consistent")
sys.exit(0 if (ok and same) else 1)
PY
  [ $? -eq 0 ] || FAILURES=$((FAILURES + 1))
else
  skip "mobet-capability.json not present"
fi

head2 "Evidence bundle"
if [ -s mobet-evidence.zip ]; then
  if [ -s mobet-evidence.zip.sha256 ]; then
    WANT="$(awk '{print $1}' mobet-evidence.zip.sha256)"
    GOT="$(sha256 mobet-evidence.zip)"
    [ "$WANT" = "$GOT" ] && pass "evidence bundle matches its sidecar digest" \
      || fail "evidence bundle digest $GOT != $WANT"
  else
    skip "mobet-evidence.zip.sha256 not present"
  fi
  python3 <<'PYEV'
import json, sys, zipfile
e = json.loads(zipfile.ZipFile("mobet-evidence.zip").read("evidence.json"))
build = e.get("build", {})
# The evidence bundle is produced in the unsigned build job, so it binds the UNSIGNED payload.
# The reproducibility report carries that same digest, which is how the chain closes:
#   evidence -> unsigned APK -> independent rebuild -> content digest -> signed APK (below).
ok = True
try:
    report = json.load(open("mobet-reproducibility.json"))
except FileNotFoundError:
    report = {}
unsigned = report.get("firstSha256")
if unsigned:
    ok = build.get("apkSha256") == unsigned
    print(f"  {'PASS' if ok else 'FAIL'}  evidence binds the unsigned payload the report rebuilds")
else:
    print("  SKIP  no reproducibility report to cross-check the evidence digest against")
privacy = e.get("privacy", {})
clean = privacy.get("screenshotsIncluded") is False and privacy.get("secretsIncluded") is False
print(f"  {'PASS' if clean else 'FAIL'}  evidence declares no screenshots/secrets")
print(f"        commit {build.get('commit')}  tests recorded: {len(e.get('tests', []))}")
sys.exit(0 if ok and clean else 1)
PYEV
  [ $? -eq 0 ] || FAILURES=$((FAILURES + 1))
else
  skip "mobet-evidence.zip not present"
fi

head2 "Reproducibility report"
if [ -s mobet-reproducibility.json ]; then
  python3 - "$APK_SHA" <<'PYREPRO'
import hashlib, json, sys, zipfile

SIGNATURE_SUFFIXES = (".SF", ".RSA", ".DSA", ".EC")


def content_digest(path):
    """Payload digest: every entry except the v1 signature files. Survives signing."""
    h = hashlib.sha256()
    with zipfile.ZipFile(path) as archive:
        for name in sorted(archive.namelist()):
            if name == "META-INF/MANIFEST.MF":
                continue
            if name.startswith("META-INF/") and name.upper().endswith(SIGNATURE_SUFFIXES):
                continue
            h.update(name.encode())
            h.update(b"\0")
            h.update(hashlib.sha256(archive.read(name)).digest())
    return h.hexdigest()


report = json.load(open("mobet-reproducibility.json"))
ok = True
status = report.get("status")
reproducible = status == "reproducible"
print(f"  {'PASS' if reproducible else 'FAIL'}  independent rebuild of the unsigned APK: {status}")
ok &= reproducible

# The load-bearing link: the APK you downloaded must carry exactly the payload that was built
# and independently rebuilt. Signing adds a signature block, so only a content digest can match.
expected = report.get("contentSha256")
if expected:
    actual = content_digest("mobet.apk")
    same = actual == expected
    print(f"  {'PASS' if same else 'FAIL'}  signed APK payload matches the rebuilt payload")
    if not same:
        print(f"        downloaded {actual}")
        print(f"        rebuilt    {expected}")
    ok &= same
else:
    print("  SKIP  report predates contentSha256; payload not tied to the rebuild")

signed = report.get("signedSha256")
if signed:
    match = signed == sys.argv[1]
    print(f"  {'PASS' if match else 'FAIL'}  report records this signed APK digest")
    ok &= match
sys.exit(0 if ok else 1)
PYREPRO
  [ $? -eq 0 ] || FAILURES=$((FAILURES + 1))
else
  skip "mobet-reproducibility.json not present"
fi

head2 "Sigstore provenance (mobet.sigstore.json)"
if [ -s mobet.sigstore.json ]; then
  python3 - "$APK_SHA" <<'PY'
import json, sys
b = json.load(open("mobet.sigstore.json"))
print(f"        mediaType: {b.get('mediaType')}")
has_sig = bool(b.get("dsseEnvelope") or b.get("messageSignature"))
print(f"  {'PASS' if has_sig else 'FAIL'}  bundle carries a signature envelope")
print(f"  {'PASS' if b.get('verificationMaterial') else 'FAIL'}  bundle carries verification material")
digest = sys.argv[1]
found = digest in json.dumps(b)
try:
    import base64
    payload = json.loads(base64.b64decode(b["dsseEnvelope"]["payload"]))
    subjects = [s.get("digest", {}).get("sha256") for s in payload.get("subject", [])]
    found = digest in subjects
    print(f"        attested subjects: {subjects}")
    print(f"        builder: {payload.get('predicate', {}).get('runDetails', {}).get('builder', {}).get('id')}")
except Exception as exc:  # noqa: BLE001 - reported, not swallowed
    print(f"        could not decode DSSE payload: {exc}")
print(f"  {'PASS' if found else 'FAIL'}  attestation subject == this APK digest")
sys.exit(0 if has_sig and found else 1)
PY
  [ $? -eq 0 ] || FAILURES=$((FAILURES + 1))
  if command -v gh >/dev/null; then
    if gh attestation verify "$APK" --repo "$REPO" --bundle mobet.sigstore.json >/dev/null 2>&1; then
      pass "gh attestation verify accepts the bundle offline against $REPO"
    elif gh attestation verify "$APK" --repo "$REPO" >/dev/null 2>&1; then
      pass "gh attestation verify accepts the APK (online lookup)"
    else
      fail "gh attestation verify rejected the APK — run it manually for the reason:"
      echo "        gh attestation verify $APK --repo $REPO --bundle mobet.sigstore.json"
    fi
  else
    skip "gh not installed — cryptographic Sigstore verification not run"
  fi
else
  skip "mobet.sigstore.json not present"
fi

head2 "Result"
if [ "$FAILURES" -eq 0 ] && [ "$SKIPS" -eq 0 ]; then
  echo "  All checks passed."
elif [ "$FAILURES" -eq 0 ]; then
  echo "  No failures, but $SKIPS check(s) were SKIPPED — those properties are unverified."
else
  echo "  $FAILURES check(s) FAILED. Do not install this APK."
fi
exit $(( FAILURES > 0 ? 1 : 0 ))
