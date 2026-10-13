package com.cuscus.wifiaudiostreaming

import org.junit.Assert.*
import org.junit.Test

class PacketMetricsTest {
    @Test fun receivedSilenceIsNotLostAndDoesNotInflateJitter() {
        var now = 0L
        val m = PacketMetrics(48000) { now }
        m.observe(10, 0); now += 10_000_000; m.observe(11, 480)
        now += 1_000_000_000; m.observe(12, 960, true)
        now += 1_000_000_000; m.observe(13, 960, true)
        now += 10_000_000; m.observe(14, 960)
        assertEquals(0L, m.snapshot().lost)
        assertEquals(0.0, m.snapshot().jitterMs, 0.0001)
    }
    @Test fun wrapLateArrivalAndDuplicatesHaveIndependentCounts() {
        val m = PacketMetrics(48000) { 0L }
        m.observe(65534, 0); m.observe(0, 1)
        assertEquals(1L, m.snapshot().lost)
        assertFalse(m.observe(65535, 0))
        assertEquals(0L, m.snapshot().lost)
        assertFalse(m.observe(0, 1))
        assertEquals(1L, m.snapshot().duplicates)
        assertTrue(m.observe(1, 2))
    }
    @Test fun genuineMissingKeepaliveIsStillTransportLoss() {
        val m = PacketMetrics(48000) { 0L }
        m.observe(1, 0); m.observe(3, 0, true)
        assertEquals(1L, m.snapshot().lost)
    }
}
