# Offline workflow bundles and quarantine

Workflow bundle version 2 is an offline, hash-addressed exchange format. Each entry contains its
workflow JSON, author label, bundle version, required packages and permissions, maximum risk,
network-dependency declaration, SHA-256 content hash, and an optional signature.

Hashes cover canonical JSON with recursively sorted object keys and preserved array order. Import
recomputes the hash before validation; missing or mismatched hashes keep a v2 entry quarantined.
Optional signatures use `SHA256withECDSA` over the same canonical bytes with an X.509-encoded EC
public key. Unsupported, malformed, or invalid signatures are shown as unsigned, never trusted.

Before acceptance the UI displays a quarantine report with package declarations, confirmation
count, unsigned count, hash status, network posture, per-workflow risk, and validation outcome.
Import only writes eligible entries to the inert local library. It never starts a workflow, fills a
secret, grants a permission, or widens package policy. Legacy v1 bundles remain importable but are
clearly marked as legacy/unhashed.
