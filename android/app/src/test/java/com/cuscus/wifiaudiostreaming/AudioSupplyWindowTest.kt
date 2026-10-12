package com.cuscus.wifiaudiostreaming

import org.junit.Assert.*
import org.junit.Test

class AudioSupplyWindowTest {
    @Test fun prolongedKeepalivesDoNotTriggerAdaptiveUnderrunRecovery() {
        val activity = AudioSupplyWindow()
        val health = PlaybackHealth(true)
        val headerOnly = ByteArray(10)
        for (second in 0..180) {
            val now = second * 1000L
            activity.observe(now, headerOnly, 10, 0, silence = true)
            val decision = health.sample(now, second * 300, activity.flowing(now), 0, 0)
            assertEquals(0, decision.targetDeltaMs)
            assertFalse(decision.rebuild)
        }
        activity.observe(181000, byteArrayOf(1, 0, 1, 0), 0, 4)
        assertTrue(activity.flowing(181000))
    }

    @Test fun digitalSilenceDoesNotRefreshThePreviousRealAudioWindow() {
        val activity = AudioSupplyWindow()
        activity.observe(0, byteArrayOf(1, 0), 0, 2)
        activity.observe(4000, ByteArray(400), 0, 400)
        assertFalse(activity.flowing(4000))
        activity.observe(5000, byteArrayOf(3, 0), 0, 2)
        assertTrue(activity.flowing(5000))
        assertFalse(activity.flowing(8000))
    }
}
