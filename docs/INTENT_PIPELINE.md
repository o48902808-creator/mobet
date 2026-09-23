# Intent-to-plan pipeline

Typed goals and reviewed voice transcripts enter the same deterministic pipeline:

1. normalize and bound user text;
2. create a `StructuredGoal` with an exact package and success fact;
3. create an `AgentPlanPreview` with fixed action, risk, confidence, cycle, and runtime limits;
4. show **Explain** mode before any device action;
5. optionally show **Dry run**, which cannot touch the device;
6. require a separate **Execute** tap;
7. re-plan from fresh accessibility observations while deterministic policy remains authoritative;
8. verify progress and success evidence or halt with a named reason.

The preview displays package scope, allowed action kinds, maximum cycles, runtime, confidence floor,
confirmation behavior, required success evidence, and possible halt conditions. Voice-created goals
are labelled as reviewed transcripts, but receive no additional authority. Model assistance remains
an optional untrusted proposer and cannot change budgets or skip the preview.

Autonomous routes are dynamic because future screens cannot safely be predicted from a stale tree.
The preview therefore describes the bounded candidate plan and halt policy rather than inventing a
fixed selector sequence. The visual execution timeline exposes each concrete selected action,
screen fingerprint, confidence, evidence, and recovery decision as fresh observations arrive.
