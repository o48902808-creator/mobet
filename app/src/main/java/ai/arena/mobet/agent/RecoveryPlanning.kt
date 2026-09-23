package ai.arena.mobet.agent

enum class RecoveryStrategy {
    WAIT_FOR_SETTLE,
    REPAIR_SELECTOR,
    DISMISS_MODAL,
    BACKTRACK,
    REPLAN,
    ASK_USER,
    ABSTAIN
}

data class ActionSnapshot(
    val cycle: Int,
    val actionId: String,
    val screenBefore: String,
    val screenAfter: String?,
    val reversible: Boolean,
    val destructive: Boolean,
    val reversalAction: String?,
    val reversalSucceeded: Boolean? = null,
    val recoveryCost: Int = 0,
    val retries: Int = 0
)

data class RecoveryContext(
    val failure: FailureKind,
    val reversible: Boolean,
    val destructive: Boolean,
    val retries: Int,
    val selectorRepairAvailable: Boolean = false,
    val modalPresent: Boolean = false,
    val canReplan: Boolean = true
)

/** Deterministic recovery preference: wait → repair → reversible backtrack → replan → ask → abstain. */
object RecoveryPlanner {
    fun select(context: RecoveryContext): RecoveryStrategy {
        if (context.destructive) return RecoveryStrategy.ASK_USER
        return when {
            context.failure == FailureKind.LOADING_DELAY && context.retries < 2 ->
                RecoveryStrategy.WAIT_FOR_SETTLE
            context.failure == FailureKind.STALE_SELECTOR && context.selectorRepairAvailable ->
                RecoveryStrategy.REPAIR_SELECTOR
            context.failure == FailureKind.MODAL_INTERRUPTION && context.modalPresent ->
                RecoveryStrategy.DISMISS_MODAL
            context.failure == FailureKind.PERMISSION_GATE -> RecoveryStrategy.ASK_USER
            context.failure == FailureKind.DEVICE_REJECTED -> RecoveryStrategy.ABSTAIN
            context.reversible -> RecoveryStrategy.BACKTRACK
            context.canReplan -> RecoveryStrategy.REPLAN
            else -> RecoveryStrategy.ABSTAIN
        }
    }

    fun cost(strategy: RecoveryStrategy): Int = when (strategy) {
        RecoveryStrategy.WAIT_FOR_SETTLE -> 1
        RecoveryStrategy.REPAIR_SELECTOR -> 2
        RecoveryStrategy.DISMISS_MODAL -> 3
        RecoveryStrategy.BACKTRACK -> 4
        RecoveryStrategy.REPLAN -> 5
        RecoveryStrategy.ASK_USER -> 8
        RecoveryStrategy.ABSTAIN -> 10
    }
}
