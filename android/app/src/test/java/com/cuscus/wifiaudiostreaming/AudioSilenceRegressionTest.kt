package com.cuscus.wifiaudiostreaming

import org.junit.Assert.*
import org.junit.Test

class AudioSilenceRegressionTest {
    @Test fun silenceMustBeExactAndRespectPayloadBounds() {
        val packet = byteArrayOf(0x57, 0x46, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        assertTrue(PcmSilence.isZero(packet, 10, 2))
        packet[11] = 1 // A single least-significant PCM bit is audible data.
        assertFalse(PcmSilence.isZero(packet, 10, 2))
        assertTrue(PcmSilence.isZero(packet, 10, 1))
        assertTrue(PcmSilence.isZero(packet, 12, 0))
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun malformedPcmRangeIsRejected() { PcmSilence.isZero(ByteArray(4), 3, 2) }

    private class FakeTrack : PlayoutGovernor.Output {
        override var playbackHeadPosition = 0
        var accepted = 0
        var flushes = 0
        var plays = 0
        var writeMode = -1
        override fun write(pcm: ByteArray, offset: Int, length: Int, mode: Int): Int {
            writeMode = mode
            return accepted
        }
        override fun pause() {}
        override fun flush() { flushes++; playbackHeadPosition = 0 }
        override fun play() { plays++ }
        override fun setPlaybackRate(rate: Int) = 0
    }

    @Test fun partialWriteCountsOnlyAcceptedFramesAndDoesNotRetry() {
        val track = FakeTrack().apply { accepted = 16 }
        val governor = PlayoutGovernor(track, 48000, 4, 20, "test", { 0L }, {})
        assertEquals(16, governor.writePcm(ByteArray(100), 0, 100))
        assertEquals(4L, governor.bufferedFrames())
        assertEquals(1, track.writeMode) // AudioTrack.WRITE_NON_BLOCKING
    }

    @Test fun stalledTrackRecoversAndResetsAccountingBeforeNewAudio() {
        var now = 0L
        val track = FakeTrack().apply { accepted = 100 }
        val governor = PlayoutGovernor(track, 48000, 4, 20, "test", { now }, {})
        governor.writePcm(ByteArray(100), 0, 100)
        track.accepted = 0
        now = 499
        governor.writePcm(ByteArray(100), 0, 100)
        assertEquals(0, track.flushes)
        now = 500
        track.accepted = 20
        governor.writePcm(ByteArray(100), 0, 100)
        assertEquals(1, track.flushes)
        assertEquals(1, track.plays)
        assertEquals(5L, governor.bufferedFrames())
    }

    @Test(expected = IllegalStateException::class)
    fun outputFailureIsReported() {
        val track = FakeTrack().apply { accepted = -6 }
        PlayoutGovernor(track, 48000, 4, 20, "test", { 0L }, {})
            .writePcm(ByteArray(4), 0, 4)
    }

    @Test fun repeatedFailedFlushesRemainVisibleUntilPlaybackAdvances() {
        var now = 0L
        val track = FakeTrack().apply { accepted = 100 }
        val governor = PlayoutGovernor(track, 48000, 4, 20, "test", { now }, {})
        governor.writePcm(ByteArray(100), 0, 100)
        for (i in 1..3) {
            now += 500
            governor.writePcm(ByteArray(100), 0, 100)
            // Establish the post-flush head without falsely treating a reset
            // to zero as proof the hardware actually played anything.
            governor.writePcm(ByteArray(100), 0, 100)
        }
        assertEquals(3, governor.recoveryFailures())
        track.playbackHeadPosition = 1
        governor.writePcm(ByteArray(100), 0, 100)
        assertEquals(0, governor.recoveryFailures())
    }
}
