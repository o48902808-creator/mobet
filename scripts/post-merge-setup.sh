#!/usr/bin/env bash
#
# One-time, maintainer-run repository setup for docs/PRODUCTION.md §2.
#
#   bash scripts/post-merge-setup.sh
#
# Requires: `gh` authenticated as the repo owner (your token already carries the
# administration scope the built-in GITHUB_TOKEN cannot have). Every step is
# idempotent — re-running is a verification pass, not a second change:
#   1. Dependency graph      — verified; enabled only if the repo is private.
#   2. Branch protection     — upserted on the default branch with the four CI checks.
#   3. Pin dependency sums   — dispatched once; skipped if it has ever run.
#
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

if ! gh auth status >/dev/null 2>&1; then
    echo "error: gh is not authenticated. Run 'gh auth login' first." >&2
    exit 1
fi

REPO="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"
DEFAULT_BRANCH="$(gh api "repos/$REPO" --jq .default_branch)"
echo "== Repository: $REPO (default branch: $DEFAULT_BRANCH)"

# ── 1. Dependency graph ──────────────────────────────────────────────────────
VISIBILITY="$(gh api "repos/$REPO" --jq .visibility)"
if [ "$VISIBILITY" = "public" ]; then
    echo "== 1. Dependency graph: public repository — enabled by default, nothing to do."
else
    echo "== 1. Dependency graph: private repository — enabling explicitly…"
    gh api -X PATCH "repos/$REPO" --input - <<'JSON'
{"security_and_analysis":{"dependency_graph":{"status":"enabled"}}}
JSON
    echo "   enabled."
fi

# ── 2. Branch protection ─────────────────────────────────────────────────────
echo "== 2. Branch protection: upserting rules on $DEFAULT_BRANCH…"
gh api -X PUT "repos/$REPO/branches/$DEFAULT_BRANCH/protection" --input - <<'JSON'
{
  "required_status_checks": {
    "strict": false,
    "contexts": [
      "Build & JVM unit tests",
      "Connected device tests",
      "CodeQL Java and Kotlin",
      "Dependency review"
    ]
  },
  "enforce_admins": false,
  "required_pull_request_reviews": {
    "dismiss_stale_reviews": false,
    "require_code_owner_reviews": false,
    "required_approving_review_count": 0
  },
  "restrictions": null,
  "required_linear_history": false,
  "allow_force_pushes": false,
  "allow_deletions": false
}
JSON
CHECKS="$(gh api "repos/$REPO/branches/$DEFAULT_BRANCH/protection" \
    --jq '.required_status_checks.contexts | join(", ")')"
echo "   active. Required checks: $CHECKS"

# ── 3. Pin dependency checksums ──────────────────────────────────────────────
EXISTING="$(gh api "repos/$REPO/actions/workflows/pin-dependencies.yml/runs" \
    --jq '.workflow_runs | length')"
if [ "$EXISTING" != "0" ]; then
    echo "== 3. Pin dependency checksums: already has $EXISTING run(s) — skipping dispatch."
else
    gh api -X POST "repos/$REPO/actions/workflows/pin-dependencies.yml/dispatches" \
        -f "ref=$DEFAULT_BRANCH"
    echo "== 3. Pin dependency checksums: dispatched on $DEFAULT_BRANCH."
    echo "   Follow it: gh run watch \$(gh run list --workflow=pin-dependencies.yml --limit 1 --json databaseId --jq '.[0].databaseId')"
fi

echo
echo "All three one-time settings are applied or verified. See docs/PRODUCTION.md §3 to cut v0.7.0."
