package com.cuscus.wifiaudiostreaming

import org.junit.Assert.*
import org.junit.Test

class ReleaseChannelTest {
    private fun release(tag: String, preview: Boolean, asset: String) =
        ReleaseChannel.Published(tag, "https://github.com/mu23XR/AudioBridge/releases/tag/$tag", preview, false, listOf(asset))

    @Test fun separateChannelsAndExcludeHistoricalTestPackages() {
        val stable = release("v1.3.1", false, "AudioBridge-Android-1.3.1.apk")
        val legacy = release("v1.3.1-test.16", true, "AudioBridge-Android-Test-1.3.1-test.16.apk")
        val preview = release("v1.3.1-test.17", true, "AudioBridge-Android-Preview-1.3.1-test.17.apk")
        val all = listOf(stable, legacy, preview)
        assertEquals(stable, ReleaseChannel.select(all, false))
        assertEquals(preview, ReleaseChannel.select(all, true))
        assertNull(ReleaseChannel.select(listOf(legacy, stable), true))
    }

    @Test fun draftsAndMissingAndroidAssetsNeverOfferUpdates() {
        val preview = release("v1.3.1-test.17", true, "AudioBridge-Android-Preview-1.3.1-test.17.apk")
        assertNull(ReleaseChannel.select(listOf(preview.copy(draft = true), preview.copy(assets = listOf("desktop.zip"))), true))
    }

    @Test fun stableOutranksItsPrereleasesAndNumericTestOrderingIsCorrect() {
        assertTrue(ReleaseChannel.compare("1.3.1", "1.3.1-test.16") > 0)
        assertTrue(ReleaseChannel.compare("v1.3.1-test.17", "1.3.1-test.9") > 0)
        assertTrue(ReleaseChannel.compare("1.4.0-test.1", "1.3.1") > 0)
    }
}
