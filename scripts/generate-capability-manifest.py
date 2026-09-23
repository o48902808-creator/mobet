#!/usr/bin/env python3
"""Generate Mobet's canonical, self-verifying embedded build manifest.

The final APK SHA-256 cannot be embedded in the APK itself (doing so changes the APK). The
embedded manifest therefore authenticates its canonical payload with manifestSha256. Mobet
computes the installed APK SHA-256 at runtime, while the release sidecar adds artifact.sha256
after the APK has been built.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def canonical(value: dict) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--commit", default="development")
    parser.add_argument("--workflow", default="local-build")
    parser.add_argument("--attestation", default="unavailable")
    parser.add_argument("--build-type", choices=("debug", "release"), default="debug")
    parser.add_argument("--signing", choices=("debug", "release", "unsigned"), default="debug")
    args = parser.parse_args()

    manifest = {
        "schema": "mobet.capability.v1",
        "applicationId": "ai.arena.mobet",
        "versionName": args.version_name,
        "versionCode": args.version_code,
        "buildType": args.build_type,
        "expectedSigning": args.signing,
        "commit": args.commit,
        "workflow": args.workflow,
        "attestation": args.attestation,
        "policyVersion": "deterministic-policy.v1",
        "ledgerSchemaVersion": "mobet.ledger.v1/hash-chain.v2",
        "modelEngine": "AICore Gemini Nano (optional) + deterministic local fallback",
        "voiceEngine": "Offline VoiceEngine: Android on-device + optional local model pack",
        "invariants": [
            "no-network-permission",
            "deterministic-policy-authority",
            "hash-chained-ledger",
            "model-output-is-untrusted",
        ],
    }
    manifest["manifestSha256"] = hashlib.sha256(canonical(manifest)).hexdigest()

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main()
