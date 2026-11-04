/** Numeric arrival telemetry only; no payload, endpoint or credential is retained. */
internal class DesktopReceiveTiming {
    data class Snapshot(
        val realPcmPackets: Long,
        val realPcmFrames: Long,
        val maxRealPcmGapMs: Long,
        val maxBatchPackets: Long,
        val maxBatchFrames: Long
    ) {
        fun format(): String = "realPcmPackets=$realPcmPackets realPcmFrames=$realPcmFrames " +
            "maxRealPcmGapMs=$maxRealPcmGapMs maxBatchPackets=$maxBatchPackets maxBatchFrames=$maxBatchFrames"
    }

    private var lastRealArrivalMs: Long? = null
    private var realPackets = 0L
    private var realFrames = 0L
    private var maxGapMs = 0L
    private var batchPackets = 0L
    private var batchFrames = 0L
    private var maxBatchPackets = 0L
    private var maxBatchFrames = 0L

    @Synchronized fun observe(nowMs: Long = System.nanoTime() / 1_000_000, frames: Int, silence: Boolean) {
        require(frames >= 0 && (silence || frames > 0)) { "Real PCM must contain complete positive frames" }
        if (silence) {
            // An explicit silent interval is not a network delivery gap. A
            // resumed source starts a new real-arrival interval and batch.
            lastRealArrivalMs = null
            batchPackets = 0; batchFrames = 0
            return
        }
        val gap = lastRealArrivalMs?.let { nowMs - it }
        if (gap != null && gap >= 0) maxGapMs = maxOf(maxGapMs, gap)
        if (gap == null || gap !in 0..2) {
            batchPackets = 0; batchFrames = 0
        }
        lastRealArrivalMs = nowMs
        realPackets++; realFrames += frames
        batchPackets++; batchFrames += frames
        maxBatchPackets = maxOf(maxBatchPackets, batchPackets)
        maxBatchFrames = maxOf(maxBatchFrames, batchFrames)
    }

    /**
     * Batch sizes are clipped to each reporting window, so an indefinitely
     * clustered stream never carries an unbounded batch count across reports.
     * Keep the last arrival only for the next window's first delivery gap;
     * explicit silence clears it. Packet/frame batch peaks are independent
     * maxima, since different batches may use different packet durations.
     */
    @Synchronized fun snapshotAndReset(): Snapshot {
        val result = Snapshot(realPackets, realFrames, maxGapMs, maxBatchPackets, maxBatchFrames)
        realPackets = 0; realFrames = 0; maxGapMs = 0
        batchPackets = 0; batchFrames = 0
        maxBatchPackets = 0; maxBatchFrames = 0
        return result
    }
}
