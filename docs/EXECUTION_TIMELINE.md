# Visual execution timeline

Mobet exposes a structured live explanation in the **Activity** card while a workflow or
autonomous goal is running. It is driven by typed `ExecutionTimelineEvent` messages rather than
parsing human log strings.

The current event displays:

- run mode and lifecycle state;
- current goal and subgoal;
- step or cycle position and budget;
- structural screen fingerprint;
- selected action;
- decision confidence when available;
- evidence supporting the decision or required after the action;
- deterministic risk classification and policy verdict;
- recovery strategy and attempt;
- the explicit reason a run halted.

Workflow events are emitted after live screen observation and deterministic risk assessment.
Autonomous events are emitted only after candidate selection has passed `AgentPlanValidator`.
Recovery and confirmation waits have distinct states, and successful and halted terminal events
remain visible after the run ends.

## Privacy boundary

Timeline broadcasts are package-scoped and accepted only by Mobet's non-exported receiver. The UI
is protected by `FLAG_SECURE`. Structured workflow events never include fill values or resolved
secret contents; evidence summaries contain only declared postconditions. Autonomous action IDs
are structural hashes rather than visible control labels. Timeline events are bounded to 8 KiB and
individual text fields are capped before serialization and after parsing.

The visual timeline is observability, not authority. It cannot approve an action, alter policy,
satisfy evidence, or bypass a confirmation gate. The existing audit ledger remains the persistent
tamper-evident record; the timeline is a live, human-readable projection of the same execution
state.
