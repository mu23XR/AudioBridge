package com.cuscus.wifiaudiostreaming

import org.junit.Assert.*
import org.junit.Test

class PlaybackHealthTest {
    @Test fun shortagesRaiseTargetButFixedModeKeepsUserValue() {
        for (adaptive in listOf(true, false)) {
            val health = PlaybackHealth(adaptive)
            health.sample(1000, 1, true, 0, 0)
            assertEquals(if (adaptive) 10 else 0,
                health.sample(2000, 2, true, 0, 0).targetDeltaMs)
        }
    }

    @Test fun idleAndIntermittentUnderrunsDoNotCountAsCleanAudio() {
        val health = PlaybackHealth(true)
        health.sample(0, 0, true, 0, 0)
        health.sample(29_000, 1, true, 0, 0)
        assertEquals(0, health.sample(30_000, 1, true, 0, 0).targetDeltaMs)
        assertEquals(0, health.sample(60_000, 1, false, 0, 0).targetDeltaMs)
        for (t in 61_000L..90_000L step 1000) health.sample(t, 1, true, 0, 0)
        assertEquals(-5, health.sample(91_000, 1, true, 0, 0).targetDeltaMs)
    }

    @Test fun adaptiveStillEscalatesRepeatedLocalFailuresAndStorms() {
        val health = PlaybackHealth(true)
        assertTrue(health.sample(1000, 0, true, 100, 3).rebuild)
        assertFalse(health.sample(2000, 0, false, 6000, 3).rebuild)
        health.sample(3000, 300, true, 0, 0)
        health.sample(4000, 600, true, 0, 0)
        assertTrue(health.sample(5000, 900, true, 0, 0).rebuild)
    }

    @Test fun missingSamplesDoNotProveContinuousHealthyPlayback() {
        val health = PlaybackHealth(true)
        health.sample(0, 0, true, 0, 0)
        assertEquals(0, health.sample(31_000, 0, true, 0, 0).targetDeltaMs)
    }

    @Test fun migrationRetainsManualUsersButNewInstallsCanDefaultAdaptive() {
        assertTrue(com.cuscus.wifiaudiostreaming.data.resolveAdaptiveLatency(null, false))
        assertFalse(com.cuscus.wifiaudiostreaming.data.resolveAdaptiveLatency(null, true))
        assertTrue(com.cuscus.wifiaudiostreaming.data.resolveAdaptiveLatency(true, true))
        assertFalse(com.cuscus.wifiaudiostreaming.data.resolveAdaptiveLatency(false, false))
    }
}
