# Mobet

Mobet is an Android-first, on-device mobile automation prototype. It uses Android's Accessibility API to locate controls and run explicit JSON workflows that the phone owner starts.

## Frontier capabilities (v0.3.1)

- **Bounded autonomous intelligence** — a platform-neutral `AutonomousAgent` repeatedly observes, deliberates, acts, and verifies instead of blindly replaying a plan. Package, risk, and cycle budgets remain hard boundaries enforced independently of the deliberation policy.
- **Verified exploration and backtracking** — safe reversible actions may be explored when no learned route exists. Unchanged screens, rejected actions, loops, and exhausted branches become dead ends; the agent backtracks rather than repeating them.
- **Experience-guided navigation** — a bounded `ExperienceStore` retains successful transitions and dead ends. `ExperienceNavigator` searches this learned graph without cycles, while every proposed action remains subject to live verification.
- **Counterfactual agent validation** — autonomous proposals are rejected if they exceed risk or cycle limits, and irreversible actions may only be terminal. The device adapter retains final authority over execution.
- **Deterministic autonomy benchmark** — ten synthetic navigation tasks must maintain a 100% success rate in no more than 30 total action cycles, alongside focused tests for risk abstention, graph cycles, dead-end memory, and backtracking.
- **Graduated risk engine** — every step is scored by a deterministic, explainable `RiskEngine` (consequential, destructive, financial, and credential signals are additive). `ELEVATED` steps require an adjacent confirm; `CRITICAL` steps (e.g. "Confirm transfer of $500") escalate to a **hardened typed confirmation** where the user must literally type `APPROVE`.
- **Self-healing selectors (opt-in)** — when an app update renames "Network & internet" to "Network and internet" or rotates a resource ID, the runner can heal the selector against the live screen using blended Jaccard + Levenshtein similarity. Healing is policy-gated (`allowSelfHealing`), limited to LOW-risk steps, one-shot per step, requires both a confidence floor *and* a margin over the runner-up so it abstains rather than guesses, and every heal is logged.
- **Counterfactual dry run** — `Dry run` statically walks the plan against the last accessibility snapshot without touching the device: per-step grounding grades (✔ / ≈ / ✖), risk tiers, confirmation gates, and an estimated duration. Secret and literal fill values are masked in the report.
- **Loop guard + screen fingerprinting** — each observed screen is hashed into an order-insensitive structural fingerprint; a screen revisited too many times without progress aborts the run instead of burning the runtime budget.
- **On-device world model** — Mobet passively learns a screen-transition graph (`fingerprint --action--> fingerprint`) while workflows run. Only structural hashes and the selectors from your own workflows are stored, bounded to 400 edges, never leaving the device. It gives future planners a grounded navigation prior.
- **Tamper-evident audit ledger** — every runner event is appended to a SHA-256 hash chain (`hash = H(prev | seq | ts | event)`). The **Audit ledger** screen re-verifies the whole chain and pinpoints the first broken link if the history was altered.
- **Risk-aware goal compiler** — the offline planner now shares the exact same `RiskEngine` as the runner, inserts confirmations with the *reason* attached, grounds `fill` clauses only against editable elements, understands `go back` and `scroll`, and uses typo-tolerant fuzzy grounding.
- **JVM unit test suite + CI** — deterministic components (risk engine, validator, fuzzy matcher, resolver, fingerprints, planner, simulator, parser) are covered by plain JUnit tests, run in GitHub Actions on every push alongside a debug APK build.

## Current MVP

- Launch an installed application by package name
- Locate controls by visible text, accessibility description, or resource ID
- Tap controls (including a clickable parent)
- Focus and fill text fields
- Scroll, wait, delay, Back, and Home actions
- Per-step polling, bounded retries, timeout, status, Stop, and fail-fast handling
- Variables and conditional `ifText` / `unlessText` execution
- AES-GCM secrets protected by a non-exportable Android Keystore key
- Blocking, user-visible confirmation steps for consequential actions
- Editable workflow JSON stored locally on the device
- Named local workflow library with Save and Load
- Installed-app picker for recording
- Tap and scroll recorder that walks up parent controls to find stable selectors
- Last-screen visual inspector with roles, bounds, uniqueness counts, and selector confidence
- Duplicate-selector warnings and copyable selector diagnostics
- Persistent rolling execution diagnostics (last 100 status events)
- Display-relative tap/swipe fallback with coordinates clamped to safe screen bounds
- Consent-gated Android 11+ screenshots stored only in private app storage
- In-app screenshot viewer and deletion control
- Bundled on-device Latin-script OCR with line bounds and confidence heuristics
- Consent-gated `ocrWait` and `visualTap` workflow actions
- Visual text matching with normalized whitespace and exact-match preference
- Mandatory preflight policy validation for every plan
- Package and action allowlists, action budgets, and runtime deadlines
- Consequential-action detection with required adjacent confirmation
- Runtime package-boundary enforcement
- Offline natural-language goal compiler grounded in inspected screen elements
- Ambiguity rejection, confidence thresholding, and automatic safety confirmations
- Model-neutral JSON plan boundary for future local or hosted planners
- Included harmless Android Settings demonstration

No data is sent off-device. This prototype does not include a network permission.

## Build and install

Prerequisites: Android Studio Ladybug or newer, Android SDK 35, and JDK 17.

1. Open this directory in Android Studio.
2. Let Gradle sync and install SDK 35 if prompted.
3. Run the `app` configuration on an Android 8.0+ physical device or emulator.
4. In Mobet, tap **Open accessibility settings**, select **Mobet automation**, and enable it.
5. Return to Mobet and run the included Settings demo.

> The repository intentionally does not commit the generated Gradle wrapper JAR. Android Studio can sync the project directly; you can also run `gradle wrapper` with Gradle 8.9 installed.

## Workflow format

```json
{
  "name": "Profile update",
  "package": "com.example.app",
  "steps": [
    { "action": "wait", "text": "Profile", "timeoutMs": 5000 },
    { "action": "tap", "text": "Profile" },
    { "action": "fill", "viewId": "com.example.app:id/name", "value": "Ama Mensah" },
    { "action": "scroll", "viewId": "com.example.app:id/form" },
    { "action": "tap", "description": "Save" },
    { "action": "delay", "delayMs": 1000 },
    { "action": "back" }
  ]
}
```

Selectors can use `text`, `viewId`, and/or `description`. Multiple selector properties are combined. Supported actions are `wait`, `tap`, `fill`, `scroll`, `delay`, `confirm`, `tapPoint`, `swipe`, `capture`, `ocrWait`, `visualTap`, `back`, and `home`. Timeouts are capped at 60 seconds, delays at 10 seconds, retries at 10, and gestures at 5 seconds.

Define non-sensitive values in the root `variables` object and reference them as `{{var:name}}`. Save sensitive values from **Secrets** and reference them as `{{secret:name}}`; plaintext secret values are never stored in workflow JSON. Add `ifText` or `unlessText` to conditionally execute a step based on the current screen. Place a `confirm` step immediately before any consequential action:

```json
{
  "action": "fill",
  "viewId": "com.example.app:id/password",
  "value": "{{secret:account_password}}",
  "retries": 2
},
{
  "action": "confirm",
  "message": "Submit this form to Example App?"
},
{
  "action": "tap",
  "text": "Submit",
  "unlessText": "Already submitted"
}
```

## Safety and platform notes

- Android displays a strong warning when enabling accessibility access because this capability can read and operate screen content. Only enable services you trust.
- Mobet runs only a workflow explicitly started in its foreground UI and offers Stop. Use blocking `confirm` steps before sensitive actions; remote triggers and AI planning remain disabled.
- `QUERY_ALL_PACKAGES` supports user-authored package targets in sideloaded builds. Google Play restricts this permission; a Play-distributed edition should use declared package visibility or a user-selected app model.
- Secure fields, CAPTCHAs, biometrics, protected windows, and apps with poor accessibility metadata may not be automatable and should not be bypassed.
- iOS does not permit an ordinary installed app to control arbitrary other apps; Android is the initial target.

## Recording a workflow

Tap **Record**, choose a target app, and perform the taps and scrolls you want Mobet to reproduce. Return to Mobet using Android's app switcher and tap **Stop / import recording**. Recorded selectors are appended to the editor. For privacy, typed text is never recorded; add `fill` steps and values manually. Use **Save** to keep a named workflow locally.

After visiting a target app, select **Inspect last app screen** to review actionable elements exposed by Android. Mobet scores selectors by stability and uniqueness, warns when multiple nodes match, and lets you copy selector details. **Diagnostics** shows the rolling local execution log.

## Visual fallbacks and captures

Use semantic selectors whenever possible. For inaccessible canvases only, `tapPoint` accepts `xPercent` and `yPercent`; `swipe` also requires `endXPercent` and `endYPercent`. Values are display-relative and clamped to 2–98% of screen bounds. Both actions require an immediately preceding approved `confirm` step.

The Android 11+ `capture` action also requires an immediately preceding confirmation. PNG files remain in private app storage with no sharing or network upload. Use **Captures** to inspect, OCR, or delete the latest image.

`ocrWait` verifies that specified `text` is visually present. `visualTap` finds that text and taps its recognized center. Both are Android 11+ fallbacks, run through a bundled on-device OCR model, and require an immediately preceding confirmation. Accessibility selectors remain preferred because OCR can misread stylized, low-contrast, or non-Latin text.

```json
{ "action": "confirm", "message": "Use OCR to locate Continue?" },
{ "action": "visualTap", "text": "Continue" }
```

## Constrained planning policy

Every workflow—including future AI-proposed plans—is rejected before launch unless it satisfies its policy. The runner also enforces runtime and package boundaries while executing.

```json
"policy": {
  "allowedPackages": ["com.example.app"],
  "allowedActions": ["wait", "tap", "fill", "confirm"],
  "maxActions": 30,
  "maxRuntimeMs": 120000,
  "allowVisualFallbacks": false,
  "allowSelfHealing": false
}
```

Visual actions must be explicitly allowed and enabled. Coordinate, screenshot, and OCR actions always require an adjacent confirmation. Instead of a binary keyword list, the `RiskEngine` scores each step across consequential, destructive, financial, and credential signals: `ELEVATED` steps require an immediately preceding `confirm`, and `CRITICAL` steps additionally demand a typed `APPROVE` at runtime. Select **Validate plan policy** for the static verdict or **Dry run** for the full counterfactual preflight report.

### Self-healing selectors

Set `"allowSelfHealing": true` to let the runner repair a selector that no longer matches after an app update. Healing only ever runs for steps scored at or below LOW risk, fires at most once per step, must clear a 72% confidence floor plus an 8% margin over the second-best candidate, and appears in the diagnostics and audit ledger as `healed to "…" via text (94%)`. Consequential steps are never healed — they fail loudly instead.

## Grounded goal planning

After visiting a target screen, choose **Generate plan from goal**. The offline compiler supports clauses beginning with `tap`, `click`, `open`, `select`, `choose`, `wait`, `find`, `locate`, `fill`, `enter`, or `type`, separated by “then”, semicolons, or new lines. Quote labels for precision:

```text
tap "Profile" then fill "Email" with "ama@example.com"
```

Every target must resolve confidently and uniquely against the inspected accessibility snapshot. Ambiguous or hallucinated targets are rejected. Consequential clauses receive confirmation steps automatically, and the finished JSON is policy-validated before entering the editor. This compiler is intentionally deterministic and offline; future model-based planners must emit the same schema and cannot bypass `PlanValidator`.

## Trust, transparency, and memory

- **Audit ledger** — a bounded, on-device, hash-chained log of every runner event. Open **Audit ledger** to verify the chain (`✔ Hash chain verified`) or detect tampering, and clear it at any time. Events contain runner status text only — never screen content or secret values.
- **World model** — open **World model** to see how many screens and transitions Mobet has learned for the apps you automate. The graph stores only 16-character structural fingerprints plus the workflow's own action selectors, is capped at 400 edges with least-recently-seen eviction, and can be cleared with one tap.
- **Loop guard** — if the same screen fingerprint is observed more times than the plan could legitimately need (steps + slack) without a structural change, the run aborts with a diagnostic rather than looping until the runtime budget expires. This is the hard rail that makes future bounded replanning safe to add.

## Testing and CI

JVM unit tests cover every deterministic component: `RiskEngine` tiers, `PlanValidator` rules, `FuzzyText` similarity, `SelectorResolver` healing and abstention, `ScreenFingerprint` stability, `GoalPlanner` grounding/rejection, `PlanSimulator` reports (including secret masking), and `Workflow` parsing bounds.

```bash
gradle test            # run the JVM unit suite
gradle assembleDebug   # build the debug APK
```

GitHub Actions (`.github/workflows/android-ci.yml`) runs both on every push and pull request and uploads test reports plus the debug APK as artifacts.

## Next milestones

1. Multi-screen observe-plan-act loop with bounded replanning driven by the learned world model
2. Instrumented on-device test suite and signed APK pipeline
3. Performance, compatibility, and accessibility hardening across real devices
