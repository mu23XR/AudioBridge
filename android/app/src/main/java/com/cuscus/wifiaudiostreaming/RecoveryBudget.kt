package com.cuscus.wifiaudiostreaming

/** Bound automatic retries; only success or a new explicit command resets it. */
internal class RecoveryBudget(private val maximumRetries: Int = 2) {
    private var retries = 0
    fun retryAllowed(): Boolean {
        if (retries >= maximumRetries) return false
        retries++; return true
    }
    fun reset() { retries = 0 }
}
