#!/usr/bin/env python3
import copy
import hashlib
import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("verify-ledger.py")
spec = importlib.util.spec_from_file_location("verify_ledger", SCRIPT)
verify_ledger = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(verify_ledger)


def digest(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


def valid_bundle():
    device_run = "12345678-1234-4234-9234-123456789abc"
    source_events = ["run started", "token=[REDACTED]"]
    source_previous = "mobet-genesis"
    source = []
    for index, event in enumerate(source_events, 1):
        timestamp = 1000 + index
        source_hash = digest(f"{source_previous}|{index}|{timestamp}|{event}")
        source.append((index, timestamp, event, source_hash, source_previous))
        source_previous = source_hash
    source_head = source[-1][3]
    genesis = digest(f"mobet-export-genesis|{device_run}|{source_head}")
    previous = genesis
    entries = []
    for seq, timestamp, event, source_hash, source_prev in source:
        export_hash = digest(f"{previous}|{seq}|{timestamp}|{event}")
        entries.append({
            "seq": seq, "ts": timestamp, "event": event,
            "hash": export_hash, "prev": previous,
            "sourceHash": source_hash, "sourcePrev": source_prev,
            "redacted": seq == 2,
        })
        previous = export_hash
    return {
        "schema": "mobet.ledger.v1",
        "deviceRun": device_run,
        "exportedAt": 2000,
        "build": {
            "version": "0.8.0", "versionCode": 9,
            "apkSha256": "a" * 64, "manifestSha256": "b" * 64,
            "commit": "abc123", "signing": "debug",
            "capabilityVerified": True, "provenanceVerifiedOnDevice": True,
        },
        "policyVersion": "deterministic-policy.v1",
        "ledgerSchemaVersion": "mobet.ledger.v1/hash-chain.v2",
        "entries": entries,
        "chain": {
            "algorithm": "SHA-256(prev|seq|ts|event UTF-8)",
            "genesis": genesis, "head": previous, "verified": True,
            "sourceGenesis": "mobet-genesis", "sourceHead": source_head,
            "sourceVerifiedAtExport": True, "redactedEntries": 1,
        },
        "privacy": {
            "screenshotsIncluded": False, "secretsIncluded": False,
            "sensitiveValuesRedacted": True,
        },
    }


class VerifyLedgerTest(unittest.TestCase):
    def test_valid_bundle(self):
        self.assertEqual([], verify_ledger.verify(valid_bundle()))

    def test_event_tampering_breaks_export_chain(self):
        bundle = valid_bundle()
        bundle["entries"][0]["event"] = "forged"
        self.assertTrue(any("hash mismatch" in item for item in verify_ledger.verify(bundle)))

    def test_source_failure_is_not_accepted(self):
        bundle = valid_bundle()
        bundle["chain"]["sourceVerifiedAtExport"] = False
        self.assertTrue(any("source ledger" in item for item in verify_ledger.verify(bundle)))

    def test_unredacted_source_hash_is_independently_checked(self):
        bundle = valid_bundle()
        bundle["entries"][0]["sourceHash"] = "c" * 64
        bundle["entries"][1]["sourcePrev"] = "c" * 64
        self.assertTrue(any("source hash mismatch" in item for item in verify_ledger.verify(bundle)))

    def test_secret_leak_is_rejected_even_with_recomputed_chain(self):
        bundle = valid_bundle()
        entry = bundle["entries"][0]
        entry["event"] = "password=hunter2"
        entry["hash"] = digest(f"{entry['prev']}|{entry['seq']}|{entry['ts']}|{entry['event']}")
        bundle["entries"][1]["prev"] = entry["hash"]
        second = bundle["entries"][1]
        second["hash"] = digest(f"{second['prev']}|{second['seq']}|{second['ts']}|{second['event']}")
        bundle["chain"]["head"] = second["hash"]
        self.assertTrue(any("sensitive value" in item for item in verify_ledger.verify(bundle)))

    def test_wrong_apk_digest_is_rejected(self):
        bundle = valid_bundle()
        bundle["build"]["apkSha256"] = "not-a-digest"
        self.assertTrue(any("apkSha256" in item for item in verify_ledger.verify(bundle)))

    def test_unverified_build_capability_is_rejected(self):
        bundle = valid_bundle()
        bundle["build"]["capabilityVerified"] = False
        self.assertTrue(any("capability identity" in item for item in verify_ledger.verify(bundle)))

    def test_extra_screenshot_payload_is_rejected(self):
        bundle = valid_bundle()
        bundle["screenshots"] = ["base64-data"]
        self.assertTrue(any("unexpected top-level" in item for item in verify_ledger.verify(bundle)))


if __name__ == "__main__":
    unittest.main()
