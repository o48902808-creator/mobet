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
- `synthesis/`: offline workflow generation engine — natural-language/recipe/recording → typed intent IR → grounding in inspected elements → robustness lowering → optimizer → risk-driven confirmation insertion via the shared `RiskEngine` → least-privilege policy → `PlanValidator`. See docs/WORKFLOW_GENERATION.md.
- `PlanSimulator`: counterfactual dry run — static grounding grades, risk tiers, confirmation gates, duration estimate, secret masking.
- `PlanValidator`: non-bypassable package/action allowlists, budgets, visual restrictions, and risk-tier confirmation rules shared by all plan sources.
- `RiskEngine` (policy): deterministic, explainable per-step risk scoring; the single source of truth for what counts as consequential, destructive, financial, or credential-sensitive.
- `FuzzyText` / `SelectorResolver` (agent): typo- and paraphrase-tolerant matching plus guarded self-healing with confidence floor, ambiguity margin, and abstention.
- `ScreenFingerprint` / `WorldModel` (agent): order-insensitive structural screen hashes, Jaccard screen similarity, and the bounded on-device transition graph.
- `ExecutionTimelineEvent` (automation/UI): bounded typed live explanations covering goal, screen, action, evidence, risk, policy, recovery, and terminal reason; package-scoped and non-authoritative.
- `ToolRegistry` / `ToolPlanGate` (agent): schema-typed tools with package, risk, confirmation, and autonomous-execution authorization repeated at dispatch; every outcome crosses a mandatory redacted audit sink.
- `VoiceEngine` (voice): offline-only transcription boundary with Android on-device, optional local PCM model-pack, and unavailable implementations; transcripts remain non-authoritative review input.
- `IntentToPlanPipeline` / `AgentPlanPreview` (planner): normalized package-bound goals, hard execution budgets, Explain/Dry-run/Execute modes, and explicit halt conditions before autonomous authority is granted.
- `ActionSnapshot` / `RecoveryPlanner` (agent): before/after structural evidence, explicit reversal receipts and recovery costs, plus deterministic wait→repair→dismiss→backtrack→replan→ask→abstain selection.
- `AuditLedger` (audit): SHA-256 hash-chained, tamper-evident, bounded execution history with full-chain verification.
- `AutonomousAgent` / `LiveAndroidAgent` (agent): bounded observe–deliberate–act–verify controllers. The live controller owns no accessibility nodes: it submits typed actions back through a freshly validated one-action `WorkflowRunner` and verifies a new snapshot afterward.
- `AccessibilityObservationAdapter`: converts node-free snapshots to observations/actions, assigns structural action IDs, and maps selected actions back to typed workflow steps.
- `Deliberator` / `HierarchicalPlanner` / `HierarchicalExecutor`: deterministic, uncertainty-aware ranking plus executable subgoal state with explicit preconditions/completion evidence, Bayesian learned-route utility, reversibility, and strict candidate/expansion budgets.
- `ObservationStabilizer`: requires repeated structural observations before planning and abstains when a UI cannot settle inside its sample budget.
- `BeliefReasoner` / `TemporalBeliefTracker`: preserve competing hypotheses and source attribution across accessibility, OCR, user, and world-model evidence; unsupported temporal evidence decays and low-confidence choices abstain.
- `ContentTrustEngine`: treats all screen/model text as untrusted data and blocks instruction-injection patterns from becoming action authority.
- `PersistentExperienceStore` / `ExperienceNavigator`: AES-GCM authenticated, bounded transition, outcome, repair-hash, and dead-end memory with Bayesian reliability, confidence decay, expiry, contradiction-driven drift invalidation, app-version invalidation, and cycle-safe graph search.
- `EncryptedStateStore` (security): namespace-bound authenticated storage backed by a non-exportable Android Keystore key with atomic legacy migration.
- `FailureClassifier` / `RecoveryPolicies`: explicit stale-selector, loading, modal, wrong-app, permission, rejection, and dead-end handling with bounded remedies.
- `ModelAssistant` / `LocalStructuredModelAssistant` / `ModelOutputValidator`: optional structured on-device suggestions and rankings over allowlisted candidate IDs only; influence is capped and has no device authority.
- `RunCheckpointStore` / `ResourceGovernor`: encrypted non-resuming crash evidence, irreversible replay prevention, battery/thermal limits, and operational emergency-stop support.
- `AgentPlanValidator`: counterfactual gate for autonomous proposals, including risk, lookahead, confidence, and terminal-only irreversible constraints.
- `AgentEvaluation`: stable success, cycle, excess-action, abstention-quality, and safety metric contracts enforced by CI tests.

## Autonomous control loop

1. **Observe** a node-free screen state through `AgentDevice`.
2. **Verify** the goal, package boundary, and remaining cycle budget.
3. **Deliberate** over actions that fit the goal's risk ceiling and are not remembered dead ends.
4. **Act** only through the guarded gateway: translate to a `Step`, recompute risk, validate a constrained workflow, collect any required confirmation, then let `WorkflowRunner` operate the device.
5. **Verify progress** from a fresh observation; persist the transition only after observing its result, with confidence/recency/app-version metadata.
6. **Backtrack** when a branch has no safe unexplored action. The failed parent edge is marked dead so it is not retried on this or a later run.

The implementation deliberately avoids unconstrained recursive planning, arbitrary code execution, remote triggers, and model-defined safety decisions. A future local or hosted model can rank candidates or suggest goals, but deterministic budgets, validation, execution, and verification remain authoritative.

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
