#!/usr/bin/env python3
"""Build a deterministic, screenshot-free Mobet release evidence bundle."""
from __future__ import annotations
import argparse, hashlib, json, pathlib, zipfile


def digest(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''): h.update(chunk)
    return h.hexdigest()


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument('--apk', type=pathlib.Path, required=True)
    p.add_argument('--capability', type=pathlib.Path, required=True)
    p.add_argument('--output', type=pathlib.Path, required=True)
    p.add_argument('--tests', type=pathlib.Path, action='append', default=[])
    p.add_argument('--ledger', type=pathlib.Path)
    args = p.parse_args()
    capability = json.loads(args.capability.read_text())
    apk_hash = digest(args.apk)
    if capability.get('sha256') and capability['sha256'] != apk_hash:
        raise SystemExit('capability APK digest mismatch')
    reports = []
    for root in args.tests:
        if not root.exists(): continue
        for path in sorted(x for x in root.rglob('*') if x.is_file()):
            reports.append({'path': str(path.relative_to(root)), 'sha256': digest(path), 'bytes': path.stat().st_size})
    ledger = None
    if args.ledger:
        value = json.loads(args.ledger.read_text())
        ledger = {'schema': value.get('schema'), 'head': value.get('chain', {}).get('head'),
                  'sourceHead': value.get('chain', {}).get('sourceHead'), 'sha256': digest(args.ledger)}
    evidence = {
        'schema': 'mobet.evidence.v1',
        'build': {'version': capability.get('versionName'), 'commit': capability.get('commit'),
                  'workflow': capability.get('workflow'), 'apkSha256': apk_hash,
                  'manifestSha256': capability.get('manifestSha256'),
                  'attestation': capability.get('attestation')},
        'invariants': capability.get('invariants', []),
        'tests': reports,
        'ledger': ledger,
        'privacy': {'screenshotsIncluded': False, 'secretsIncluded': False},
    }
    payload = json.dumps(evidence, sort_keys=True, separators=(',', ':')).encode()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    info = zipfile.ZipInfo('evidence.json', (1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    with zipfile.ZipFile(args.output, 'w') as archive: archive.writestr(info, payload)
    print(f'{args.output}: {hashlib.sha256(payload).hexdigest()}')
    return 0

if __name__ == '__main__': raise SystemExit(main())
