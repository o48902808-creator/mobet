# Scheduled execution (Tier 3) — threat-model review

**Status: decision document. No code may ship on the strength of this file.**
This is the review the Tier-3 feature ("Tier 3 scheduled runs, WorkManager") required before
implementation. It names the invariant scheduled execution collides with, enumerates the new
threats honestly, defines the only implementation shape that could preserve Mobet's safety
posture, and records what the maintainer must explicitly approve before a first pull request.

Current state: scheduling exists only as **run reminders** (`RunReminder`) — a notification
that opens the app with a workflow loaded; the user still presses Run. That design is
load-bearing and documented in `docs/THREAT_MODEL.md` ("Scheduling posts a reminder
notification only") and in `RunReminder.kt`'s header comment.

---

## 1. What Tier 3 would change

Today every device action is causally downstream of a foreground user gesture (`Run workflow`
or an in-dialog confirmation). Tier 3 asks WorkManager to fire a saved workflow at a scheduled
time/interval with the app possibly closed and the user absent or asleep.

## 2. The invariant it collides with

> With nobody present, a confirmation prompt cannot be answered, a mis-grounded selector
> cannot be caught, and Stop cannot be pressed. — `README.md`, *Reminders, not unattended runs*

This is not a UX preference; it is the containment strategy for every other rail. All of the
following presuppose a present human on the failure path:

| Rail | Why it presumes presence |
| --- | --- |
| `confirm` / typed-`APPROVE` gates | Exist solely to transfer authority confirm-by-confirm. Unattended, they are either auto-denied (the run halts — a scheduled run that can't do anything) or weakened (unacceptable). |
| Risk tiers at execution time | `RiskEngine` rescoring protects against authored risk; drift between author-time and fire-time is precisely the case that needs eyes. |
| Stop (in-app + notification) | Works because someone watches the run. |
| Confirmation timeout (2 min, fail-closed) | Makes unattended runs *safe* by making them inert: any gated step ends the run. That is a defence of the current design, not an argument that Tier 3 is already safe. |

## 3. Threat deltas introduced by scheduled execution

- **T1 — wrong-context execution.** The foreground app, signed-in account, or even device
  holder at fire time may differ from author time. Interactive runs inherit the user's eyeballs;
  scheduled runs do not.
- **T2 — confirmation semantics fork.** Three options: (a) auto-deny every gate → scheduled
  plans limited to confirm-free LOW-risk steps; (b) queue the prompt for later → a gate detached
  from the screen it protects authorizes blind; (c) skip gates for schedules → violates the
  core invariant. Only (a) is ever acceptable.
- **T3 — stale-plan execution.** Apps update; selectors drift. Self-healing is LOW-risk-only and
  one-shot, which is compatible, but a healed/drifted target on an unwatched device can act on
  the wrong control silently. The ledger records it; nobody watches it live.
- **T4 — trust erosion.** A phone that visibly operates itself confuses bystanders and teaches
  the user to distrust the device. The ongoing notification requirement exists for interactive
  runs; Tier 3 needs it enforced *before*, not merely during.
- **T5 — scheduler semantics.** WorkManager guarantees eventual delivery, not exactness;
  `setExactAndAllowWhileIdle` needs `SCHEDULE_EXACT_ALARM` (a special permission since
  Android 12) and Doze still distorts timing. A "run at 9:00" that fires at 9:40 must not be
  silently wrong.
- **T6 — worm-shaped bundles.** Imports merge workflows; a bundle that could *also* plant a
  schedule would turn a shareable JSON file into a persistent behavior implant. Scheduling must
  never be importable, only per-device, per-saved-workflow, set by hand.
- **T7 — repeat amplification.** Any scheduled run repeats by default in users' minds. A
  recurring unattended misfire is not a bug that happens once; it happens daily until noticed.

## 4. The only implementation shape that could be accepted

If Tier 3 is ever built, all of the following are non-negotiable, in code and in review:

1. **Opt-in per saved workflow, default off.** Scheduling is a per-workflow toggle with its own
   destructive-style confirmation at enable time, stating plainly what can happen unattended.
   Never settable by an imported bundle.
2. **LOW-risk, gate-free plans only.** At fire time (not author time) the plan is re-parsed and
   must satisfy: every step scores `RiskTier.LOW` or below; zero `confirm` steps; no
   gesture/visual actions (`tappoint`, `swipe`, `capture`, `ocrwait`, `visualtap`); no `launch`
   outside the single static target; budgets shrunk (e.g. maxActions ≤ 20, maxRuntimeMs ≤ 60s).
   **Any violation converts the firing into the existing reminder notification instead of
   executing** — the safe degrade that keeps a stale plan from acting blindly.
3. **All existing rails run unchanged.** Same `PlanValidator` preflight, same `RiskEngine`
   rescoring (variable look-ahead included), same `WorkflowRunner`, budgets, loop guard,
   redaction and ledger — plus a ledger event `scheduled execution fired for “name”` so the
   audit trail distinguishes scheduled from interactive runs.
4. **Prominent notification before and during**, withStop. A heads-up "Mobet will run 'name'
   in 1 minute — tap to cancel" pre-fire, then the standard ongoing emergency-Stop
   notification while executing. Never silent.
5. **Environment gates at fire time.** Do not execute when the device is locked (unless the
   user explicitly accepted lock-screen execution), when another package is in the foreground
   that is not the target, or under the existing `ResourceGovernor` battery/thermal blocks.
   Each veto converts to a reminder instead of failing silently.
6. **Caps and cooldowns.** Per-schedule daily execution cap, per-schedule minimum interval,
   and automatic disable after N consecutive failures with its reason shown in the run
   reminders screen.
7. **No new network surface.** WorkManager adds no network permission (verified by the
   existing merged-manifest check in CI), and no `SCHEDULE_EXACT_ALARM` unless a later review
   shows exactness is truly needed.
8. **Tests before merge.** JVM tests for the fire-time policy gate (LOW-only, gate-free,
   shrink-budget, degrade-to-reminder) and a connected-device test that a scheduled LOW-risk
   plan runs its notification path. No emulator → no merge.

## 5. Alternatives that deliver most of the value with no invariant change

Before building §4 at all, these are cheaper and safer:

1. **Recurring reminders.** Today's reminder is one-shot; making it recurring (daily/weekly)
   covers "run my backup check every morning" while keeping the user in the loop.
2. **A "Run now" notification action.** The reminder notification gains a button that starts
   the run directly — still a foreground gesture by a present human, keeping the trust chain
   intact while removing the "open app, find workflow, press Run" friction. This is the
   recommended next step if schedule convenience is the real pain point.

## 6. Decision record (maintainer sign-off required before code)

The feature is approved **only if** the maintainer explicitly accepts, in review:

- [ ] §4 items 1–8 as binding constraints (not goals).
- [ ] Scheduled execution is capped at LOW-risk, gate-free plans — permanently.
- [ ] Violating plans degrade to reminders; they never execute partially.
- [ ] Alternative §5.2 (notification "Run now") has been tried first or consciously rejected.
- [ ] An update to `docs/THREAT_MODEL.md` and `README.md` ("Reminders, not unattended runs")
      ships in the same PR as the first line of scheduling code; this document is amended to
      "implemented under review" and deleted as a gate.

Until then, the correct engineering answer to "run it unattended" remains: **Mobet reminds
you; you run it.**
