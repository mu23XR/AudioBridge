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
        var rateResult = 0
        override var restartPrerollFrames = 0
        var rejectPreroll = false
        override fun write(pcm: ByteArray, offset: Int, length: Int, mode: Int): Int {
            writeMode = mode
            if (restartPrerollFrames > 0 && length == restartPrerollFrames * 4) {
                return if (rejectPreroll) 0 else length
            }
            return accepted
        }
        override fun pause() {}
        override fun flush() { flushes++; playbackHeadPosition = 0 }
        override fun play() { plays++ }
        override fun setPlaybackRate(rate: Int) = rateResult
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

    @Test fun rejectedRateChangeDoesNotPretendToHaveChangedTheHardware() {
        val track = FakeTrack().apply { rateResult = -3 }
        val governor = PlayoutGovernor(track, 48000, 4, 20, "test", { 0L }, {})
        governor.retune()
        assertEquals(48000, governor.playbackRateHz())
    }

    @Test fun playbackHeadWrapAfterLongSessionsDoesNotInventGigabytesOfBacklog() {
        val track = FakeTrack()
        val governor = PlayoutGovernor(track, 48000, 4, 20, "test", { 0L }, {})
        governor.bufferedFrames() // Establish origin.
        repeat(9) { governor.noteWritten(Int.MAX_VALUE / 4 * 4) }
        track.playbackHeadPosition = -1
        val before = governor.bufferedFrames()
        track.playbackHeadPosition = 0 // Unsigned 32-bit wrap, not a new track.
        assertEquals(before - 1, governor.bufferedFrames())
    }

    @Test fun deliberateIdleRestoresNominalRateWithoutFlushingAudio() {
        val track = FakeTrack()
        val governor = PlayoutGovernor(track, 48000, 4, 20, "test", { 0L }, {})
        governor.retune()
        assertTrue(governor.playbackRateHz() < 48000)
        governor.retune(activeInput = false)
        assertEquals(48000, governor.playbackRateHz())
        assertEquals(0, track.flushes)
    }

    @Test fun fullAdmissionCannotHideAStalledOutputFromLocalRecovery() {
        var now = 0L
        val track = FakeTrack().apply { accepted = 6_720 }
        val governor = PlayoutGovernor(track, 48_000, 4, 20, "test", { now }, {})
        governor.writePcm(ByteArray(6_720), 0, 6_720)
        val queue = PcmQueue(4, 11_520).apply { offer(ByteArray(256)) }
        val pump = PcmDrainPump(queue, 48_000, 4) { 0L }
        assertTrue(pump.drain(20, governor::bufferedFrames) { _, _, _ -> fail("Must not write over ceiling"); 0 }.blocked)
        now = 499
        assertFalse(governor.recoverStalledOutput())
        now = 500
        assertTrue(governor.recoverStalledOutput())
        track.accepted = 256
        assertEquals(256, pump.drain(20, governor::bufferedFrames) { pcm, offset, length ->
            governor.writePcm(pcm, offset, length)
        }.bytes)
        assertEquals(1, track.flushes)
        assertEquals(0, queue.bytes())
    }

    @Test fun intentionalSilentFeedDoesNotFlushASuspendedSinkRepeatedly() {
        var now = 0L
        val track = FakeTrack().apply { accepted = 100 }
        val governor = PlayoutGovernor(track, 48_000, 4, 20, "test", { now }, {})
        assertFalse(governor.recoverStalledOutput()) //Establish the initial head like normal output startup.
        repeat(10) {
            now += 1_000
            governor.writePcm(ByteArray(100), 0, 100, recoverStall = false)
        }
        assertEquals(0, track.flushes)
        assertTrue(governor.recoverStalledOutput()) //Real PCM may recover immediately.
    }

    @Test fun recoveryReprimesTheDeviceThresholdAndCountsItBeforeNewPcm() {
        var now = 0L
        val track = FakeTrack().apply { accepted = 100; restartPrerollFrames = 7_680 }
        val governor = PlayoutGovernor(track, 48_000, 4, 20, "test", { now }, {})
        governor.writePcm(ByteArray(100), 0, 100)
        now = 500
        assertTrue(governor.recoverStalledOutput())
        assertEquals(7_680L, governor.bufferedFrames())
        assertEquals(1, track.plays)
        // The one-time startup capacity drains before normal bounded admission.
        track.playbackHeadPosition = 7_680
        assertEquals(0L, governor.bufferedFrames())
        assertEquals(100, governor.writePcm(ByteArray(100), 0, 100))
        assertEquals(25L, governor.bufferedFrames())
    }

    @Test(expected = IllegalStateException::class)
    fun incompleteRestartPrimingIsReportedInsteadOfPretendingToResume() {
        var now = 0L
        val track = FakeTrack().apply { accepted = 100; restartPrerollFrames = 480; rejectPreroll = true }
        val governor = PlayoutGovernor(track, 48_000, 4, 20, "test", { now }, {})
        governor.writePcm(ByteArray(100), 0, 100)
        now = 500
        governor.recoverStalledOutput()
    }
}
