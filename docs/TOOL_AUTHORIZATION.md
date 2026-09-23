# Tool metadata and authorization

Natural-language, voice, and model output never invokes Android operations directly. It may only
request a named `SafeTool` registered with complete metadata:

- stable name and human-readable description;
- typed input schema and bounded output schema;
- `ToolRisk` classification;
- required package scope;
- mandatory-confirmation flag;
- autonomous-execution allowance.

`ToolRegistry.dispatch` repeats authorization immediately before invocation even when a whole plan
was validated earlier. This prevents direct-dispatch and time-of-check/time-of-use bypasses.
Unknown tools, duplicate call IDs, malformed schemas, package transitions, missing confirmations,
oversized plans, forbidden autonomous calls, and `NEVER_AUTOMATIC` tools fail closed.

## Risk policy

| Risk | Examples | Default authority |
|---|---|---|
| `READ_ONLY` | `describe_screen`, `list_actions`, `inspect_current_package`, future `read_visible_text` and plan summaries | May run autonomously inside the bound package |
| `REVERSIBLE` | Future `scroll`, `navigate_back`, `open_app`, `focus_field` | May run autonomously only when the individual tool explicitly allows it |
| `CONFIRM_REQUIRED` | Future `tap`, `type_text`, `submit_form`, `send_message`, `delete_item`, `change_setting` | Requires confirmation for the exact call ID; autonomous use is off by default |
| `NEVER_AUTOMATIC` | Permission grants, package installation, secret export, accessibility/security-policy changes, shell commands | Cannot be model-authorized even when a confirmation ID is supplied |

Only read-only observation tools are currently registered for execution. Mutating tools remain
behind the existing typed workflow, `RiskEngine`, `PlanValidator`, confirmation, and evidence
pipeline until the intent-to-plan phase can integrate them without creating parallel authority.

## Audit contract

Constructing a `ToolRegistry` requires an audit sink. `MobetAccessibilityService` binds that sink
to `AuditLedger.append`; a failed ledger write makes the tool result fail closed. Every allowed or
denied dispatch records:

- tool name;
- schema-marked and name-detected sensitive arguments redacted;
- risk classification;
- deterministic policy decision;
- confirmation result;
- result digest and success state.

Raw tool output is never written to the ledger. Argument names are normalized, values are bounded,
and line breaks are removed before serialization. Current tools are read-only, so a post-result
audit-write failure cannot leave an unaudited device mutation.
