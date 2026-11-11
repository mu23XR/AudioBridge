package com.cuscus.wifiaudiostreaming

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderPlaybackPolicyTest {
    @Test fun upgradeRetainsOldSdkDefaultUntilUserChooses() {
        assertFalse(SenderPlaybackPolicy.keepPlayingOnDevice(null, 32))
        assertTrue(SenderPlaybackPolicy.keepPlayingOnDevice(null, 33))
        assertFalse(SenderPlaybackPolicy.keepPlayingOnDevice(false, 36))
        assertTrue(SenderPlaybackPolicy.keepPlayingOnDevice(true, 33))
    }

    @Test fun localPlaybackLeavesHardwareVolumeToSystem() {
        assertFalse(SenderPlaybackPolicy.interceptHardwareVolume(false, false, false))
        assertFalse(SenderPlaybackPolicy.interceptHardwareVolume(true, true, true))
        assertTrue(SenderPlaybackPolicy.interceptHardwareVolume(true, true, false))
        assertTrue(SenderPlaybackPolicy.interceptHardwareVolume(true, false, true))
    }
}
