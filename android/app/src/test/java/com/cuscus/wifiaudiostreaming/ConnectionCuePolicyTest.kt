package com.cuscus.wifiaudiostreaming

import org.junit.Assert.*
import org.junit.Test

class ConnectionCuePolicyTest {
    @Test fun anUnsuccessfulAttemptDoesNotConsumeTheNextAttemptsCue() {
        val policy = ConnectionCuePolicy()
        policy.beginAttempt(true) // ACK/rejected data must not call onValidFrame.
        assertTrue(policy.beginAttempt(true).onValidFrame())
        assertFalse(policy.beginAttempt(true).onValidFrame())
    }

    @Test fun automaticRetriesAndAdditionalFramesCannotRepeatTheCue() {
        val policy = ConnectionCuePolicy()
        val first = policy.beginAttempt(true)
        assertTrue(first.onValidFrame())
        assertFalse(first.onValidFrame())
        assertFalse(policy.beginAttempt(true).onValidFrame())
    }

    @Test fun explicitReconnectRenewsEligibilityAndInvalidatesOldAttempts() {
        val policy = ConnectionCuePolicy()
        val old = policy.beginAttempt(true)
        assertTrue(old.onValidFrame())
        policy.reset()
        assertFalse(old.onValidFrame())
        assertTrue(policy.beginAttempt(true).onValidFrame())
        policy.reset()
        val replacedBeforeConnection = policy.beginAttempt(true)
        policy.reset()
        assertFalse(replacedBeforeConnection.onValidFrame())
        assertTrue(policy.beginAttempt(true).onValidFrame())
    }

    @Test fun disabledPreferenceIsRespectedWithoutAnAutomaticRetryChime() {
        val policy = ConnectionCuePolicy()
        assertFalse(policy.beginAttempt(false).onValidFrame())
        assertFalse(policy.beginAttempt(true).onValidFrame())
        policy.reset()
        assertTrue(policy.beginAttempt(true).onValidFrame())
    }
}
