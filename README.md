# Mobet

Mobet is an Android-first, on-device mobile automation prototype. It uses Android's Accessibility API to locate controls and run explicit JSON workflows that the phone owner starts.

## Frontier capabilities (v0.6.0)

- **Device-reliability layer** — autonomous decisions require structurally settled observation quorums with strict sample deadlines. Resource governance blocks new operations under severe thermal pressure or critically low battery, while generation-scoped callbacks eliminate lifecycle races.
- **Executable hierarchy and probabilistic planning** — subgoals now run through an explicit state machine whose preconditions and completion evidence advance only after observed transitions. Bayesian learned-route reliability, bounded beam-style lookahead, reversible-action utility, and local replanning cooperate under hard budgets.
- **Consent-controlled multimodal perception** — the run dialog can opt into on-device OCR for completion evidence. OCR is source-attributed, confidence-gated, temporally fused, requires repeated observations, is never persisted, and never creates tap authority; accessibility remains canonical.
- **Operational model assistance** — an optional structured on-device assistant decomposes clauses and ranks only deterministic-policy-approved action IDs. Output schemas reject invented IDs and injection patterns, and model influence is capped to a small tie-break rather than safety authority.
- **Production security controls** — both memory and the audit ledger are AES-GCM authenticated with Android Keystore keys and atomic migration. Fresh-snapshot action canonicalization, package provenance, prompt-injection filtering, anti-replay checkpoints, least-privilege package visibility, CodeQL, dependency review, and Android lint are enforced.
- **100-world adversarial evaluation** — seeded generated worlds cover UI wording drift, loading interruptions, ambiguity, prompt injection, deceptive financial controls, and risk abstention, with hard success, cycle, and zero-safety-violation assertions.
- **Operational resilience** — encrypted crash checkpoints never auto-resume or replay irreversible operations. Active runs expose an ongoing notification with emergency Stop, remain explicitly user-started, and retain in-app Stop as a fallback.
- **Adversarially hardened autonomy** — every proposed selector is canonicalized against a fresh live snapshot immediately before execution; foreign-package success spoofing, stale callbacks, screen-borne prompt injection, forged action metadata, and transient one-frame completion evidence fail closed. Autonomous runs have independent cycle, search-expansion, candidate, depth, retry, package, risk, and wall-clock budgets.
- **Encrypted adaptive intelligence** — episodic and semantic memory is AES-GCM encrypted with namespace-bound authenticated data and a non-exportable Android Keystore key. Bayesian route reliability resists one-shot overfitting; temporal evidence decays; repeated prediction contradictions trigger structural-drift invalidation; legacy plaintext metadata migrates atomically and is deleted only after a successful encrypted commit.
- **Zero-write CI security posture** — CI runs with read-only repository permissions and no persisted checkout credential, performs deterministic intelligence tests plus Android security lint, then assembles the APK and publishes reports. The app rejects cleartext traffic and protects its UI with `FLAG_SECURE`.
- **Live Apex Android agent** — accessibility snapshots are converted into node-free `AgentObservation`s and selected tap/scroll/back actions return through a narrow guarded gateway. Every operation is translated to a typed workflow, rescored by `RiskEngine`, validated by `PlanValidator`, confirmed when required, and executed only by `WorkflowRunner`. The UI exposes bounded goal runs with explicit completion evidence and Stop.
- **Persistent private memory** — successful and failed transitions, dead ends, selector-repair hashes, confidence, recency, and app-version metadata survive restarts. Confidence decays over 45 days, dead ends expire, major app versions invalidate old routes, storage is bounded, and screen/OCR text, entered values, secrets, and screenshots are excluded.
- **Hierarchical, uncertainty-aware planning** — goals decompose into subgoals with explicit preconditions and completion evidence. Accessibility, optional OCR, user, and world-model evidence remain separately attributed in a belief state; ambiguous candidates cause abstention instead of a guess.
- **Budgeted lookahead and recovery** — deterministic candidate ranking has hard action and expansion caps and optimizes learned success, semantic fit, risk, confidence, and reversibility. Failures are classified as stale selectors, loading delays, modal interruptions, wrong app, permission gates, device rejection, or dead ends, each with a bounded recovery policy.
- **Non-authoritative model boundary** — an optional model can return only structured subgoal suggestions or rankings over already-safe action IDs. Schema validation rejects unknown IDs; models never receive accessibility authority and cannot bypass policy, confirmation, execution, or verification.
- **Intelligence regression gates** — deterministic benchmarks track success, cycles, unnecessary actions, abstention quality, and safety violations with CI-enforced thresholds.
- **Bounded autonomous intelligence** — a platform-neutral `AutonomousAgent` repeatedly observes, deliberates, acts, and verifies instead of blindly replaying a plan. Package, risk, and cycle budgets remain hard boundaries enforced independently of the deliberation policy.
- **Verified exploration and backtracking** — safe reversible actions may be explored when no learned route exists. Unchanged screens, rejected actions, loops, and exhausted branches become dead ends; the agent backtracks rather than repeating them.
- **Experience-guided navigation** — a bounded `ExperienceStore` retains successful transitions and dead ends. `ExperienceNavigator` searches this learned graph without cycles, while every proposed action remains subject to live verification.
- **Counterfactual agent validation** — autonomous proposals are rejected if they exceed risk or cycle limits, and irreversible actions may only be terminal. The device adapter retains final authority over execution.
- **Deterministic autonomy benchmark** — thirty synthetic navigation tasks must maintain a 100% success rate in no more than 90 total action cycles, alongside focused tests for risk abstention, graph cycles, dead-end memory, belief ambiguity, failure policies, and backtracking.
- **Graduated risk engine** — every step is scored by a deterministic, explainable `RiskEngine` (consequential, destructive, financial, and credential signals are additive). `ELEVATED` steps require an adjacent confirm; `CRITICAL` steps (e.g. "Confirm transfer of $500") escalate to a **hardened typed confirmation** where the user must literally type `APPROVE`.
- **Self-healing selectors (opt-in)** — when an app update renames "Network & internet" to "Network and internet" or rotates a resource ID, the runner can heal the selector against the live screen using blended Jaccard + Levenshtein similarity. Healing is policy-gated (`allowSelfHealing`), limited to LOW-risk steps, one-shot per step, requires both a confidence floor *and* a margin over the runner-up so it abstains rather than guesses, and every heal is logged.
- **Counterfactual dry run** — `Dry run` statically walks the plan against the last accessibility snapshot without touching the device: per-step grounding grades (✔ / ≈ / ✖), risk tiers, confirmation gates, and an estimated duration. Secret and literal fill values are masked in the report.
- **Loop guard + screen fingerprinting** — each observed screen is hashed into an order-insensitive structural fingerprint; a screen revisited too many times without progress aborts the run instead of burning the runtime budget.
- **On-device world model** — Mobet passively learns a screen-transition graph (`fingerprint --action--> fingerprint`) while workflows run. Only structural hashes and the selectors from your own workflows are stored, bounded to 400 edges, never leaving the device. It gives future planners a grounded navigation prior.
- **Tamper-evident audit ledger** — every runner event is appended to a SHA-256 hash chain (`hash = H(prev | seq | ts | event)`). The **Audit ledger** screen re-verifies the whole chain and pinpoints the first broken link if the history was altered.
- **Risk-aware goal compiler** — the offline planner now shares the exact same `RiskEngine` as the runner, inserts confirmations with the *reason* attached, grounds `fill` clauses only against editable elements, understands `go back` and `scroll`, and uses typo-tolerant fuzzy grounding.
- **JVM unit test suite + CI** — deterministic components (risk engine, validator, fuzzy matcher, resolver, fingerprints, planner, simulator, parser) are covered by plain JUnit tests, run in GitHub Actions on every push alongside a debug APK build.

## Interface

![Mobet redesigned interface](docs/screenshots/overview.png)

> The previews above are rendered from the app's own resource files (`colors.xml`,
> `dimens.xml`, `strings.xml` and the `ic_*.xml` vector paths) so they cannot drift from what
> the app ships. They approximate Android's layout engine rather than being emulator captures —
> verify on a device before relying on exact metrics.

Mobet's control surface is a Material 3 layout organised around the task you are doing, not
the order the features were built.

- **Service card** — the enabled/disabled state is the first thing on screen, tinted green or
  red, with the Accessibility shortcut shown only while it is still needed. `Run workflow`
  stays disabled until the service is connected, so the primary action can never silently fail.
- **Workflow editor** — a monospaced JSON field with one-tap reformatting and a full-screen
  editing mode. Summary chips parse the buffer on every keystroke and report the target
  package, step budget, runtime budget, visual-fallback and self-healing posture, and the
  policy verdict, so an invalid plan is visible while you type rather than at run time.
- **Plan & autonomy / Inspect & verify** — the twelve former buttons are grouped into two
  labelled tiles grids with icons: generate plan, bounded agent run, dry run; inspect screen,
  diagnostics, captures, audit ledger, agent memory, validate policy.
- **Activity log** — a timestamped, scrollable history of the last 80 events replaces the
  single overwritten status line, so nothing is lost when a message is superseded. Important
  messages also raise a tone-coded snackbar anchored above the run bar.
- **Report sheets** — diagnostics, ledger, dry-run output, memory, OCR results and validation
  results open in scrollable bottom sheets with selectable monospaced text, pass/fail banners
  and purpose-built empty states, instead of truncated alert dialogs.
- **Safety affordances** — the hardened `APPROVE` confirmation keeps its Confirm button
  disabled until the exact word is typed, and every destructive control (clear ledger, clear
  agent memory, delete a secret or capture) is behind an explicit second confirmation.
- Edge-to-edge insets, 48dp touch targets, content descriptions, a light/dark palette mapped
  to Material 3 colour roles, and an in-app "How Mobet works" sheet.

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

No data is sent off-device. This build does not include a network permission. See the explicit [threat model](docs/THREAT_MODEL.md) for enforced invariants and residual risks.

## Build and install

Prerequisites: Android Studio Ladybug or newer, Android SDK 35, and JDK 17.

1. Open this directory in Android Studio.
2. Let Gradle sync and install SDK 35 if prompted.
3. Run the `app` configuration on an Android 8.0+ physical device or emulator.
4. In Mobet, tap **Open accessibility settings**, select **Mobet automation**, and enable it.
5. Return to Mobet and run the included Settings demo.

### “Restricted setting” — the Accessibility toggle is greyed out

On Android 13 and newer, Accessibility access is a **restricted setting**: apps installed
outside an app-store session (for example by tapping a downloaded APK, such as the
`mobet-debug-apk` CI artifact) cannot be granted it until the restriction is lifted. The
toggle appears disabled and tapping it shows a *Restricted setting* dialog. Mobet is not
broken — the permission is gated by the system.

To unlock it:

1. **Settings › Apps › See all apps › Mobet** — or tap **“Toggle greyed out?”** on Mobet's
   home screen, which deep-links there.
2. Tap the **⋮ menu in the top-right of the App info page** (not in the Accessibility menu).
3. Tap **Allow restricted settings** and confirm with your PIN, pattern or biometric.
4. Return to **Settings › Accessibility › Mobet automation** and enable it.

Installs performed with `adb install -r -g app-debug.apk`, or launched from Android Studio,
are exempt from this restriction. Some OEM skins (Xiaomi/HyperOS, Samsung One UI, Realme)
relocate or further gate the option.

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
- Mobet runs only a workflow or bounded Apex goal explicitly started in its foreground UI and offers Stop. Sensitive actions still require blocking confirmation; remote triggers remain disabled, and optional model assistance has no execution authority.
- Package discovery uses a least-privilege launcher `<queries>` declaration rather than `QUERY_ALL_PACKAGES`; non-launchable/private packages are intentionally outside the picker and autonomous launch boundary.
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

1. Instrumented on-device coverage for OEM-specific accessibility trees and interruption handling
2. Signed APK pipeline plus reproducible release provenance
3. Performance, compatibility, and accessibility hardening across real devices
