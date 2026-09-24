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

### 2. Grounding (`SnapshotGrounder.kt`)

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

### 3. Lowering and robustness (`WorkflowSynthesizer.kt`)

* A `wait` on the same selector precedes every tap/fill, so a slow screen fails as a named
  timeout instead of a mis-tap (duplicate waits are folded).
* Navigational taps assert `screenChange`; `verify` clauses become `textPresent`/`textAbsent`.
* Conditionals become `branch` with an explicit zero-length `delay` join label; loops become
  `repeatUntil` that always jumps backwards with an iteration cap of 1..50.

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

### 5. Trace synthesis (`TraceSynthesizer.kt`)

Stopping a recording no longer pastes raw events. The trace is normalised (selector fields,
duplicate taps dropped, scroll runs coalesced to ≤ 3, waits inserted before taps) and then goes
through the same back half. Typed text is still never recorded; fills must be added by hand with
`{{var:…}}` / `{{secret:…}}` references.

### 6. Recipes (`WorkflowRecipes.kt`)

Parameterised patterns (search, sign-in with stored secrets, navigate-and-toggle, scroll-until,
dismiss-then-act) expand into the *same goal DSL*, so they inherit every stage above. Parameters
are validated and quote/separator characters are rejected, so a parameter cannot inject extra
clauses into the generated program.

## What the user sees

The generated plan is presented as a report — grounding scores and chosen selectors, inserted
waits, alternates, risk confirmations with their reasons, and the synthesized policy — with an
explicit **Insert** action. Generation has no device effects; running still requires the existing
separate, explicit tap.

## Tests

`app/src/test/java/ai/arena/mobet/synthesis/` covers grammar acceptance and rejection, grounding
failure modes, determinism, control-flow lowering and label integrity, risk confirmation
placement, least-privilege policy, trace cleanup, and recipe parameter injection.
