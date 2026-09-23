# Reversible action snapshots and recovery planning

Every autonomous action now produces a bounded `ActionSnapshot` containing the structural screen
fingerprint before and after execution, reversibility, destructive classification, proposed
reversal, reversal result when attempted, retry count, and accumulated recovery cost. Snapshot
summaries enter the hash-chained ledger without screen text or values.

`RecoveryPlanner` selects one explicit strategy:

1. `WAIT_FOR_SETTLE`
2. `REPAIR_SELECTOR`
3. `DISMISS_MODAL`
4. `BACKTRACK`
5. `REPLAN`
6. `ASK_USER`
7. `ABSTAIN`

Selection is deterministic from failure kind, reversibility, destructive risk, retries, selector
repair availability, modal evidence, and replanning availability. Costs increase toward user
interruption and abstention and are shown in the execution timeline and ledger.

A failed destructive or irreversible action is never retried automatically. Recovery escalates to
the user instead. Backtracking is implemented only through the typed Back action and receives its
own before/after snapshot; `reversalSucceeded` remains null until observed evidence establishes the
result. Missing evidence can never be reported as a successful reversal.
