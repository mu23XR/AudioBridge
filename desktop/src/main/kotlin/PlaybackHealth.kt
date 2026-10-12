/** Monotonic, receiver-local WFAS target. Retained through output rebuilds/retries. */
internal class PlaybackHealth(val adaptive: Boolean, initialTargetMs: Int, private val minimumMs: Int = 20) {
    init { require(minimumMs in 5..20) }
    @Volatile var targetMs: Int = initialTargetMs.coerceIn(if (adaptive) 20 else minimumMs, if (adaptive) 80 else 400)
        private set
    private var lastUnderruns = 0L
    private var lastSampleAt: Long? = null
    private var cleanSince: Long? = null
    private var shortageSeconds = 0

    fun sample(nowMs: Long, underruns: Long, flowing: Boolean, stalledMs: Long, failedRecoveries: Int): Int {
        val gap = lastSampleAt?.let { nowMs - it } ?: 1000L
        lastSampleAt = nowMs
        if (gap > 2500 || gap < 0) { cleanSince = null; shortageSeconds = 0 }
        val delta = (underruns - lastUnderruns).coerceAtLeast(0)
        lastUnderruns = underruns
        val healthy = flowing && delta == 0L && stalledMs < 500 && failedRecoveries == 0
        cleanSince = if (healthy) cleanSince ?: nowMs else null
        shortageSeconds = if (flowing && delta > 0) shortageSeconds + 1 else 0
        val previous = targetMs
        if (adaptive && shortageSeconds >= 2) {
            targetMs = (targetMs + 10).coerceAtMost(80)
            shortageSeconds = 0
        } else if (adaptive && cleanSince?.let { nowMs - it >= 30_000 } == true) {
            targetMs = (targetMs - 5).coerceAtLeast(20)
            cleanSince = nowMs
        }
        return targetMs - previous
    }
}
