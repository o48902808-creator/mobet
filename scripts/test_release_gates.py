#!/usr/bin/env python3
"""Tests for the release signing gate and the independent release verifier.

The signing gate is the one check standing between a debug-signed APK and users who have
granted Mobet an accessibility service. It is shell + regex over apksigner output, which is
exactly the kind of code that silently stops matching when a tool's output changes. These tests
run it against recorded apksigner transcripts (real format, including the Android debug
identity) so its behaviour is known before a release run, not during one.

    python3 scripts/test_release_gates.py

No third-party dependencies and no Android SDK required.
"""
from __future__ import annotations

import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest
import zipfile

REPO = pathlib.Path(__file__).resolve().parent.parent
GATE = REPO / "scripts" / "assert-production-signature.sh"
VERIFIER = REPO / "scripts" / "verify-release-apk.sh"
CAPABILITY = REPO / "scripts" / "generate-capability-manifest.py"
EVIDENCE = REPO / "scripts" / "build-evidence-bundle.py"

PROD_CERT = "5f2c1b0d9e3a47c8b61d05f4a29e7c3d8b40f16a2d5e9c7b3a108f42d6e5b9c1"

# Real apksigner --verbose --print-certs output shape.
PRODUCTION_OUTPUT = f"""Verifies
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v4 scheme (APK Signature Scheme v4): false
Verified for SourceStamp: false
Number of signers: 1
Signer #1 certificate DN: CN=Mobet, OU=Mobet, O=Mobet, L=Accra, C=GH
Signer #1 certificate SHA-256 digest: {PROD_CERT}
Signer #1 certificate SHA-1 digest: 0b9d6c4f2a8e1537bd04c6a9e2f81d37b5a0c4e6
Signer #1 certificate MD5 digest: 4d9a2f6c1b8e3057ad24c6f9b2e81d35
Signer #1 key algorithm: RSA
Signer #1 key size (bits): 4096
"""

DEBUG_OUTPUT = """Verifies
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Verified for SourceStamp: false
Number of signers: 1
Signer #1 certificate DN: CN=Android Debug, O=Android, C=US
Signer #1 certificate SHA-256 digest: 8a3c4b262d721acd49a4bf97d5213199c86fa2b93c9f4a1d2e5b7086c4d3f1a2
Signer #1 certificate SHA-1 digest: 1e9f3c7a5b204d68ae31c05f9b6d2e847a0c3f15
Signer #1 key algorithm: RSA
Signer #1 key size (bits): 2048
"""

V1_ONLY_OUTPUT = """Verifies
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): false
Verified using v3 scheme (APK Signature Scheme v3): false
Number of signers: 1
Signer #1 certificate DN: CN=Mobet, O=Mobet, C=GH
Signer #1 certificate SHA-256 digest: aa11bb22cc33dd44ee55ff6677889900aabbccddeeff00112233445566778899
Signer #1 certificate SHA-1 digest: 00112233445566778899aabbccddeeff00112233
"""

TWO_SIGNER_OUTPUT = PRODUCTION_OUTPUT.replace("Number of signers: 1", "Number of signers: 2")

NO_CERT_OUTPUT = """Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 1
"""


def run_gate(transcript: str, *extra: str):
    with tempfile.TemporaryDirectory() as tmp:
        recorded = pathlib.Path(tmp) / "apksigner.txt"
        recorded.write_text(transcript)
        out = pathlib.Path(tmp) / "signing.json"
        proc = subprocess.run(
            ["bash", str(GATE), "--apksigner-output", str(recorded), "--output", str(out), *extra],
            capture_output=True, text=True,
        )
        report = json.loads(out.read_text()) if out.exists() else None
        return proc, report


class SigningGate(unittest.TestCase):
    def test_accepts_production_signature(self):
        proc, report = run_gate(PRODUCTION_OUTPUT, "--expect-cert", PROD_CERT)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertTrue(report["signed"])
        self.assertFalse(report["debugKey"])
        self.assertTrue(report["fingerprintPinned"])
        self.assertEqual(report["certSha256"], PROD_CERT)
        self.assertEqual(report["schemes"], {"v1": True, "v2": True, "v3": True})

    def test_rejects_android_debug_certificate(self):
        proc, report = run_gate(DEBUG_OUTPUT)
        self.assertEqual(proc.returncode, 1)
        self.assertIn("ANDROID DEBUG certificate", proc.stderr)
        self.assertIsNone(report, "no signing evidence may be written for a rejected APK")

    def test_rejects_debug_certificate_even_when_pinned_to_it(self):
        """A pin can never launder the debug key: the DN check runs regardless."""
        debug_sha = "8a3c4b262d721acd49a4bf97d5213199c86fa2b93c9f4a1d2e5b7086c4d3f1a2"
        proc, _ = run_gate(DEBUG_OUTPUT, "--expect-cert", debug_sha)
        self.assertEqual(proc.returncode, 1)
        self.assertIn("ANDROID DEBUG", proc.stderr)

    def test_rejects_missing_v2_signature(self):
        proc, _ = run_gate(V1_ONLY_OUTPUT)
        self.assertEqual(proc.returncode, 1)
        self.assertIn("Scheme v2", proc.stderr)

    def test_rejects_fingerprint_mismatch(self):
        proc, _ = run_gate(PRODUCTION_OUTPUT, "--expect-cert", "dead" * 16)
        self.assertEqual(proc.returncode, 1)
        self.assertIn("does not match the pinned fingerprint", proc.stderr)

    def test_accepts_pin_in_colon_separated_uppercase_form(self):
        """keytool prints AB:CD:...; apksigner prints abcd... — both must pin identically."""
        colonised = ":".join(PROD_CERT[i:i + 2] for i in range(0, len(PROD_CERT), 2)).upper()
        proc, report = run_gate(PRODUCTION_OUTPUT, "--expect-cert", colonised)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertTrue(report["fingerprintPinned"])

    def test_rejects_multiple_signers(self):
        proc, _ = run_gate(TWO_SIGNER_OUTPUT, "--expect-cert", PROD_CERT)
        self.assertEqual(proc.returncode, 1)
        self.assertIn("Expected exactly one signer", proc.stderr)

    def test_rejects_unreadable_certificate(self):
        proc, _ = run_gate(NO_CERT_OUTPUT)
        self.assertEqual(proc.returncode, 1)
        self.assertIn("Could not read the signer certificate", proc.stderr)

    def test_warns_but_passes_without_a_pin(self):
        proc, report = run_gate(PRODUCTION_OUTPUT)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertFalse(report["fingerprintPinned"])
        self.assertIn("not pinned", proc.stdout + proc.stderr)


def build_release_fixture(directory: pathlib.Path, *, tamper: str = "") -> str:
    """Assemble a release-asset set of the shape the three-job workflow publishes.

    The build job emits an unsigned APK and measures reproducibility on it; the sign job adds
    the signature. The fixtures mirror that split, including the content digest that ties the
    signed artifact back to the independently rebuilt payload.
    """
    subprocess.run([sys.executable, str(CAPABILITY), "--output", str(directory / "cap.json"),
                    "--version-name", "1.0.0", "--version-code", "10", "--commit", "c0ffee",
                    "--workflow", "wf", "--attestation", "ref",
                    "--build-type", "release", "--signing", "release"], check=True)
    cap = json.loads((directory / "cap.json").read_text())
    apk = directory / "mobet.apk"
    with zipfile.ZipFile(apk, "w") as z:
        z.writestr("assets/mobet-capability.json", json.dumps(cap, indent=2, ensure_ascii=False) + "\n")
        z.writestr("classes.dex", "payload")
        z.writestr("META-INF/CERT.RSA", "signature")
    unsigned_digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    content = subprocess.run(
        [sys.executable, str(REPO / "scripts" / "apk-content-digest.py"), "--print", str(apk)],
        check=True, capture_output=True, text=True).stdout.strip()
    digest = unsigned_digest
    (directory / "mobet.apk.sha256").write_text(f"{digest}  mobet.apk\n")
    sidecar = dict(cap, artifact="mobet.apk", sha256=digest)
    (directory / "mobet-capability.json").write_text(json.dumps(sidecar, indent=2) + "\n")
    # Evidence is built over the unsigned payload, exactly as the build job does it.
    subprocess.run([sys.executable, str(EVIDENCE), "--apk", str(apk),
                    "--capability", str(directory / "mobet-capability.json"),
                    "--output", str(directory / "mobet-evidence.zip")], check=True,
                   capture_output=True)
    ev = (directory / "mobet-evidence.zip").read_bytes()
    (directory / "mobet-evidence.zip.sha256").write_text(
        hashlib.sha256(ev).hexdigest() + "  mobet-evidence.zip\n")
    (directory / "mobet-reproducibility.json").write_text(json.dumps({
        "schema": "mobet.reproducibility.v3", "buildType": "release",
        "measuredOn": "unsigned APK (signing happens in a separate job)",
        "firstSha256": unsigned_digest, "secondSha256": unsigned_digest,
        "status": "reproducible", "contentSha256": content,
        "secondContentSha256": content, "contentStatus": "reproducible",
        "signedSha256": digest}, indent=2) + "\n")
    import base64
    payload = base64.b64encode(json.dumps({
        "subject": [{"name": "mobet.apk", "digest": {"sha256": digest}}],
        "predicate": {"runDetails": {"builder": {"id": "https://github.com/actions/runner"}}},
    }).encode()).decode()
    (directory / "mobet.sigstore.json").write_text(json.dumps({
        "mediaType": "application/vnd.dev.sigstore.bundle.v0.3+json",
        "dsseEnvelope": {"payload": payload, "signatures": [{"sig": "x"}]},
        "verificationMaterial": {"certificate": {"rawBytes": "x"}}}, indent=2) + "\n")
    (directory / "mobet-signing.json").write_text(json.dumps({
        "schema": "mobet.signing.v1", "signed": True, "debugKey": False,
        "certSha256": PROD_CERT}, indent=2) + "\n")
    if tamper == "append":
        # Trailing bytes after the central directory: zip readers ignore them, so only a
        # whole-file digest notices. This is the shape of the classic appended-payload trick.
        with apk.open("ab") as handle:
            handle.write(b"\x00")
    elif tamper == "payload":
        # A swapped entry: the whole-file digest AND the content digest must both move,
        # which is what proves the content digest is not merely decorative.
        original = zipfile.ZipFile(apk)
        entries = [(i, original.read(i.filename)) for i in original.infolist()]
        original.close()
        with zipfile.ZipFile(apk, "w") as z:
            for info, data in entries:
                z.writestr(info, b"MALICIOUS" if info.filename == "classes.dex" else data)
    elif tamper:
        raise ValueError(f"unknown tamper mode {tamper!r}")
    return digest


def run_verifier(directory: pathlib.Path):
    return subprocess.run(["bash", str(VERIFIER), "--dir", str(directory)],
                          capture_output=True, text=True)


class ReleaseVerifier(unittest.TestCase):
    """The verifier must fail loudly on tampering and must never report SKIP as PASS."""

    def test_intact_release_passes_every_available_check(self):
        with tempfile.TemporaryDirectory() as tmp:
            build_release_fixture(pathlib.Path(tmp))
            proc = run_verifier(pathlib.Path(tmp))
            out = proc.stdout
            self.assertIn("PASS  APK matches mobet.apk.sha256", out)
            self.assertIn("PASS  capability sidecar binds this exact APK", out)
            self.assertIn("PASS  embedded manifest matches the sidecar", out)
            self.assertIn("PASS  evidence binds the unsigned payload the report rebuilds", out)
            self.assertIn("PASS  signed APK payload matches the rebuilt payload", out)
            self.assertIn("PASS  attestation subject == this APK digest", out)
            # Signature verification needs the Android SDK; where it is absent the row must
            # say SKIP, never PASS — an unverified signature is not a verified one.
            if "SKIP  apksigner not found" in out:
                self.assertNotIn("PASS  apksigner verifies", out)

    def test_appended_bytes_are_caught_by_the_whole_file_digest(self):
        """Trailing bytes leave the zip entries untouched, so the digest chain must catch it."""
        with tempfile.TemporaryDirectory() as tmp:
            build_release_fixture(pathlib.Path(tmp), tamper="append")
            proc = run_verifier(pathlib.Path(tmp))
            out = proc.stdout
            self.assertEqual(proc.returncode, 1)
            self.assertIn("FAIL  APK digest", out)
            self.assertIn("FAIL  capability sidecar sha256", out)
            self.assertIn("FAIL  attestation subject == this APK digest", out)
            self.assertIn("Do not install this APK", out)
            # The payload genuinely is unchanged, so this check honestly still passes. The
            # verifier must not manufacture a failure it cannot substantiate.
            self.assertIn("PASS  signed APK payload matches the rebuilt payload", out)

    def test_swapped_payload_is_caught_by_the_content_digest(self):
        """A replaced classes.dex must break the tie to the independently rebuilt payload."""
        with tempfile.TemporaryDirectory() as tmp:
            build_release_fixture(pathlib.Path(tmp), tamper="payload")
            proc = run_verifier(pathlib.Path(tmp))
            out = proc.stdout
            self.assertEqual(proc.returncode, 1)
            self.assertIn("FAIL  signed APK payload matches the rebuilt payload", out)
            self.assertIn("FAIL  APK digest", out)
            self.assertIn("Do not install this APK", out)
            # The embedded capability manifest is untouched by this swap, so that row still
            # passes — the content digest is what carries the detection here.
            self.assertIn("PASS  embedded manifest matches the sidecar", out)


if __name__ == "__main__":
    unittest.main(verbosity=2)
