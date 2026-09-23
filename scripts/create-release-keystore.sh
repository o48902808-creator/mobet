#!/usr/bin/env bash
#
# Create Mobet's production signing keystore and print the exact commands that load it into
# GitHub Actions secrets.
#
# Run this on a machine you control. The private key it generates is the app's identity for its
# entire lifetime: if it leaks, anyone can publish an "update" that Android installs in place over
# a user's Mobet, inheriting its accessibility grant. If it is lost, no future release can ever
# update an installed Mobet again. Back it up offline before you delete the working copy.
#
# The keystore is never written into the repository and this script refuses to write inside a git
# work tree.
#
#   bash scripts/create-release-keystore.sh
#   bash scripts/create-release-keystore.sh --out ~/keys/mobet-release.p12 --alias mobet
#
set -euo pipefail

OUT="${HOME}/mobet-release.p12"
ALIAS="mobet-release"
VALIDITY_DAYS=10950 # 30 years: the key must outlive the app, not the other way round.
DNAME="CN=Mobet, OU=Mobet, O=Mobet, L=Accra, C=GH"

while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="$2"; shift 2 ;;
    --alias) ALIAS="$2"; shift 2 ;;
    --validity) VALIDITY_DAYS="$2"; shift 2 ;;
    --dname) DNAME="$2"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

command -v keytool >/dev/null || { echo "keytool not found — install a JDK 17." >&2; exit 1; }

OUT_DIR="$(cd "$(dirname "$OUT")" && pwd)"
OUT="$OUT_DIR/$(basename "$OUT")"

# Guard against the single worst outcome: committing the key.
if git -C "$OUT_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "Refusing to write the keystore inside a git work tree ($OUT_DIR)." >&2
  echo "Pass --out with a path outside any repository, e.g. --out ~/keys/mobet-release.p12" >&2
  exit 1
fi
if [ -e "$OUT" ]; then
  echo "Refusing to overwrite an existing keystore at $OUT" >&2
  echo "If you are rotating keys deliberately, move the old one aside first — but read" >&2
  echo "docs/RELEASE_SIGNING.md on why rotation forces every user to uninstall." >&2
  exit 1
fi

echo "Creating $OUT (alias: $ALIAS, validity: $VALIDITY_DAYS days)"
echo
read -r -s -p "Choose a keystore password (min 12 chars, store it in a password manager): " PASSWORD
echo
read -r -s -p "Repeat it: " PASSWORD_CONFIRM
echo
[ "$PASSWORD" = "$PASSWORD_CONFIRM" ] || { echo "Passwords do not match." >&2; exit 1; }
[ "${#PASSWORD}" -ge 12 ] || { echo "Password is shorter than 12 characters." >&2; exit 1; }

umask 077
# PKCS#12 with a single key entry: the key password equals the store password, which is the only
# arrangement PKCS#12 really supports and what apksigner/AGP expect.
keytool -genkeypair \
  -keystore "$OUT" -storetype PKCS12 \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -sigalg SHA256withRSA \
  -validity "$VALIDITY_DAYS" \
  -dname "$DNAME" \
  -storepass "$PASSWORD" -keypass "$PASSWORD"
chmod 600 "$OUT"

CERT_SHA256="$(keytool -list -v -keystore "$OUT" -storepass "$PASSWORD" -alias "$ALIAS" \
  | sed -n 's/.*SHA256: *//p' | head -1 | tr -d ': ' | tr 'A-F' 'a-f')"

B64_FILE="$OUT.base64"
base64 -w0 < "$OUT" > "$B64_FILE" 2>/dev/null || base64 < "$OUT" | tr -d '\n' > "$B64_FILE"
chmod 600 "$B64_FILE"

cat <<EOF

Keystore created.

  Keystore:          $OUT
  Base64 (for CI):   $B64_FILE
  Alias:             $ALIAS
  Cert SHA-256:      $CERT_SHA256

1. Back up $OUT and its password offline (two locations). Losing either ends the
   upgrade path for every installed copy of Mobet.

2. Load the secrets (needs an admin-scoped 'gh auth login', not a CI token):

   gh secret set MOBET_KEYSTORE_BASE64   --repo o48902808-creator/mobet < "$B64_FILE"
   gh secret set MOBET_KEYSTORE_PASSWORD --repo o48902808-creator/mobet   # paste the password
   gh secret set MOBET_KEY_ALIAS         --repo o48902808-creator/mobet --body '$ALIAS'
   gh secret set MOBET_KEY_PASSWORD      --repo o48902808-creator/mobet   # same password
   gh secret set MOBET_SIGNING_CERT_SHA256 --repo o48902808-creator/mobet --body '$CERT_SHA256'

   (Or paste them in Settings -> Secrets and variables -> Actions -> New repository secret.)

3. Publish the fingerprint $CERT_SHA256 in docs/RELEASE_SIGNING.md so users can pin it.

4. Delete the base64 copy once the secret is set:

   shred -u "$B64_FILE" 2>/dev/null || rm -P "$B64_FILE" || rm -f "$B64_FILE"

EOF
