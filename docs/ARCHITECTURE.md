# Architecture

## Trust boundary

The Accessibility Service is the privileged boundary. The activity parses locally authored JSON and passes a typed `Workflow` directly to the connected service. There is currently no internet permission, remote command channel, background scheduler, or arbitrary script execution.

## Components

- `MainActivity`: service setup, local JSON editor, Run/Stop controls, status display, dry-run report, audit-ledger and world-model viewers, and the hardened typed-confirmation dialog.
- `Workflow`: strict parser and typed step model with bounded timing values.
- `MobetAccessibilityService`: Android accessibility lifecycle, app launch, text setting, local status broadcasts, and append-only ledger writes.
- `WorkflowRunner`: sequential observe–act state machine — breadth-first accessibility-tree selector, polling, retries, conditions, secret resolution, risk-tiered confirmations, screen-fingerprint loop guard, policy-gated self-healing, world-model learning, cancellation, and fail-fast behavior.
- `InteractionRecorder`: privacy-preserving tap/scroll capture; it records selectors but never entered text.
- `ScreenInspector`: node-free snapshots with selector stability, uniqueness, role, and bounds diagnostics.
- `SecretStore`: AES-GCM values protected by a non-exportable Android Keystore key.
- `GoalPlanner`: offline natural-language-to-JSON compiler grounded only in inspected elements, with risk-driven confirmation insertion via the shared `RiskEngine`.
- `PlanSimulator`: counterfactual dry run — static grounding grades, risk tiers, confirmation gates, duration estimate, secret masking.
- `PlanValidator`: non-bypassable package/action allowlists, budgets, visual restrictions, and risk-tier confirmation rules shared by all plan sources.
- `RiskEngine` (policy): deterministic, explainable per-step risk scoring; the single source of truth for what counts as consequential, destructive, financial, or credential-sensitive.
- `FuzzyText` / `SelectorResolver` (agent): typo- and paraphrase-tolerant matching plus guarded self-healing with confidence floor, ambiguity margin, and abstention.
- `ScreenFingerprint` / `WorldModel` (agent): order-insensitive structural screen hashes, Jaccard screen similarity, and the bounded on-device transition graph.
- `AuditLedger` (audit): SHA-256 hash-chained, tamper-evident, bounded execution history with full-chain verification.

## Runtime safety rails

1. **Static gate** — every plan passes `PlanValidator` before launch, regardless of source (hand-authored, recorded, goal-compiled, or a future model planner).
2. **Risk tiers** — `RiskEngine` maps each step to NONE / LOW / ELEVATED / CRITICAL. ELEVATED requires an adjacent confirm; CRITICAL upgrades that confirm to a typed `APPROVE` dialog at runtime.
3. **Boundaries** — package allowlist and runtime deadline are enforced on every tick; the loop guard aborts when a screen fingerprint repeats more often than the plan could legitimately need (steps + slack) without structural change.
4. **Healing guardrails** — self-healing is off by default, only ever applies to ≤ LOW-risk steps, fires once per step, and must beat both a confidence floor and an ambiguity margin; otherwise the run fails loudly.
5. **Accountability** — every status event lands in both the rolling diagnostics and the hash-chained audit ledger, which can be re-verified at any time.

## Design rules for future work

- Prefer resource IDs and accessibility semantics over screen coordinates.
- Treat OCR and coordinate tapping as explicit low-confidence fallbacks.
- Never log or persist filled values; introduce Keystore-backed secret references instead.
- Require local confirmation for external communication, money movement, purchases, form submission, account changes, and deletion.
- Maintain package allowlists per workflow and show the target app before starting.
- Keep deterministic execution as the final authority even if an AI planner proposes steps.
