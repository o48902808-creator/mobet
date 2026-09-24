#!/usr/bin/env python3
"""Digest an APK's payload independently of its signature, and report/verify reproducibility.

Mobet builds unsigned and signs in a separate job, so two distinct claims need measuring:

* **Reproducibility** — two independent unsigned builds of the same commit are byte-identical.
  Anyone can reproduce this; it needs no key.
* **Signing fidelity** — the published *signed* APK contains exactly the payload that was built
  and independently rebuilt. Signing adds the v1 signature files and an APK Signing Block, so
  whole-file digests necessarily differ; the content digest is what must not move.

The content digest covers every zip entry except the v1 signature files, hashing entry names
and uncompressed contents so that recompression or reordering cannot mask a changed payload.

    apk-content-digest.py --report out.json --first a.apk --second b.apk
    apk-content-digest.py --verify-report out.json --apk signed.apk --against unsigned.apk
    apk-content-digest.py --print some.apk
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import zipfile
from pathlib import Path

SIGNATURE_ENTRIES = {"META-INF/MANIFEST.MF"}
SIGNATURE_SUFFIXES = (".SF", ".RSA", ".DSA", ".EC")


def is_signature_entry(name: str) -> bool:
    if name in SIGNATURE_ENTRIES:
        return True
    return name.startswith("META-INF/") and name.upper().endswith(SIGNATURE_SUFFIXES)


def content_digest(path: Path) -> str:
    """SHA-256 over every non-signature entry: name, NUL, then the digest of its bytes."""
    digest = hashlib.sha256()
    with zipfile.ZipFile(path) as archive:
        for name in sorted(archive.namelist()):
            if is_signature_entry(name):
                continue
            digest.update(name.encode())
            digest.update(b"\0")
            digest.update(hashlib.sha256(archive.read(name)).digest())
    return digest.hexdigest()


def whole_digest(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_report(output: Path, first: Path, second: Path) -> int:
    first_sha, second_sha = whole_digest(first), whole_digest(second)
    first_content, second_content = content_digest(first), content_digest(second)
    reproducible = first_sha == second_sha
    report = {
        "schema": "mobet.reproducibility.v3",
        "buildType": "release",
        "measuredOn": "unsigned APK (signing happens in a separate job)",
        "firstSha256": first_sha,
        "secondSha256": second_sha,
        "status": "reproducible" if reproducible else "non-reproducible",
        "contentDigestScope": "all APK entries except META-INF signature files",
        "contentSha256": first_content,
        "secondContentSha256": second_content,
        "contentStatus": "reproducible" if first_content == second_content else "non-reproducible",
        "note": ("Unsigned builds are byte-comparable by anyone without the signing key. "
                 "contentSha256 survives signing, so the published signed APK must match it."),
    }
    output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    # A non-reproducible build is reported honestly rather than failing the release, matching
    # the existing posture — but a payload that differs between two builds of the same commit
    # is a real signal, so it is surfaced as a workflow warning.
    if not reproducible:
        print("::warning::Unsigned rebuild was not byte-identical; see mobet-reproducibility.json")
    return 0


def verify_report(report_path: Path, apk: Path, against: Path | None) -> int:
    report = json.loads(report_path.read_text())
    expected = report.get("contentSha256")
    actual = content_digest(apk)
    failures = []
    if not expected:
        failures.append("report has no contentSha256 to check against")
    elif expected != actual:
        failures.append(f"signed APK payload {actual} != built payload {expected}")
    if against is not None:
        other = content_digest(against)
        if other != actual:
            failures.append(f"signed APK payload {actual} != {against.name} payload {other}")
    if failures:
        for failure in failures:
            print(f"::error::{failure}", file=sys.stderr)
        print("Signing must not alter the built payload. Refusing to continue.", file=sys.stderr)
        return 1
    print(f"Signed APK payload matches the built and independently rebuilt payload: {actual}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, help="Write a reproducibility report here")
    parser.add_argument("--first", type=Path)
    parser.add_argument("--second", type=Path)
    parser.add_argument("--verify-report", type=Path, help="Check an APK against a report")
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--against", type=Path)
    parser.add_argument("--print", dest="show", type=Path, help="Print one APK's content digest")
    args = parser.parse_args()

    if args.show:
        print(content_digest(args.show))
        return 0
    if args.report:
        if not (args.first and args.second):
            parser.error("--report requires --first and --second")
        return write_report(args.report, args.first, args.second)
    if args.verify_report:
        if not args.apk:
            parser.error("--verify-report requires --apk")
        return verify_report(args.verify_report, args.apk, args.against)
    parser.error("nothing to do: pass --report, --verify-report, or --print")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
