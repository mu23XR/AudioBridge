/** Arrival summaries are window-bounded, silence-aware and independent of PCM storage. */
internal object DesktopReceiveTimingChecks {
    fun runChecks() {
        packetAndFramePeaksAreIndependent()
        explicitSilenceDoesNotBecomeANetworkGap()
        batchesAreClippedButDeliveryGapsCrossWindows()
        clockRollbackStartsANewBatch()
        indefinitelyClusteredInputStaysWindowBounded()
        invalidFrameCountsAreRejected()
        println("Receive timing: PCM-only counts,2ms batches, silence boundaries and bounded reporting windows passed")
    }

    private fun packetAndFramePeaksAreIndependent() {
        val timing = DesktopReceiveTiming()
        timing.observe(100, 480, false)
        timing.observe(101, 240, false)
        timing.observe(103, 120, false) // Inclusive2ms joins the same batch.
        timing.observe(106, 960, false) //3ms starts a new, longer single-packet batch.
        val snapshot = timing.snapshotAndReset()
        check(snapshot == DesktopReceiveTiming.Snapshot(4, 1800, 3, 3, 960))
        check(snapshot.format() == "realPcmPackets=4 realPcmFrames=1800 maxRealPcmGapMs=3 maxBatchPackets=3 maxBatchFrames=960")
    }

    private fun explicitSilenceDoesNotBecomeANetworkGap() {
        val timing = DesktopReceiveTiming()
        timing.observe(100, 480, false)
        timing.observe(110, 480, false)
        timing.observe(10_000, 0, true)
        timing.observe(20_000, 960, false)
        check(timing.snapshotAndReset() == DesktopReceiveTiming.Snapshot(3, 1920, 10, 1, 960))
        timing.observe(25_000, 480, true) // Full zero PCM is also intentional silence.
        timing.observe(35_000, 120, false)
        check(timing.snapshotAndReset() == DesktopReceiveTiming.Snapshot(1, 120, 0, 1, 120))
    }

    private fun batchesAreClippedButDeliveryGapsCrossWindows() {
        val timing = DesktopReceiveTiming()
        timing.observe(0, 100, false)
        timing.observe(1, 200, false)
        check(timing.snapshotAndReset() == DesktopReceiveTiming.Snapshot(2, 300, 1, 2, 300))
        timing.observe(2, 300, false)
        check(timing.snapshotAndReset() == DesktopReceiveTiming.Snapshot(1, 300, 1, 1, 300))
        check(timing.snapshotAndReset() == DesktopReceiveTiming.Snapshot(0, 0, 0, 0, 0))
        timing.observe(70, 400, false)
        check(timing.snapshotAndReset() == DesktopReceiveTiming.Snapshot(1, 400, 68, 1, 400))
    }

    private fun clockRollbackStartsANewBatch() {
        val timing = DesktopReceiveTiming()
        timing.observe(100, 10, false)
        timing.observe(100, 20, false)
        timing.observe(99, 30, false)
        timing.observe(101, 40, false)
        check(timing.snapshotAndReset() == DesktopReceiveTiming.Snapshot(4, 100, 2, 2, 70))
    }

    private fun indefinitelyClusteredInputStaysWindowBounded() {
        val timing = DesktopReceiveTiming()
        repeat(1_000) { time ->
            timing.observe(time.toLong(), 1, false)
            val snapshot = timing.snapshotAndReset()
            check(snapshot.realPcmPackets == 1L && snapshot.realPcmFrames == 1L)
            check(snapshot.maxBatchPackets == 1L && snapshot.maxBatchFrames == 1L)
        }
    }

    private fun invalidFrameCountsAreRejected() {
        val timing = DesktopReceiveTiming()
        check(runCatching { timing.observe(0, -1, false) }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { timing.observe(0, 0, false) }.exceptionOrNull() is IllegalArgumentException)
        check(timing.snapshotAndReset() == DesktopReceiveTiming.Snapshot(0, 0, 0, 0, 0))
    }
}
