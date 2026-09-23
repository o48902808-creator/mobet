#!/usr/bin/env python3
"""Independently verify a Mobet redacted ledger evidence bundle.

Uses only Python's standard library. Exit code 0 means the portable redacted chain, build
identity fields, source-chain topology, and privacy declarations are internally consistent.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any

SCHEMA = "mobet.ledger.v1"
GENESIS_PREFIX = "mobet-export-genesis"
HEX_256 = re.compile(r"^[0-9a-f]{64}$")
MAX_BYTES = 10 * 1024 * 1024
MAX_ENTRIES = 100_000


def sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def require(condition: bool, message: str, failures: list[str]) -> None:
    if not condition:
        failures.append(message)


def has_sensitive_value(event: str) -> bool:
    if re.search(r"\{\{secret:[^}]+}}", event, re.I):
        return True
    if re.search(r"\bBearer\s+(?!\[REDACTED])\S+", event, re.I):
        return True
    assignment = re.compile(
        r"\b(secret|token|password|passcode|pin|api[_ -]?key|authorization)\s*[:=]\s*(?!\[REDACTED])\S+",
        re.I,
    )
    email = re.compile(r"(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
    long_number = re.compile(r"(?<!\d)(?:\d[ -]?){12,19}(?!\d)")
    return bool(assignment.search(event) or email.search(event) or long_number.search(event))


def verify(bundle: dict[str, Any]) -> list[str]:
    failures: list[str] = []
    allowed_top = {
        "schema", "deviceRun", "exportedAt", "build", "policyVersion",
        "ledgerSchemaVersion", "entries", "chain", "privacy",
    }
    require(not (set(bundle) - allowed_top), f"unexpected top-level fields: {sorted(set(bundle) - allowed_top)}", failures)
    require(bundle.get("schema") == SCHEMA, f"unsupported schema: {bundle.get('schema')!r}", failures)
    device_run = bundle.get("deviceRun")
    require(isinstance(device_run, str) and 8 <= len(device_run) <= 128, "invalid deviceRun", failures)
    require(isinstance(bundle.get("exportedAt"), int), "exportedAt must be an integer", failures)

    build = bundle.get("build")
    if not isinstance(build, dict):
        failures.append("build must be an object")
        build = {}
    for field in ("version", "commit", "signing"):
        require(isinstance(build.get(field), str) and bool(build.get(field)), f"build.{field} is required", failures)
    require(isinstance(build.get("versionCode"), int), "build.versionCode must be an integer", failures)
    require(build.get("provenanceVerified") is True, "build provenance was not verified at export", failures)
    for field in ("apkSha256", "manifestSha256"):
        value = build.get(field)
        require(isinstance(value, str) and bool(HEX_256.fullmatch(value)), f"build.{field} must be lowercase SHA-256", failures)
    require(isinstance(bundle.get("policyVersion"), str) and bool(bundle.get("policyVersion")), "policyVersion is required", failures)
    require(isinstance(bundle.get("ledgerSchemaVersion"), str) and bool(bundle.get("ledgerSchemaVersion")), "ledgerSchemaVersion is required", failures)

    privacy = bundle.get("privacy")
    if not isinstance(privacy, dict):
        failures.append("privacy must be an object")
    else:
        require(privacy.get("screenshotsIncluded") is False, "screenshots must not be included", failures)
        require(privacy.get("secretsIncluded") is False, "secretsIncluded must be false", failures)
        require(privacy.get("sensitiveValuesRedacted") is True, "redaction declaration missing", failures)

    chain = bundle.get("chain")
    if not isinstance(chain, dict):
        failures.append("chain must be an object")
        chain = {}
    source_head = chain.get("sourceHead")
    require(isinstance(source_head, str) and (source_head == "" or bool(HEX_256.fullmatch(source_head))), "chain.sourceHead is invalid", failures)
    expected_genesis = sha256(f"{GENESIS_PREFIX}|{device_run}|{source_head}") if isinstance(device_run, str) and isinstance(source_head, str) else ""
    require(chain.get("genesis") == expected_genesis, "export genesis does not bind deviceRun and sourceHead", failures)
    require(chain.get("algorithm") == "SHA-256(prev|seq|ts|event UTF-8)", "unsupported chain algorithm", failures)
    require(chain.get("verified") is True, "export did not declare its redacted chain verified", failures)
    require(chain.get("sourceVerifiedAtExport") is True, "source ledger was not verified when exported", failures)

    entries = bundle.get("entries")
    if not isinstance(entries, list):
        failures.append("entries must be an array")
        entries = []
    require(len(entries) <= MAX_ENTRIES, f"entries exceeds limit of {MAX_ENTRIES}", failures)

    previous = expected_genesis
    previous_source: str | None = None
    previous_sequence: int | None = None
    redacted_count = 0
    for index, entry in enumerate(entries):
        label = f"entries[{index}]"
        if not isinstance(entry, dict):
            failures.append(f"{label} must be an object")
            continue
        allowed_entry = {"seq", "ts", "event", "hash", "prev", "sourceHash", "sourcePrev", "redacted"}
        require(not (set(entry) - allowed_entry), f"unexpected fields at {label}: {sorted(set(entry) - allowed_entry)}", failures)
        seq, timestamp, event = entry.get("seq"), entry.get("ts"), entry.get("event")
        if not isinstance(seq, int) or seq < 1:
            failures.append(f"{label}.seq must be a positive integer")
            continue
        require(isinstance(timestamp, int) and timestamp >= 0, f"{label}.ts must be a non-negative integer", failures)
        require(isinstance(event, str), f"{label}.event must be a string", failures)
        if previous_sequence is not None:
            require(seq == previous_sequence + 1, f"sequence gap at {label}: expected {previous_sequence + 1}", failures)
        previous_sequence = seq

        require(entry.get("prev") == previous, f"redacted-chain previous hash mismatch at {label}", failures)
        if isinstance(timestamp, int) and isinstance(event, str):
            expected_hash = sha256(f"{previous}|{seq}|{timestamp}|{event}")
            require(entry.get("hash") == expected_hash, f"redacted-chain hash mismatch at {label}", failures)
            previous = expected_hash
            require(not has_sensitive_value(event), f"unredacted sensitive value at {label}", failures)

        source_hash, source_prev = entry.get("sourceHash"), entry.get("sourcePrev")
        require(isinstance(source_hash, str) and bool(HEX_256.fullmatch(source_hash)), f"{label}.sourceHash is invalid", failures)
        require(isinstance(source_prev, str) and (source_prev == "mobet-genesis" or bool(HEX_256.fullmatch(source_prev))), f"{label}.sourcePrev is invalid", failures)
        if previous_source is None:
            require(source_prev == chain.get("sourceGenesis"), f"source genesis mismatch at {label}", failures)
        else:
            require(source_prev == previous_source, f"source-chain link mismatch at {label}", failures)
        if isinstance(source_hash, str):
            previous_source = source_hash
        redacted = entry.get("redacted")
        require(isinstance(redacted, bool), f"{label}.redacted must be boolean", failures)
        if redacted is True:
            redacted_count += 1
        elif isinstance(source_prev, str) and isinstance(timestamp, int) and isinstance(event, str):
            expected_source_hash = sha256(f"{source_prev}|{seq}|{timestamp}|{event}")
            require(source_hash == expected_source_hash, f"source hash mismatch at unredacted {label}", failures)

    expected_head = expected_genesis if not entries else previous
    require(chain.get("head") == expected_head, "chain.head does not match the redacted chain", failures)
    require(chain.get("sourceHead") == (previous_source or ""), "chain.sourceHead does not match final sourceHash", failures)
    require(chain.get("redactedEntries") == redacted_count, "chain.redactedEntries count mismatch", failures)
    return failures


def load(path: Path) -> dict[str, Any]:
    size = path.stat().st_size
    if size > MAX_BYTES:
        raise ValueError(f"bundle is larger than {MAX_BYTES // (1024 * 1024)} MiB")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("top-level JSON must be an object")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path, help="exported Mobet ledger JSON")
    args = parser.parse_args()
    try:
        bundle = load(args.bundle)
        failures = verify(bundle)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"INVALID: {error}", file=sys.stderr)
        return 1

    if failures:
        print("INVALID Mobet ledger bundle", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    chain = bundle["chain"]
    print("VERIFIED Mobet ledger bundle")
    print(f"entries: {len(bundle['entries'])}")
    print(f"build: {bundle['build']['version']} ({bundle['build']['versionCode']})")
    print(f"apk sha256: {bundle['build']['apkSha256']}")
    print(f"source head: {chain['sourceHead'] or '(empty)'}")
    print(f"export head: {chain['head']}")
    print(f"redacted entries: {chain['redactedEntries']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
