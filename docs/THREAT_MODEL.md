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
- A proposed action is canonicalized against a fresh accessibility snapshot immediately before use.
- Live package provenance is checked before action execution and before success evidence.
- Risk is recomputed from the canonical selector and visible label; planner-provided risk is ignored.
- `PlanValidator`, `RiskEngine`, confirmation, `WorkflowRunner`, and post-action observation cannot be bypassed by a model.
- Elevated and critical actions retain adjacent and hardened typed confirmation requirements.
- Every autonomous run has package, action, cycle, runtime, candidate, depth, lookahead, and recovery limits.
- Session generations invalidate callbacks after Stop/replacement.
- Completion requires repeated observations rather than one transient frame.
- Persistent agent memory is minimized, bounded, AES-GCM authenticated, namespace-bound, and backed by Android Keystore.
- Major app versions and repeated route contradictions invalidate learned knowledge.
- CI tokens are read-only and checkout credentials are not persisted.

## Privacy posture

The manifest contains no internet permission. Agent memory excludes visible labels, OCR text, entered values, secrets, and screenshots; it stores package names, app versions, structural screen/action hashes, outcomes, confidence, timestamps, failure classes, and repair hashes. Consented screenshots stay in private app storage. Mobet's own activity uses `FLAG_SECURE` and Android backup is disabled.

## Non-goals and residual risks

- Rooted or fully compromised devices can subvert app and platform guarantees.
- Accessibility metadata can be incomplete or dishonest; uncertainty and confirmation reduce but cannot eliminate this risk.
- CAPTCHA, biometric, secure-window, and protected-field bypasses are intentionally unsupported.
- Production claims require signed reproducible releases and validation across physical devices/OEMs; deterministic JVM worlds and lint are necessary but not sufficient evidence.
