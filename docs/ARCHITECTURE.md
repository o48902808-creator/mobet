# Architecture

## Trust boundary

The Accessibility Service is the privileged boundary. The activity parses locally authored JSON and passes a typed `Workflow` directly to the connected service. There is currently no internet permission, remote command channel, background scheduler, or arbitrary script execution.

## Components

- `MainActivity`: service setup, local JSON editor, Run/Stop controls, status display.
- `Workflow`: strict parser and typed step model with bounded timing values.
- `MobetAccessibilityService`: Android accessibility lifecycle, app launch, text setting, and local status broadcasts.
- `WorkflowRunner`: sequential state machine, breadth-first accessibility-tree selector, polling, retries, conditions, secret resolution, confirmations, cancellation, and fail-fast behavior.
- `InteractionRecorder`: privacy-preserving tap/scroll capture; it records selectors but never entered text.
- `ScreenInspector`: node-free snapshots with selector stability, uniqueness, role, and bounds diagnostics.
- `SecretStore`: AES-GCM values protected by a non-exportable Android Keystore key.
- `GoalPlanner`: offline natural-language-to-JSON compiler grounded only in inspected elements.
- `PlanValidator`: non-bypassable package/action allowlists, budgets, visual restrictions, and confirmation rules shared by all plan sources.

## Design rules for future work

- Prefer resource IDs and accessibility semantics over screen coordinates.
- Treat OCR and coordinate tapping as explicit low-confidence fallbacks.
- Never log or persist filled values; introduce Keystore-backed secret references instead.
- Require local confirmation for external communication, money movement, purchases, form submission, account changes, and deletion.
- Maintain package allowlists per workflow and show the target app before starting.
- Keep deterministic execution as the final authority even if an AI planner proposes steps.
