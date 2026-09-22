# State-of-Thought reasoning — research note and Mobet mapping

This note answers two questions: what "State of Thought" (SoT) reasoning is, and which parts
of it can strengthen Mobet *without* weakening the product's defining constraints — no network,
no model shipped, every device action gated by deterministic rules. The outcome of the review
is one implemented component (`agent/StateOfThoughtPolicy.kt`), one deliberately declined
component, and one documented finding.

## What SoT is

*State of Thought Enables Endogenous Reasoning* (Gong, Hou, Zeng, Xiao, Yuen, Lim — NTU
Singapore / KTH, September 2026, [arXiv:2609.16055](https://arxiv.org/abs/2609.16055),
code at [github.com/GongZhiren/State-of-Thought](https://github.com/GongZhiren/State-of-Thought)).

Test-time reasoning today is controlled *from outside the model*: prompts prescribe a fixed
reasoning program (chain-of-thought, plan formats), or a search procedure expands the
trajectory within a constrained space (tree-of-thoughts, best-of-n). Both ignore the model's
own evolving internal state as a signal for what evidence is needed next.

SoT reframes reasoning as a **closed loop driven by an endogenous state**:

- **The state** `m_t = (δ_t, v_t, c_t, H_t)` is a compact dynamics-geometric readout of the
  model's internal information transfer, answering four questions per step: is the trajectory
  internally *organized* (δ), is it *moving* (velocity v), is it *directionally stable*
  (consistency c), and is it *locally uncertain* (entropy H).
- **Two operators act on it.** The evidence-organization operator 𝒮 decides *which historical
  reasoning steps remain active support* for the next decision (state-conditioned selection
  rather than recency or generic compression), and the stopping operator 𝒯 decides when
  sufficient support has accumulated — so stopping emerges from the state instead of a fixed
  depth schedule.
- **The machinery is tiny on purpose**: a 582-parameter controller on a *frozen* backbone,
  operating at sentence level — the unit that still carries a coherent reasoning intention.

Reported results: accuracy 1.34–2.51× the mean baseline across quantitative, general,
symbolic/code, and long-context reasoning (3 LLMs, 16 datasets) while *reducing* generated
tokens by 62.6% and latency by 44.6%; +3.8 points on VLM tasks with 74.9% fewer tokens than
search-based methods. Under constrained access, training-free and embedding-only variants
retain 38.2%/36.5% of the mean accuracy gains, and trajectory-only judging agrees with
full-state scoring 84.1% of the time.

Adjacent work that shaped this mapping: the position paper *LLM Reasoning Is Latent, Not the
Chain of Thought* (arXiv:2604.15726) argues the primary object of reasoning is the latent state
trajectory, with the surface trace a partial interface — precisely the part of an agent
Mobet does not and cannot observe, which is why Mobet treats any future model as an oracle in
the environment rather than a mind to trust. The *Dual-State Framework* (arXiv:2512.20660)
formalizes exactly that split: deterministic control flow on one side, stochastic generation
quarantined on the other — which is Mobet's `ModelAssistant` contract stated in software-
engineering terms.

## Why literal SoT is out of scope for Mobet

SoT presumes an LLM whose internals are readable. Mobet ships no model, holds no network
permission (enforced in the merged manifest at build time), and reads screens that can contain
secrets — three independent reasons a language-model reasoning loop is not a drop-in.
A genuinely on-device model advisor (e.g. an AICore-class bundled model) would have to clear
the same kind of gate as Tier-3 scheduling (`SCHEDULED_RUNS_REVIEW.md`): the `ModelAssistant`
contract today already encodes the safe shape — a model may *rank pre-approved canonical
action IDs*, its output passes `ModelOutputValidator`, and every resulting step still crosses
`PlanValidator`/`RiskEngine` and the user-confirmation gates. SoT then reads as a control
architecture *inside* that contract, not a replacement for it.

## What maps onto Mobet — and what was done

The transfer is at the architecture level. SoT's claim is that the regime should govern
evidence and stopping; Mobet already had most of that machinery in deterministic form, and
the review found exactly one real gap in it.

| SoT component | Mobet analogue | Status |
| --- | --- | --- |
| State `m_t = (δ, v, c, H)` | `ReasoningRegime(grounded, movement, directionalStability, uncertainty)` | **implemented** — derived from signals the loop genuinely produces; ungrounded forces an uncertainty floor |
| Evidence operator 𝒮 — activate historical support *conditioned on the state* | `StateOfThoughtPolicy.evidencePolicy(regime)` feeding `BeliefReasoner.infer(evidence, weights)` | **implemented** — the gap the review found: source trust was static, so repeat-read OCR on a stuck screen corroborated exactly like fresh OCR |
| Stopping operator 𝒯 — state decides when enough support exists | `ObservationStabilizer` (settling gate), cycle/runtime budgets, stuckness recovery, completion quorum | **existing, deliberately not replaced** — see below |
| Frozen backbone + tiny controller | ML Kit OCR (bundled, frozen) + deterministic rule layer | existing — the "controller" is reviewed, test-locked code rather than parameters |

### The implemented piece: state-conditioned evidence weighting

Before this change, `BeliefReasoner` weighted evidence only by channel: accessibility 1.0,
user 1.0, OCR 0.72, world model 0.58 — independent of how the *run* was going. The failure
that invites is concrete: when the agent is stuck, the same screen gets re-observed and the
same confident OCR line is re-read, and the reasoner counts repetition as corroboration. SoT
names the underlying principle — evidence activation must be conditioned on the reasoning
state — and Mobet now has it in deterministic form:

- Any single degraded dimension (no movement, oscillation between screens, high ambiguity, or
  no grounded observation at all) damps the noisy channels toward fixed, non-zero floors:
  OCR 0.72 → 0.45, world model 0.58 → 0.35.
- Ground-truth channels never move: damping the accessibility tree *relative to* OCR in bad
  states would invert the trust order precisely when the noise is worst.
- Discounting, never deletion: a degraded regime raises the corroboration bar; it cannot
  render a channel mute.
- The healthy regime provably reproduces the previous static weights
  (`healthy regime is exactly the default policy` in `StateOfThoughtPolicyTest`), so the change
  is a strict tightening — nothing that was confidently decided before is decided differently
  while the run is going well.

### Deliberately declined: a state-conditioned stopper

SoT's 𝒯 lets the state end reasoning early when support plateaus. Mobet's answer remains
concrete rule failure — cycle and runtime budgets, settling abstention, dead-end memory,
completion quorums — and a second, softer stopping signal beside the hard budgets would
duplicate control and smear responsibility for "why did it stop?" across two mechanisms.
Learning-style early stopping is valuable for token-latency economics; Mobet's currency is
predictability under consequences. Declined, not deferred.

### Documented finding: the belief tracker is write-only on the decision path

`TemporalBeliefTracker` accumulates evidence every cycle and returns a `BeliefState`, but the
live decision path (`Deliberator.choose`, completion checks) never consults it — the returned
state is discarded at both call sites in `LiveAndroidAgent`. The new policy is therefore not
force-wired into a path that would make it decorative. If the tracker starts gating decisions
(and the SoT mapping says the natural home for that is *corroborating* completion evidence,
never vetoing policy gates), `BeliefReasoner.infer` now accepts the state-conditioned
weighting at exactly that call. Until then the tracker is diagnostics-plus-infrastructure, and
this note records that honestly rather than pretending a telemetry hook changed behaviour.

## References

- Gong, Hou, Zeng, Xiao, Yuen, Lim. *State of Thought Enables Endogenous Reasoning.*
  arXiv:2609.16055, September 2026.
- *LLM Reasoning Is Latent, Not the Chain of Thought* (position paper). arXiv:2604.15726, 2026.
- *Formalizing the Dual-State Framework for deterministic control of stochastic generation in
  LLM-based coding agents.* arXiv:2512.20660, 2025.
- Yao et al. *Tree of Thoughts: Deliberate Problem Solving with Large Language Models.*
  NeurIPS 2023 — the constrained-search baseline SoT positions itself against.
- Wei et al. *Chain-of-Thought Prompting Elicits Reasoning in Large Language Models.* 2022 —
  the fixed-program baseline.
