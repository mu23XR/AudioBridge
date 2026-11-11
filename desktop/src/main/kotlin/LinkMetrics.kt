object LinkMetrics {
    private const val REPORT_INTERVAL_MS = 5000L

    data class Snapshot(
        val transport: String = "-",
        val packets: Long = 0,
        val lost: Long = 0,
        val reordered: Long = 0,
        val jitterMs: Double = 0.0,
        val peakJitterMs: Double = 0.0,
        val meanIntervalMs: Double = 0.0,
        val duplicates: Long = 0
    ) {
        val lossPercent: Double
            get() {
                val expected = packets + lost
                return if (expected <= 0) 0.0 else lost * 100.0 / expected
            }

        fun format(): String = String.format(
            "%s pkts=%d loss=%.2f%% reorder=%d duplicate=%d jitter=%.2fms peak=%.2fms interval=%.2fms",
            transport, packets, lossPercent, reordered, duplicates, jitterMs, peakJitterMs, meanIntervalMs
        )
    }

    @Volatile var snapshot: Snapshot = Snapshot()
        private set

    private var metrics: DesktopPacketMetrics? = null
    private var transport = "-"
    private var lastReportMs = 0L

    @Synchronized fun start(transportLabel: String, rate: Int) {
        require(rate > 0) { "Metrics sample rate must be positive: $rate" }
        metrics = DesktopPacketMetrics(rate)
        transport = transportLabel
        lastReportMs = System.currentTimeMillis()
        snapshot = Snapshot(transport = transportLabel)
    }

    @Synchronized fun stop() {
        val active = metrics ?: return
        snapshot = active.snapshot(transport)
        metrics = null
        AppDebug.log("[METRICS] final ${snapshot.format()}")
    }

    /** Returns false for duplicate or late packets that must not rewind playback. */
    @Synchronized fun onPacket(seq: Int, samplePos: Long, silence: Boolean = false): Boolean {
        // Preserve packet delivery for callers that did not opt into metrics.
        val active = metrics ?: return true
        val fresh = active.observe(seq, samplePos, silence)
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastReportMs >= REPORT_INTERVAL_MS) {
            lastReportMs = nowMs
            snapshot = active.snapshot(transport)
            AppDebug.log("[METRICS] ${snapshot.format()}")
        }
        return fresh
    }
}
