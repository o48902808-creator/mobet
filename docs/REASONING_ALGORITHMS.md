# Reasoning algorithms Mobet can implement — evaluation and verdicts

Question: should Mobet implement State-of-Thought and other advanced reasoning algorithms,
and which ones fit? The frame for every answer below is the product's contract: **no
network, no shipped model, deterministic rules decide, everything is unit-testable on a
JVM.** An algorithm that needs a data center is not "too big" — it is out of scope by
definition. What survives that filter is more interesting.

## State of Thought — already here, in the part that helps

SoT ([arXiv:2609.16055](https://arxiv.org/abs/2609.16055), full background in
[STATE_OF_THOUGHT.md](STATE_OF_THOUGHT.md)) decomposes into three pieces:

| Piece | Reading on-device | Verdict |
| --- | --- | --- |
| Evidence-organization operator 𝒮 (which historical evidence may support the next decision, conditioned on the reasoning regime) | `StateOfThoughtPolicy`: regime (grounded δ̂ / movement v̂ / stability ĉ / uncertainty Ĥ) damps OCR and world-model weights toward floors under degraded runs; truth channels never move | **Implemented** — 16 locked invariants in `StateOfThoughtPolicyTest` |
| Stopping operator 𝒯 (state-estimated "enough support" halt) | A second, softer stopper beside hard budgets — duplicated control, less predictability under consequences | **Declined** (deliberate; do not reopen) |
| Belief corroboration of completion evidence | `BeliefReasoner.infer` already accepts the regime-weighted policy; its natural home is corroborating completion, never vetoing gates | **Next** — the second half of pillar 2 |

So "implement SoT" is mostly *done* — the avatar is the part that changes what evidence is
allowed to mean when the run goes wrong. What is left is wiring the belief tracker into
the completion check the `expect:` block now provides (pillar 3 turn).

## Algorithms that fit the contract — candidate catalog

Ordered by leverage per unit of new code; all are model-free, on-device, deterministic.

**1. Graph search over the world model (highest leverage).** `WorldModel` already records
labelled transitions (`previous fingerprint --step--> fingerprint`) per package. That is a
weighted directed graph with confidence from `PersistentExperienceStore`. On a failed
selector, **A\*/Dijkstra over that graph** answers "what known path gets me from this screen
back to a productive one?" — replanning *without* an LLM, the honest on-device analogue of
latent-trajectory planning ([arXiv:2604.15726](https://arxiv.org/abs/2604.15726); search in
the compressed representation, not in pixels). Tests: synthetic graphs, recovery paths,
abstention when confidence is below threshold.

**2. Evidence fusion upgrade.** `BeliefReasoner` currently weights sources per regime.
**Dempster–Shafer combination** adds a principled treatment of *conflict* — when
accessibility says "saved" and the world model says "dead end", DS yields an explicit
conflict mass to compare against the corroboration bar instead of silently averaging it
away. Internal change, same interface, provably equals current behavior under agreement.

**3. Beam search in the goal planner.** `WorkflowSynthesizer` + `PlanSimulator` are already a
generate-then-score pipeline. **Beam search** (width 2–3, pruned by `PlanValidator` at
every expansion) explores mixtures the greedy first-fit misses, with worst-case cost
bounded by the existing simulation. Tests lock deterministic tie-breaking — replayability
is a feature.

**4. Recovery via dead-end memory.** Dead ends expire with confidence today. Add **causal
tagging** (`why it died`: selector missing / boundary / gesture cancelled) plus
**k-nearest dead-end voting** on (app, fingerprint-similarity, action) so a workflow can
route *around* a known dead end before touching it, not after. This is the memory half of
self-healing selectors today, generalized.

**5. Fuzzy-match ensemble.** `FuzzyText` is one similarity function (Jaro–Winkler-style).
An **ensemble** — normalized Levenshtein, token-set Jaccard, prefix weight — with
majority-vote abstention removes the brittleness where a single metric's blind spot picks
the wrong candidate, and abstains instead of guessing when the metrics disagree.

**6. Constraint propagation for plans.** Treat a workflow as a constraint problem
(allowed packages, confirm-before-elevated, launch coherence, expect coherence) and apply
**AC-3-style arc consistency** to shrink invalid search space before `PlanValidator` emits
its first violation — this makes pillar-3 control flow *validatable at authoring time* for
the editor's live chip feedback, instead of producing longer error lists.

**7. Statistical anomaly check on run duration/step count.** A per-workflow EWMA of
run-to-run duration and action counts flags "this run is drifting from its own history"
early — cheap, private, and exactly the signal a user staring at a progress bar cannot see.

**Not pursued (with reasons).** Anything neural beyond the gated AICore advisor (vapor
without the contract); the SoT stopper (above); heuristic confidence scores user-facing as
"the AI is 87% sure" — telemetry theatre the policy gate would ignore anyway.

## Where they land

- **0.8:** pillar 2 (shipped) → pillar 3 control flow + algorithm 1 (replan hook) + 2 + 4.
- **0.9:** AICore advisor (FRONTIER pillar 1A) + algorithm 3 (beam) once planner grounding
  is measured, + algorithm 5.
- **1.0:** algorithms 6–7 as the editor's live plan lint and run drift signal.

Every candidate keeps the same promise: new intelligence enters as evidence the
deterministic authority can weigh — never as an authority.
