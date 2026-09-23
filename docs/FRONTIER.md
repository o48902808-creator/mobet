# Making Mobet a frontier app — roadmap

Mobet's claim to the frontier is not "run an agent in the cloud." It is the
opposite bet: **the most capable phone agent whose safety properties are provable
from its own artifacts.** Zero network permission is enforced at build time;
every action passes a deterministic policy gate; the audit ledger records what
actually happened. Most mobile agents are racing to add capability and bolting
assurances on later. The frontier for Mobet is to *stay* ahead on capability
while keeping the assurance side formally checkable.

This roadmap builds only on substrate that already shipped
([CAPABILITY_MAP.md](CAPABILITY_MAP.md) §1) and respects every gated absence
(§2.1): no network capability, deterministic rules stay the authority, Tier-3
unattended execution stays declined, and the SoT state-conditioned stopper stays
declined — *declined, not deferred* ([STATE_OF_THOUGHT.md](STATE_OF_THOUGHT.md)).

## Pillar 1 — On-device intelligence behind the existing contract

> **Status: core shipped on this branch (0.9 in flight).** `AiCoreModelAssistant` implements
> the contract over the ML Kit GenAI Prompt API (Gemini Nano via AICore): created only for
> model-assisted runs, created successfully only when `checkStatus()` reports the feature
> available on this device, process-singleton, hard-capped per inference, falling back to the
> deterministic assistant on every failure path. The gate above is satisfied: threat-model
> section in docs/THREAT_MODEL.md, strict prompt codec (`AiCorePromptCodecTest`), and a
> hostile-plan conformance suite (`ModelPlanConformanceTest`) proving the deterministic
> validator rejects what a compromised model writes. Remaining in 0.9: QS tile + share-import;
> the emulator availability matrix stays a device-lab item.

**The move:** implement the `ModelAssistant` contract with **AICore (Gemini
Nano)** — an on-device model served by the system on Pixel 8 Pro / Galaxy S24
class hardware. The app itself still ships no model, still declares no network
permission; AICore inference runs in system processes and the merged-manifest
check automatically audits whatever the client library contributes.

**Why it is frontier:** natural-language goal → plan drafts, semantic screen
summaries ("this dialog is asking for storage access"), and plain-English plan
explanations — the three things rules cannot hand-write — on a phone that
provably cannot exfiltrate anything. No shipping competitor combines on-device
planning with a build-enforced zero-radio invariant.

**The architecture is already correct for it:** the model proposes, the
deterministic `PlanValidator` disposes. A model suggestion enters the exact same
policy pipeline as hand-written JSON, so a hallucinated plan is rejected, not
executed. `GoalPlanner` remains the universal floor; the model is an
accelerator, gated per-device with graceful absence.

**Gate before any code:** its own threat-model section (prompt-injection
surface when screen text reaches the model — `InjectionDefenceInDepthTest` is
the template) plus a `ModelAssistant` conformance suite proving the deterministic
validator catches hostile model output.

**Tests that prove it:** conformance tests — adversarial fake advisor emitting
policy-violating plans, all rejected; availability matrix (present/absent)
exercised on emulator.

## Pillar 2 — Verified execution: beliefs become evidence

> **Status: shipped on this branch.** The `expect:` block, validator rules, runner evidence
> check with named halts, and the JVM suite (`ExpectationCheckerTest`,
> `WorkflowParseTest`/`PlanValidatorTest` expect cases) are implemented — and the SoT loop
> is now wired end to end (`regime → evidence weights → fused belief → gate`): the
> trajectory-derived regime conditions `BeliefReasoner` weights every tick, and OCR-channel
> completion is discounted under degradation while the accessibility ground-truth channel
> can never be blocked. `StateOfThoughtPolicy.regime` is pure logic with a locked suite.

**The move:** add an optional per-step `expect:` block (fingerprint delta, OCR
text presence/absence, node-id appearance). After each step the runner checks
the *evidence* before continuing, and `TemporalBeliefTracker` — today write-only
on the decision path ([CAPABILITY_MAP.md](CAPABILITY_MAP.md) §2.3) — corroborates:
agreement proceeds, disagreement yields a policy-visible halt, never a blind
next-tap.

**Why it is frontier:** this is zero-trust automation — every action produces,
and is permitted by, verifiable post-state evidence. Cloud agents assert success
via page-load heuristics nobody can audit; Mobet would hold a per-step evidence
chain *in the ledger*, replayable after the fact. It is also the
[Dual-State](https://arxiv.org/abs/2512.20660) pattern made concrete: explicit
state (parsed tree) cross-checked against latent state (belief distribution)
exactly at the consequential moments.

**Cost note:** pure Kotlin over existing fingerprints/OCR — no new dependency,
no new permission, fully covered by current CI.

**Tests that prove it:** locator-grade unit tests for expect evaluation;
simulated mismatch forces halt with a named ledger event; validator rejects a
malformed `expect:` block at parse time, not run time.

## Pillar 3 — Control flow that survives audits

> **Status: shipped on this branch.** `branch`/`repeatUntil`/`tryAlternates` with labels,
> three dynamic rails (per-repeat `maxIterations`, a 200-hop control budget, the action
> budget applied dynamically), static validation of jump coherence and confirm-gating, and
> dead-end-memory routing: options recorded dead on this screen are skipped with an
> auditable log line. `PlanSimulator` renders control flow as paths — branch targets resolved
> to step numbers, loop spans with caps, every alternate graded — and closes with a
> conservative worst-case path estimate against both execution rails (`maxActions`, 200-hop
> budget), warning when a looped run can outrun either; the grounded-selector tally doubles
> as the static plan-quality measurement that 0.9's model assistance consumes. The
> Dempster–Shafer fusion upgrade shipped with it: evidence combines under Dempster's rule
> with the conflict mass surfaced as ambiguity — corroboration compounds across channels,
> noise cannot dilute a ground-truth read, and total contradiction reports total doubt
> instead of a dragged label. The pillar's algorithm list is complete.

**The move:** bounded control steps as first-class, validator-visible actions:
`repeatUntil` (hard cap = run budget), `branch` on evidence, and `tryAlternates`
backed by dead-end memory — so a workflow is a *strategy*, not a recording.

**Why it is frontier:** scripts break on the first surprise; agents route
around it. Crucially, each branch decision lands in the audit ledger with the
evidence that caused it, so adaptivity stays inspectable — the property that
makes autonomy licensable rather than merely impressive.

**Design constraint:** `PlanValidator` must see branches statically (no
Turing-complete surprises): caps on nesting/iterations, dead-end memory wired in,
dry-run (`PlanSimulator`) renders each path.

## Pillar 4 — Presence without a network

> **Status: first two shipped on this branch (0.9 in flight).** The quick-settings tile and a
> static launcher shortcut both run the *pinned* workflow — pinned explicitly from any library
> sheet ("Pin to shade") or implicitly by the last successful run — through the unchanged run
> pipeline, confirmations included, and the pin carries a source snapshot so it survives
> library deletion while still tracking edits. Share-target import accepts `.mobet.json`
> bundles (and any JSON document) via `VIEW` into the exact streamed/capped/previewed import
> path the in-app picker uses; QR handoff stays the exploratory 1.0 item.

Quick wins that make the agent feel native while keeping the manifest clean:

- **Quick-settings tile + app shortcuts** — trigger a pinned workflow from the
  shade/launcher (confirmation gates still apply). Pure manifest + tiny service.
- **Share-target import** — accept `.mobet.json` bundles via `VIEW` intent into
  the existing import pipeline (§2.3 gap, real and cheap).
- **QR handoff (optional, exploratory)** — compressed-bundle transfer
  person-to-person with zero radios; camera + on-device barcode scanning. Only
  if the UX earns the camera permission decision.

**Voice goals** are deliberately split out as the one pillar needing a new
permission (`RECORD_AUDIO`, on-device `SpeechRecognizer` only). Separate
threat-model review; off by default; everything else ships without it.

> **Status: shipped on this branch under those exact locks.** A 🎙 Dictate action inside
> the goal dialog requests the mic at runtime, uses only the on-device recognizer (cloud
> backends are refused, not fallen back to), and drops the transcript into the goal field
> for review — voice is an input method, never execution authority; it cannot start a run.
> Threat-model section in docs/THREAT_MODEL.md.

## Suggested sequencing

| Line | Ships | New deps / permissions |
| --- | --- | --- |
| 0.8 | Pillar 2 (`expect:` + belief corroboration) and Pillar 3 (bounded control flow) | none — pure Kotlin, current CI covers |
| 0.9 | Pillar 1A: AICore `ModelAssistant` (device-gated, off by default) + QS tile + share-import | AICore client only; model conformance suite |
| 1.0 | Voice goals (opt-in review) · QR handoff (if earned) · hash-chained ledger | `RECORD_AUDIO` decision · camera decision |

The audit ledger is already hash-chained — every entry commits to
(previousHash|sequence|timestamp|event) in encrypted storage, `verify()` replays the chain,
and a tamper-evident high-water mark catches tail truncation — pairing naturally with
milestone 2 (reproducible release provenance). So 1.0's live scope is the two permission
decisions: voice goals and QR handoff.

## What "frontier" must never mean here

- A network permission "temporarily" (the merged-manifest check failing should
  stay a build fire-alarm, forever).
- Models replacing rules as the authority — model *proposes*, deterministic
  validator *disposes*, always.
- Resurrecting anything marked *declined* in STATE_OF_THOUGHT.md or
  SCHEDULED_RUNS_REVIEW.md without reopening the named gate.

The moat is the conjunction: capability at the frontier of mobile agents, with
assurance at the frontier of verifiable software. Every pillar above strengthens
one side without taxing the other.
