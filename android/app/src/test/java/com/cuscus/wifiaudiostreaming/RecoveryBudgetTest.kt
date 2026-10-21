package com.cuscus.wifiaudiostreaming

import org.junit.Assert.*
import org.junit.Test

class RecoveryBudgetTest {
    @Test fun repeatedStartupFailuresStopUntilSuccessOrExplicitRestart() {
        val budget = RecoveryBudget(2)
        assertTrue(budget.retryAllowed()); assertTrue(budget.retryAllowed())
        assertFalse(budget.retryAllowed()); assertFalse(budget.retryAllowed())
        budget.reset()
        assertTrue(budget.retryAllowed())
    }
}
