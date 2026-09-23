package ai.arena.mobet.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecoveryPlanningTest {
    @Test fun recoveryPreferenceIsDeterministic() {
        assertEquals(
            RecoveryStrategy.WAIT_FOR_SETTLE,
            RecoveryPlanner.select(RecoveryContext(FailureKind.LOADING_DELAY, true, false, 0))
        )
        assertEquals(
            RecoveryStrategy.REPAIR_SELECTOR,
            RecoveryPlanner.select(RecoveryContext(
                FailureKind.STALE_SELECTOR, true, false, 0, selectorRepairAvailable = true
            ))
        )
        assertEquals(
            RecoveryStrategy.DISMISS_MODAL,
            RecoveryPlanner.select(RecoveryContext(
                FailureKind.MODAL_INTERRUPTION, true, false, 0, modalPresent = true
            ))
        )
        assertEquals(
            RecoveryStrategy.BACKTRACK,
            RecoveryPlanner.select(RecoveryContext(FailureKind.DEAD_END, true, false, 0))
        )
        assertEquals(
            RecoveryStrategy.REPLAN,
            RecoveryPlanner.select(RecoveryContext(FailureKind.DEAD_END, false, false, 0))
        )
    }

    @Test fun destructiveFailureIsNeverBlindlyRetried() {
        assertEquals(
            RecoveryStrategy.ASK_USER,
            RecoveryPlanner.select(RecoveryContext(FailureKind.LOADING_DELAY, false, true, 0))
        )
    }

    @Test fun deviceRejectionAbstains() {
        assertEquals(
            RecoveryStrategy.ABSTAIN,
            RecoveryPlanner.select(RecoveryContext(FailureKind.DEVICE_REJECTED, false, false, 0))
        )
    }

    @Test fun actionSnapshotRepresentsUnattemptedReversalHonestly() {
        val snapshot = ActionSnapshot(
            cycle = 1,
            actionId = "tap:abc",
            screenBefore = "before",
            screenAfter = "after",
            reversible = true,
            destructive = false,
            reversalAction = "back"
        )
        assertNull(snapshot.reversalSucceeded)
        assertEquals(0, snapshot.recoveryCost)
    }

    @Test fun strategyCostsIncreaseTowardAbstention() {
        assertEquals(true,
            RecoveryPlanner.cost(RecoveryStrategy.WAIT_FOR_SETTLE) <
                RecoveryPlanner.cost(RecoveryStrategy.BACKTRACK))
        assertEquals(true,
            RecoveryPlanner.cost(RecoveryStrategy.REPLAN) <
                RecoveryPlanner.cost(RecoveryStrategy.ABSTAIN))
    }
}
