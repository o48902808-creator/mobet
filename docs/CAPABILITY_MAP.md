# Capability map

Deep inventory of what Mobet implements today, what is deliberately absent, what it integrates
with, and where integration could go next. Compiled from source (round-8 audit); each entry
names the code that owns it so the map can be re-verified rather than trusted.

## 1. Implemented capabilities

### 1.1 Workflow authoring

| Capability | Detail | Owner |
| --- | --- | --- |
| JSON editor | Monospaces, syntax highlighting, live policy/summary chips (debounced; re-parse + PlanValidator on every commit-width change) | `MainActivity`, `JsonHighlighter` |
| Visual step builder | Card view of editable actions (tap/fill/scroll/wait/delay/confirm/back/home/launch) with masked fill previews; gesture/OCR actions stay JSON-only | `StepBuilder` |
| Tap recorder | Records taps/scrolls as portable selectors in a chosen target app (viewId → description → text walk, 350 ms dedup, 50-step bound); text entry deliberately never captured | `InteractionRecorder` |
| Workflow library | Named workflows in private storage; reminders, save/load, duplicate-suffix import | `MainActivity`, `WorkflowTransfer` |
| Sample workflow | Bundled Settings demo preloaded on first run; draft + caret survive rotation/process death | `MainActivity.loadWorkflowSource` |

### 1.2 Execution engine (`WorkflowRunner`, `MobetAccessibilityService`, `ScreenInspector`, `SelectorResolver`)

- **14 actions**: `wait`, `tap`, `fill`, `scroll`, `delay`, `confirm`, `back`, `home`,
  `launch`, `tappoint`, `swipe`, `capture`, `ocrwait`, `visualtap`.
- Selector seek with per-step timeout (100 ms–60 s), retries (≤10), ifText/unlessText
  conditional skip, runtime budget checked inside loops (`maxRuntimeMs`, 5 s–15 min),
  `maxActions` (≤200), variables plus `{{secret:name}}` resolution at fill time, self-healing
  selectors behind `allowSelfHealing` with confidence + margin + LOW-risk-only guardrails.
- Guard rails proved in tests: launch targets re-checked against `policy.allowedPackages`
  immediately before switching apps; a confirmation cannot be satisfied twice while it is
  re-presented (per-gate generation counter); the gate fails closed on service loss; every
  emitted status line passes `FillValueMask` so no secret literal reaches UI, status, or the
  audit ledger via a quoted selector.

### 1.3 Risk, policy, and confirmation

- `RiskEngine`: deterministic additive scoring over consequential, destructive, financial
  (multi-currency, incl. GH₵/₦), and credential regex signals; visual actions carry base risk.
  Tiers: NONE/LOW/ELEVATED/CRITICAL.
- `PlanValidator`: static gate shared by authored, recorded, imported and generated plans —
  target package allowlist, action allowlist, step budget, visual-fallback switch, launch
  boundary, ELEVATED+ must be immediately preceded by `confirm`.
- Confirmations: ELEVATED → blocking dialog; CRITICAL → typed `APPROVE` confirmation with the
  confirm button disabled until the literal matches; service-side timeout and fail-closed
  behavior; denial path tested in the walkthrough and Espresso suite.

### 1.4 Autonomous agent (`agent/…`, `LiveAndroidAgent`, `synthesis/`, `planner/`)

- Deterministic goal compiler (`synthesis/WorkflowSynthesizer`) grounded in the last accessibility snapshot;
  ungrounded clauses reject; risk-driven confirmations inserted through the same validator.
- Hierarchical execution: `HierarchicalPlanner` + `HierarchicalExecutor` (subgoal completion
  requires observed evidence — explicit facts or a semantic screen transition — never the
  planner's claim).
- Deliberation: budgeted candidate set, dead-end memory, `BudgetedLookahead` utility, backtracking.
- Robustness: `ObservationStabilizer` (settling quorum; deadline settles only into abstain),
  `FailureClassifier`-driven recovery policies, `ResourceGovernor` (battery/thermal),
  encrypted crash checkpoint (`RunCheckpointStore`) that never auto-resumes, completion
  quorum across observations, optional consented OCR completion evidence, package-boundary
  watchdog, agent cycle/runtime budgets.
- Injection screening: `ContentTrustEngine` normalizes zero-width/confusable/leet text before
  best-effort pattern checks; flagged elements are displayable but never actionable —
  structural controls (risk engine, validator, confirmation gates) stay the real boundary.
- Model-neutral boundary: `ModelAssistant` contract (local structured assistant today) — a
  model may only rank pre-approved canonical action IDs, output passes `ModelOutputValidator`,
  and every action still crosses the risk/policy/confirmation gates.
- New this round (`StateOfThoughtPolicy`): state-conditioned evidence weighting — see
  [STATE_OF_THOUGHT.md](STATE_OF_THOUGHT.md). Degraded regimes damp OCR/world-model evidence
  toward non-zero floors; ground-truth channels never move; healthy regime is provably the
  old static behavior.

### 1.5 Vision

- Bundled ML Kit Latin OCR, fully on-device (`OnDeviceTextRecognizer`, heuristic confidence
  20–95). `capture` screenshots (Android 11+), OCR text matching for `ocrwait`/`visualtap`;
  captures stay in private storage with an inspect/OCR/delete UI.

### 1.6 Security and privacy

- `SecretStore`: AES-GCM with Android Keystore non-exportable key, name-bound AAD (ciphertexts
  cannot be swapped between names), in-place upgrade of legacy payloads, unreadable-secret
  detection (key invalidated) surfaced in the UI.
- `EncryptedStateStore`: namespaced AES-GCM for internal state (audit ledger, checkpoint,
  reminders-independent stores), AAD binds ciphertext to namespace.
- `AuditLedger`: SHA-256 hash chain in encrypted storage, MAX_ENTRIES front-trim, separate
  high-water mark detects tail truncation; clear() ordering documented fail-safe.
- Manifest posture (unit-test-locked + build-task enforced): no network permission (ML Kit's
  telemetry-contributed perms stripped in the merged manifest), backup disabled, cleartext
  off, accessibility service and receivers non-exported, FileProvider scoped to `exports/`,
  FLAG_SECURE on the UI. Permission set: `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`.

### 1.7 UX and reminders

- Material 3 (day/night), edge-to-edge insets, searchable pickers (≥12 rows), report sheets,
  badge rows with programmatic content descriptions, haptic confirmation on Run/Stop.
- Run reminders: one-shot inexact alarms + notifications that open Mobet with the workflow
  loaded — never start it (`RunReminder`); boot receiver re-arms future reminders and posts
  missed ones at reboot; records for deleted workflows are forgotten.
- Library export/import: authenticated-preference source, export never contains secret values
  (references only), streamed 2 MB cap while reading, ≤200 entries, ≤200 steps/100 variables
  per workflow, import never overwrites (suffixing), disk-write-before-claim ordering.

### 1.8 Testing and CI

- 210 JVM tests (risk, validator, parser bounds, fuzzy text, resolver abstention, fingerprints,
  planner, simulator, ledger chain, secret codec, trust engine, highlighter, masks, reminder
  reconciliation, SoT policy, agent benchmarks) + 21 on-device tests (Keystore boundary,
  encrypted ledger, Espresso control surface incl. rotation draft, reminder boot restore).
- Pipelines: Android CI (unit + security lint + debug APK + connected API 29 device job with
  per-test failure annotations), Security analysis (CodeQL Java/Kotlin), manual Release APK
  (unsigned unless keystore secrets are supplied), manual dependency-checksum pinning
  (sha256+sha512 `gradle/verification-metadata.xml`, fail-closed once committed).

## 2. Gaps

### 2.1 Deliberate, gated absences (do not "fix" without the named gate)

- **Tier-3 scheduled/unattended execution** — gated by [SCHEDULED_RUNS_REVIEW.md](SCHEDULED_RUNS_REVIEW.md)
  (invariant collision, binding non-negotiables, maintainer sign-off required).
- **Model-based planning/voice models** — see [STATE_OF_THOUGHT.md](STATE_OF_THOUGHT.md);
  a model advisor must stay inside the `ModelAssistant` contract and would need its own
  threat-model review.
- **Any network capability** — enforced absent in the merged manifest; adding one is a
  reviewed, threat-model-visible decision by construction.
- **Timed precision** — reminders are inexact alarms by design (no sensitive permission);
  exactness is not worth the capability.

### 2.2 Operational gaps (repo-side, none of them code)

- Dependency graph **not enabled** in repo settings — the dependency-review job runs
  `continue-on-error` until it is.
- `gradle/verification-metadata.xml` **does not exist yet** — generated by dispatching
  *Pin dependency checksums* once *after merge* (manual workflows must live on the default
  branch to be dispatchable); until then artifact checksums are unpinned.
- Branch protection on `main` **not configured** (required checks should be: Build & JVM unit
  tests, Connected device tests, CodeQL Java and Kotlin).
- Release APKs are unsigned when no keystore secrets are set — expected, but a future signing
  key change still breaks in-place updates (workflow library survives only via export bundles).
- This branch carries all of the above; until merged, none of it protects `main`.

### 2.3 Product gaps (candidates, with honest cost notes)

- **Internationalization** — many UI strings are inline in Kotlin (`MainActivity`, `StepBuilder`,
  `MobetUi` helpers); `strings.xml` covers the chrome, not the sheets/dialogs. Localization
  would be a wide diff touching most call sites with little behavioral change — real work,
  low risk, deferred so far because churn outweighs value at current user base.
- **The belief tracker is write-only on the decision path** — `TemporalBeliefTracker` computes
  belief states the live loop discards; documented (and now policy-ready) in
  [STATE_OF_THOUGHT.md](STATE_OF_THOUGHT.md). Wiring it to *corroborate* completion evidence
  is the natural next step if its value is proven; requiring it today would be telemetry
  theatre.
- **No share-target / open-with import** — the manifest exposes no `VIEW` intent for bundles;
  import is only through the in-app picker (§3.3 candidate).
- **No app shortcuts / quick-settings tile** (§3.3).
- **Recorder captures taps/scrolls only** — fills must be authored afterward. Deliberate
  (never record keystrokes), but it makes end-to-end form recordings a two-phase flow.
- **No per-step error semantics beyond retries** — no try/branch steps; `ifText`/`unlessText`
  is the only conditional. A conditional-branch action would be a PlanValidator-visible
  design change, not a fix.

## 3. Integrations

### 3.1 Platform integrations (live)

- **AccessibilityService** — the core capability: `canRetrieveWindowContent`, gestures,
  screenshots; service config caps event types/flags to what the feature set uses.
- **ML Kit Text Recognition (bundled)** — the only third-party runtime dependency with a
  native model; its transitive network permissions are stripped in the merged manifest and
  that stripping is build-enforced.
- **Android Keystore** — non-exportable AES keys for `SecretStore`/`EncryptedStateStore`.
- **AlarmManager + NotificationManager** — reminders and the live autonomy notification
  (with a stop action that opens MainActivity's stop path).
- **PackageManager (launcher activities query)** — least-privilege package visibility for the
  target/recording pickers.
- **Settings intents** — Accessibility settings and App-Info (restricted-settings unlock flow
  documented in the testing walkthrough).
- **FileProvider + share sheet / Storage Access Framework picker** — the only
  cross-app boundary: one scoped `exports/` directory, per-URI grants, staged-export cleanup.

### 3.2 Pipeline integrations

- GitHub Actions: `gradle/actions/setup-gradle`, `upload-artifact`, CodeQL, dependency-review
  (advisory until Dependency graph is on), reactivecircus emulator runner for the device job.
- Maven Central / Google Maven supply chain: Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21; checksum
  pinning workflow ready to generate `gradle/verification-metadata.xml` (fail-closed from the
  first dispatch onward).

### 3.3 Integration areas (opportunity register)

| Candidate | Value | Constraint it must respect |
| --- | --- | --- |
| **Share-target / `ACTION_VIEW` for bundles** (open a `.json` from a file manager → import preview) | Removes the "where is the picker" friction | Must reuse the existing bounded path — streamed `MAX_BYTES` read, `parseBundle` validation, preview-then-commit. Never import direct from intent without the preview sheet. |
| **Static app shortcut** ("Run reminders", or specific saved workflow) | One-tap re-entry | Launcher shortcuts are user-initiated, which satisfies the run invariant *only if* the shortcut lands on the workflow *loaded* screen — user still presses Run, policy re-validated at press time. Shortcuts must not pass shortcuts around confirmations. |
| **Quick Settings tile** (service enabled/disabled-state) | Glanceable posture + one-tap open | Read-only state + deep link; no toggle logic in the tile process. |
| **On-device model advisor** (AICore/Gemini-Nano-class) | Better grounding for ambiguous goals | The hard gate: `ModelAssistant` contract + `ModelOutputValidator` + `StateOfThoughtPolicy` weighting + a threat-model addendum (model reads *structural* state, never secrets/values; nothing off-device). Target of the SoT note. |
| **WorkManager / exact scheduling (Tier-3)** | True scheduled runs | Blocked by `SCHEDULED_RUNS_REVIEW.md` sign-off; degrade-to-reminder on any violation. |
| **Locale resources (i18n)** | Non-English users | Mechanical extraction of inline strings; no behavior change; bundle with a strings freeze. |
| **Tasker/Intent API** | Power-user composition | **Declined as designed**: an exported automation API breaks the "every action explicitly user-initiated + policy-validated" invariant (remote triggers are threated-model adversaries). Any reconsideration is a threat-model event, not a feature. |
| **Backup transport (Auto Backup/D2D)** | Migration UX | `allowBackup=false` protects the secret store and ledger; the export-bundle is the sanctioned path. Do not weaken without re-keying design. |

## 4. Where this document fits

- Product/safety invariants: [THREAT_MODEL.md](THREAT_MODEL.md)
- Tier-3 scheduling gate: [SCHEDULED_RUNS_REVIEW.md](SCHEDULED_RUNS_REVIEW.md)
- Reasoning-paradigm context: [STATE_OF_THOUGHT.md](STATE_OF_THOUGHT.md)
- Architecture notes: [ARCHITECTURE.md](ARCHITECTURE.md)
- Manual verification: [TESTING_WALKTHROUGH.md](TESTING_WALKTHROUGH.md)
- Social media / marketing suitability: [SOCIAL_MEDIA_AND_MARKETING.md](SOCIAL_MEDIA_AND_MARKETING.md)
