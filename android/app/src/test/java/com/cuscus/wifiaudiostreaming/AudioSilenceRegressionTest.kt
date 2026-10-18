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
        var onPause: () -> Unit = {}
        override fun write(pcm: ByteArray, offset: Int, length: Int, mode: Int): Int {
            writeMode = mode
            if (restartPrerollFrames > 0 && length == restartPrerollFrames * 4) {
                return if (rejectPreroll) 0 else length
            }
            return accepted
        }
        override fun pause() = onPause()
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
        val playedBefore = governor.totalPlayedFrames()
        track.playbackHeadPosition = 0 // Unsigned 32-bit wrap, not a new track.
        assertEquals(before - 1, governor.bufferedFrames())
        assertEquals(playedBefore + 1, governor.totalPlayedFrames())
        assertEquals(0L, governor.resetCount())
    }

    @Test fun cumulativePlaybackSurvivesRepeatedFlushesWithoutCountingDiscardedPcm() {
        var now = 0L
        val track = FakeTrack().apply { accepted = 800 }
        val governor = PlayoutGovernor(track, 48_000, 4, 20, "test", { now }, {})
        governor.writePcm(ByteArray(800), 0, 800) //200 frames submitted.
        track.playbackHeadPosition = 100
        assertEquals(100L, governor.totalPlayedFrames())
        assertFalse(governor.recoverStalledOutput())
        // The device may advance between the stall decision and pause. The
        // final pre-flush sample must retain that progress and only discard75.
        track.onPause = { track.playbackHeadPosition = 125 }
        now = 500
        assertTrue(governor.recoverStalledOutput())
        assertEquals(125L, governor.totalPlayedFrames())
        assertEquals(75L, governor.nativeDiscardedFrames())
        assertEquals(1L, governor.localRecoveryCount())
        assertEquals(1L, governor.resetCount())
        assertEquals(0L, governor.bufferedFrames())

        track.onPause = {}
        track.accepted = 400
        governor.writePcm(ByteArray(400), 0, 400)
        track.playbackHeadPosition = 50
        assertEquals(175L, governor.totalPlayedFrames())
        assertEquals(50L, governor.bufferedFrames())
        assertFalse(governor.recoverStalledOutput())
        now = 1_000
        assertTrue(governor.recoverStalledOutput())
        assertEquals(175L, governor.totalPlayedFrames())
        assertEquals(125L, governor.nativeDiscardedFrames())
        assertEquals(2L, governor.localRecoveryCount())
        assertEquals(2L, governor.resetCount())

        track.accepted = 200
        governor.writePcm(ByteArray(200), 0, 200)
        track.playbackHeadPosition = 25
        assertEquals(200L, governor.totalPlayedFrames())
        assertEquals(25L, governor.bufferedFrames())
    }

    @Test fun repeatedRecoveryWithoutHeadProgressCannotInventPlayback() {
        var now = 0L
        val track = FakeTrack().apply { accepted = 100 }
        val governor = PlayoutGovernor(track, 48_000, 4, 20, "test", { now }, {})
        governor.writePcm(ByteArray(100), 0, 100)
        repeat(3) {
            now += 500
            assertTrue(governor.recoverStalledOutput())
            assertEquals(0L, governor.totalPlayedFrames())
            assertEquals(0L, governor.bufferedFrames())
            governor.writePcm(ByteArray(100), 0, 100)
        }
        assertEquals(75L, governor.nativeDiscardedFrames())
        assertEquals(3L, governor.localRecoveryCount())
        assertEquals(3L, governor.resetCount())
        assertEquals(3, governor.recoveryFailures())
    }

    @Test fun repeatedOrStaleHeadSamplesDoNotDoubleCountPlayback() {
        val track = FakeTrack().apply { accepted = 800 }
        val governor = PlayoutGovernor(track, 48_000, 4, 20, "test", { 0L }, {})
        governor.writePcm(ByteArray(800), 0, 800)
        track.playbackHeadPosition = 100
        assertEquals(100L, governor.totalPlayedFrames())
        track.playbackHeadPosition = 96
        assertEquals(100L, governor.totalPlayedFrames())
        track.playbackHeadPosition = 100
        assertEquals(100L, governor.totalPlayedFrames())
        track.playbackHeadPosition = 120
        assertEquals(120L, governor.totalPlayedFrames())
        assertEquals(0L, governor.nativeDiscardedFrames())
        assertEquals(0L, governor.resetCount())
    }

    @Test fun hardResyncKeepsObservedPlaybackAndRecordsOnlyTheUnplayedNativeTail() {
        var now = 0L
        val track = FakeTrack().apply { accepted = 48_000 }
        val governor = PlayoutGovernor(track, 48_000, 4, 20, "test", { now }, {})
        governor.writePcm(ByteArray(48_000), 0, 48_000)
        track.playbackHeadPosition = 100
        assertEquals(100L, governor.totalPlayedFrames())
        repeat(30) { governor.retune() } //A sustained native backlog crosses the resync threshold.
        track.onPause = { track.playbackHeadPosition = 200 }
        now = 6_000
        assertTrue(governor.hardResyncIfNeeded())
        assertEquals(200L, governor.totalPlayedFrames())
        assertEquals(11_800L, governor.nativeDiscardedFrames())
        assertEquals(1L, governor.resetCount())
        assertEquals(0L, governor.localRecoveryCount())
        assertEquals(0L, governor.bufferedFrames())
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

    @Test fun bothClockCorrectionsStayWithinRateAndPerTuneLimits() {
        for (high in listOf(false, true)) {
            val track = FakeTrack().apply { accepted = 48_000 }
            val governor = PlayoutGovernor(track, 48_000, 4, 20, "test", { 0L }, {})
            if (high) governor.writePcm(ByteArray(48_000), 0, 48_000)
            var previous = 48_000
            repeat(100) {
                governor.retune()
                val rate = governor.playbackRateHz()
                assertTrue("Unbounded drift correction: $rate", rate in 47_616..48_384)
                assertTrue("Abrupt drift correction: $previous -> $rate", kotlin.math.abs(rate - previous) <= 12)
                previous = rate
            }
            assertTrue(if (high) previous > 48_000 else previous < 48_000)
            governor.retune(activeInput = false)
            assertEquals(48_000, governor.playbackRateHz())
            assertEquals(0, track.flushes)
        }
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
