# Apex threat model

## Protected assets

- User intent and confirmation authority
- Accessibility-derived UI state and selectors
- Workflow secrets, memory metadata, captures, and audit history
- Package, action, risk, runtime, and expansion boundaries

## Adversaries

1. A target app presenting deceptive controls or prompt-injection text.
2. Stale or compromised planner/model output proposing forged selectors or metadata.
3. UI drift causing learned routes to become unsafe or incorrect.
4. A different app attempting to spoof completion evidence or receive an action.
5. Delayed callbacks from a cancelled autonomous session.
6. Offline extraction or modification of private memory on a compromised backup/filesystem.
7. A compromised CI dependency attempting to mutate the repository.

## Enforced invariants

- Screen and model text are untrusted data, never policy instructions.
- The absence of network permissions is enforced, not assumed: `ManifestPostureTest` asserts the source manifest and the Gradle task `verify<Variant>NoNetworkPermission` asserts the **merged** manifest, so a transitive dependency cannot contribute `INTERNET` through manifest merging. `assemble*` depends on it and CI runs it explicitly.
- ML Kit's bundled OCR pulls in `com.google.android.datatransport`, which declares `INTERNET` and `ACCESS_NETWORK_STATE` for telemetry. These are stripped with `tools:node="remove"`; on-device OCR requires neither. This was found by the merged-manifest check on its first run, having previously shipped unnoticed.
- Injection detection is a *signal, not a boundary*. `ContentTrustEngine` is a heuristic that will eventually be evaded; containment does not depend on it. `InjectionDefenceInDepthTest` proves the risk ceiling, irreversibility rule, cycle budget, and model-ranking allowlist all hold with the detector deliberately bypassed.
- A proposed action is canonicalized against a fresh accessibility snapshot immediately before use.
- Live package provenance is checked before action execution and before success evidence.
- Risk is recomputed from the canonical selector and visible label; planner-provided risk is ignored.
- `PlanValidator`, `RiskEngine`, confirmation, `WorkflowRunner`, and post-action observation cannot be bypassed by a model.
- Elevated and critical actions retain adjacent and hardened typed confirmation requirements.
- Every autonomous run has package, action, cycle, runtime, candidate, depth, lookahead, recovery, battery, thermal, and UI-settling limits.
- Session generations invalidate callbacks after Stop/replacement; encrypted crash checkpoints never trigger auto-resume or irreversible replay.
- Completion requires repeated settled observations rather than one transient frame. Consent-controlled OCR is evidence-only, confidence-gated, never persisted, and cannot create device actions.
- Optional model assistance can rank only pre-approved canonical action IDs, has capped utility influence, and cannot define policy or completion authority.
- Persistent agent memory is minimized, bounded, AES-GCM authenticated, namespace-bound, and backed by Android Keystore.
- Major app versions and repeated route contradictions invalidate learned knowledge.
- CI tokens are read-only and checkout credentials are not persisted.
- `launch` is the only action that can move automation into another app; its destination must appear in `policy.allowedPackages`, is validated statically by `PlanValidator`, and is re-checked against the same allowlist immediately before the switch.
- Scheduling posts a reminder notification only. Workflows are never started unattended, preserving the requirement that a user is present to answer confirmations and press Stop.
- Exported bundles contain workflow JSON only. Secret values are never read or written by the export path; a `{{secret:name}}` reference is exported without its value, and the `FileProvider` is scoped to a dedicated exports directory so captures, memory, and the ledger are unreachable.

## Privacy posture

The merged manifest contains no internet permission — verified at build time rather than asserted, including the removal of the permissions ML Kit's telemetry dependency contributes. Agent memory excludes visible labels, OCR text, entered values, secrets, and screenshots; it stores package names, app versions, structural screen/action hashes, outcomes, confidence, timestamps, failure classes, and repair hashes. Consented screenshots stay in private app storage. Mobet's own activity uses `FLAG_SECURE` and Android backup is disabled.

## If network access is ever added

Mobet currently declares no network permission, and that is enforced by the checks listed above.
This section records what a reviewed change would have to satisfy, so that the decision is made
against a written standard rather than improvised.

**Why the constraint is load-bearing.** Mobet holds an accessibility service that can read every
label on every screen the user visits. *Screen reading + network egress = exfiltration channel*,
regardless of intent. Withholding `INTERNET` removes that capability at the kernel level, and it
is the one security property a user can verify without trusting the implementation.

**A per-process split is not a security boundary.** `INTERNET` is granted per-UID, not
per-process. Once the app holds it, any process in it — including the accessibility service — can
open a socket. `android:process=":net"` provides crash and memory isolation only. The sole
OS-enforced split is two APKs with distinct UIDs (`mobet` with accessibility and no `INTERNET`,
a companion with `INTERNET` and no accessibility) communicating over a bound AIDL interface.

**Requirements for any networked feature:**

1. Prefer designs that need no network at all. On-device models (e.g. bundled weights loaded from
   an APK asset) deliver the capability without spending the invariant; download-on-first-run does
   not.
2. Egress must be declaratively allowlisted via `network-security-config` with
   `cleartextTrafficPermitted="false"` and certificate pinning to a single domain, so a
   compromised dependency cannot reach an arbitrary host.
3. Secrets must be structurally unable to cross the boundary. The network module must not depend
   on `SecretStore`.
4. Screen text, OCR output, and entered values must never be transmitted. Agent memory already
   stores structural hashes rather than labels; that line holds at the wire.
5. Anything received is untrusted input and re-enters through `PlanValidator` and `RiskEngine`
   exactly like an imported file. **Nothing arriving over the network may widen the action set.**
6. A remote planner may return canonical action *IDs* only, resolved locally against a live
   snapshot, and must abstain rather than guess below a confidence floor.

## Non-goals and residual risks

- Rooted or fully compromised devices can subvert app and platform guarantees.
- Accessibility metadata can be incomplete or dishonest; uncertainty and confirmation reduce but cannot eliminate this risk.
- CAPTCHA, biometric, secure-window, and protected-field bypasses are intentionally unsupported.
- Production claims require signed reproducible releases and validation across physical devices/OEMs; deterministic JVM worlds and lint are necessary but not sufficient evidence.
