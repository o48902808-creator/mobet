# Workflow generation and creation engine

Mobet's generation engine turns *what the user wants* into a runnable, policy-validated workflow
without contacting a network and without ever widening the authority the hand-authoring path
already has. It lives in `ai.arena.mobet.synthesis`.

```
goal text ─┐
recipe ────┼─► Intent IR ─► grounding ─► lowering ─► risk ─► policy ─► parse ─► PlanValidator ─► JSON + report
recording ─┘   (typed)      (snapshot)   (+waits,     (confirm  (least-      (Workflow.parse)
                                          expects,     gates)    privilege)
                                          control flow)
```

## Stages

### 1. Intent IR (`IntentIr.kt`)

Free text is parsed by a small closed grammar into typed nodes (`TapIntent`, `FillIntent`,
`WaitIntent`, `DelayIntent`, `BackIntent`, `HomeIntent`, `LaunchIntent`, `ConfirmIntent`,
`VerifyIntent`, `ConditionalIntent`, `RepeatIntent`). Anything the grammar does not recognise is
an error quoting the offending clause — an unrecognised clause is never silently dropped, because
a plan that quietly does less than the user asked for still looks complete.

Accepted clause forms (separate with `then`, `;`, or new lines):

| Form | Lowered to |
| --- | --- |
| `tap/click/press/select "X"` | `tap` |
| `open/go to/navigate to "X"` | `tap` + `expect.screenChange` |
| `fill/enter/type "Field" with "value"` | `fill` (grounded against editable controls only) |
| `scroll`, `scroll to "X"` | `scroll` |
| `wait for "X"` | `wait` |
| `wait 2s`, `wait 750ms` | `delay` |
| `back`, `home` | `back`, `home` |
| `launch com.example.app` | `launch` (+ allowlist entry) |
| `confirm "message"` | blocking `confirm` |
| `verify "X" appears` / `is gone` | `expect` block on the **previous** step |
| `if "X" appears then <clauses>` | `branch` + explicit join label |
| `repeat <clauses> until "X" appears max N` | backwards `repeatUntil` with a hard cap |

Budgets: ≤ 40 clauses, ≤ 8 clauses per control-flow body, no nested control flow, a program may
not begin with a verification.

### 2. Grounding (`SnapshotGrounder.kt`, `ScreenMemory.kt`)

Every target is matched against the live accessibility snapshot with `FuzzyText`, blending label
similarity (90%) with the snapshot's own selector confidence (10%). Three outcomes are kept
distinct on purpose:

* **Resolved** — one clearly-best control; the step is emitted with its exact selector.
* **Ambiguous** — several candidates within 6 score points. Emitted as a bounded `tryAlternates`
  (≤ 4 options) or, with `allowAlternates = false`, rejected. Never a coin-flip guess.
* **Not found** — rejected, with the closest visible control and its match percentage reported so
  the user can see how close they were.

Ranking is a deterministic total order (score, then selector string), so identical goal +
snapshot always yields byte-identical JSON.

**Rejection guidance.** `ClauseAdvisor` appends one specific suggestion to a grammar rejection —
unbalanced quotes, a delay without a unit, a fill without a value, a verb the grammar does not
accept (`swipe` → `scroll`), or a one-edit typo including transpositions (`tpa` → `tap`). It is
diagnostic only: it never repairs or re-interprets a clause, and it stays silent about verbs the
grammar already accepts.

**Multi-screen routes.** Real routes span screens, so grounding falls back to `SessionScreenMemory`
— a bounded, in-process graph of screens this session has actually shown (12 per package, 8
packages, keyed by structural identity, never written to disk). Memory grounding is strictly more
conservative than live grounding: only an unambiguous resolution counts, it never crosses a package
boundary, the live screen always wins, and the emitted step is *always* preceded by a `wait`, so a
route that no longer holds fails as a named timeout instead of tapping blind. Every
memory-grounded step says so in the report, with the age of the screen it came from. Screens are
linked by observed transitions, so candidates are searched in *route* order — the screens actually
reached from wherever the plan currently stands, ranked by how often that transition was seen,
before anything else by recency. When two screens of an app both hold a control with the same
label, the reachable one wins. Pass
`ScreenMemory.EMPTY` to restrict generation to the current screen.

### 3. Lowering and robustness (`WorkflowSynthesizer.kt`)

* A `wait` on the same selector precedes every tap/fill, so a slow screen fails as a named
  timeout instead of a mis-tap (duplicate waits are folded).
* Navigational taps assert `screenChange`; `verify` clauses become `textPresent`/`textAbsent`.
* Conditionals become `branch` with an explicit zero-length `delay` join label; loops become
  `repeatUntil` that always jumps backwards with an iteration cap of 1..50.

### 3b. Optimizer and parameterization (`PlanOptimizer.kt`, `PlanParameterizer.kt`)

Lowering is deliberately naive, so a deterministic peephole pass runs before risk gating:
consecutive waits on the same selector are collapsed, unreachable zero-length join markers are
dropped, and over-long runs of blind scrolls are trimmed. It only ever *removes* work, and never
removes a jump target, a step carrying an `expect`, or a label (labels move forward onto the
surviving step).

Literal `fill` values are then hoisted into named workflow variables (`{{var:search}}`), turning
a single-use plan into an editable template. Credential-looking values and existing
`{{var:…}}`/`{{secret:…}}` references are deliberately left alone — promoting a password into a
plaintext variable would make it more visible, not less.

### 4. Risk, policy, validation (`WorkflowAssembler.kt`)

Shared by *every* generation path:

* `RiskEngine` scores each emitted step — including each `tryAlternates` option as if it were a
  tap — and a blocking `confirm` is inserted before anything ELEVATED or above. If the risky step
  carries a control-flow label, the label **moves to the confirm**, so a jump cannot land past
  the gate.
* Policy is synthesized least-privilege: allowed packages = target (+ explicitly launched),
  allowed actions = exactly what was emitted plus `confirm`, `maxActions` = steps + 4, runtime
  budget from the plan's own declared timeouts, visual fallbacks and self-healing off.
* The JSON is then really parsed by `Workflow.parse` and run through `PlanValidator`. A generated
  plan that would fail validation is returned as an **error**, never as a document the user might
  run.

### 4b. Quality analysis (`PlanQuality.kt`)

Every generated plan is scored 0–100 (grade A–E) on *robustness*, which is a different question
from legality. This is advisory only: it can never block or rewrite a plan `PlanValidator` has
approved. Deductions cover unverified acting steps, text-only selectors that drift with app
wording or device language, plaintext credential values, loop caps near the maximum, and plans
that exactly fill their action budget. The summary appears in the generation report and in the
Policy validation sheet.

### 4c. Repair / re-grounding (`WorkflowRepair.kt`)

An existing plan can be re-grounded against the screen the user is on now: selectors that no
longer exist are fuzzy-rematched, and the result goes back through the ordinary risk → policy →
validate pipeline. It is authoring-time only (`allowSelfHealing` stays off), never retargets a
different package, refuses to guess when the new match is ambiguous, reports vanished controls
instead of silently dropping the step, and preserves values, expectations, labels and control
flow. Exposed as **Re-ground to screen** in the Policy validation sheet.

### 5. Trace synthesis (`TraceSynthesizer.kt`)

Stopping a recording no longer pastes raw events. The trace is normalised (selector fields,
duplicate taps dropped, scroll runs coalesced to ≤ 3, waits inserted before taps) and then goes
through the same back half. Typed text is still never recorded; fills must be added by hand with
`{{var:…}}` / `{{secret:…}}` references.

### 5b. Crystallizing an autonomous run (`AgentCrystallizer.kt`)

A verified autonomous run knows a route that worked. `AgentCrystallizer` converts the agent's
retained path (`AgentRunResult.successPath` — accepted, progress-making, non-backtracked
transitions only) into an ordinary workflow, so the next execution is a deterministic replay
instead of another exploration. Only `SUCCEEDED` runs crystallize; the run's own `successFact`
becomes an `expect` assertion on the final step; an action without a replayable selector fails the
whole conversion rather than silently shortening the route; and the result passes the same risk,
policy and validation gate as any other plan. The UI offers it as a snackbar action the moment a
goal verifies.

### 5c. Execution feedback (`SelectorOutcomes.kt`)

`WorkflowRunner` records whether each selector actually resolved, per package. Grounding consults
that tally through the `GroundingPriors` interface, so plans prefer selectors with a track record.
Weights decay with a 30-day half-life and are persisted through `EncryptedOutcomeJournal` (the
same AES-GCM/Keystore container as agent memory), flushed once at the end of a run rather than per
observation — without persistence the tally died with the process and almost never reached its
minimum-observation threshold. Hard limits: the adjustment is clamped to ±0.05 and applied to the *ranking score only* — the
grounding threshold is evaluated on raw similarity, so history can never resurrect a target that is
not on screen nor suppress one that is. It requires at least two observations, keeps a bounded LRU
of selector identities and counts (no screen text, values or secrets), and can be disabled entirely
with `SynthesisOptions(priors = NoGroundingPriors)`.

### 6. Recipes (`WorkflowRecipes.kt`)

Parameterised patterns (search, sign-in with stored secrets, navigate-and-toggle, scroll-until,
dismiss-then-act) expand into the *same goal DSL*, so they inherit every stage above. Parameters
are validated and quote/separator characters are rejected, so a parameter cannot inject extra
clauses into the generated program.

## Screen-borne template injection

Selector values come from a foreign app, and `WorkflowRunner.expand` substitutes `{{var:…}}` and
`{{secret:…}}` inside selector text and values at run time. A control literally labelled
`{{secret:bank.pin}}` could therefore get a generated plan to interpolate a stored secret into a
selector. Every construction path that copies a value from the screen — grounding, recorded
traces, crystallization, repair — goes through `SelectorSpec.of`, which refuses template syntax,
so such a control is simply not groundable and never reaches a document.

## Reports

Every long-form report (generated plan, dry run, policy validation, diagnostics, audit ledger) can
be copied or shared through the system chooser. A report that can only be read on the phone is
useless exactly when someone is trying to get help with a failure; only text the sheet already
displays is exported.

## OCR as a diagnostic

With the user's OCR consent, recognized text is passed to generation as `SynthesisOptions.ocrText`
— strictly to *explain* failures, never as a target source. Recognized pixels carry no selector, so
a step built from them could only be replayed through visual fallbacks. What the user gets instead
is the distinction between "that control does not exist" and "that control is drawn but exposes no
accessibility node", which have completely different fixes. No plan step is ever invented from OCR.

## Generation-time simulation

Plans are simulated with `PlanSimulator` at generation time, against the same snapshot they were
grounded in, and the dry-run report is folded into the synthesis report. "Step 4 will probably time
out" is visible *before* inserting, not after a failed run.

## What the user sees

The generated plan is presented as a report — grounding scores and chosen selectors, inserted
waits, alternates, risk confirmations with their reasons, and the synthesized policy — with an
explicit **Insert** action. Because Insert overwrites the editor, the report also carries a
step-level diff (`PlanDiff`, LCS over canonical step signatures) whenever the editor already holds
a parsable plan. Generation has no device effects; running still requires the existing separate,
explicit tap. **Save & schedule** names the plan, stores it in the library — the unit
`WorkflowTransfer` exports as a signed v2 bundle — and offers a run *reminder* (Mobet prompts; it
never starts a run itself). The authored document also carries a live robustness chip in the editor
summary, tappable for the finding list.

All of these flows live in `ui/GenerationController`, which talks to the activity through the
narrow `GenerationHost` surface (read document, replace document, status, schedule) rather than
owning views.

## Tests

`app/src/test/java/ai/arena/mobet/synthesis/` covers grammar acceptance and rejection, grounding
failure modes, control-flow lowering and label integrity, risk confirmation placement,
least-privilege policy, trace cleanup, recipe parameter injection, optimizer safety, quality
grading, repair boundaries, crystallization boundaries, and feedback-prior limits. Hardening adds
seeded grammar fuzzing (every rejection must be an explained error, every acceptance must validate),
a golden-goal corpus asserting byte-identical output across runs *and* across snapshot element
permutations, screen-borne template-injection defence, and diff integrity.
