package com.cuscus.wifiaudiostreaming

import org.junit.Assert.*
import org.junit.Test

class VolumeScaleTest {
    @Test fun senderAndReceiverHaveDifferentGainCeilingsButSameDisplayRange() {
        assertEquals(50, VolumeScale.percent(1f, 2f))
        assertEquals(100, VolumeScale.percent(2f, 2f))
        assertEquals(100, VolumeScale.percent(1f, 1f))
        for (percent in 0..100) {
            assertEquals(percent, VolumeScale.percent(VolumeScale.gain(percent, 2f), 2f))
            assertEquals(percent, VolumeScale.percent(VolumeScale.gain(percent, 1f), 1f))
        }
    }
    @Test fun endpointsClampWithoutChangingUnityGain() {
        assertEquals(0f, VolumeScale.gain(-1, 2f), 0f)
        assertEquals(2f, VolumeScale.gain(101, 2f), 0f)
        assertEquals(1f, VolumeScale.gain(50, 2f), 0f)
        assertEquals(100, VolumeScale.percent(3f, 2f))
    }
    @Test(expected = IllegalArgumentException::class)
    fun invalidGainIsRejected() { VolumeScale.percent(Float.NaN, 2f) }
}
