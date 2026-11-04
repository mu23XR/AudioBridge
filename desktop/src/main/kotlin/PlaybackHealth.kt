/** Monotonic, receiver-local WFAS target. Retained through output rebuilds/retries. */
internal class PlaybackHealth(val adaptive: Boolean, initialTargetMs: Int, private val minimumMs: Int = 20) {
    companion object { const val MAX_ADAPTIVE_TARGET_MS = 80 }
    init { require(minimumMs in 5..20) }
    @Volatile var targetMs: Int = initialTargetMs.coerceIn(if (adaptive) 20 else minimumMs, if (adaptive) MAX_ADAPTIVE_TARGET_MS else 400)
        private set
    private var lastUnderruns = 0L
    private var lastSampleAt: Long? = null
    private var cleanSince: Long? = null
    private var shortageSeconds = 0
    private var stormSeconds = 0
    var rebuildRequested: Boolean = false
        private set

    fun sample(nowMs: Long, underruns: Long, flowing: Boolean, stalledMs: Long, failedRecoveries: Int,
               hasQueuedAudio: Boolean = flowing): Int {
        val gap = lastSampleAt?.let { nowMs - it } ?: 1000L
        lastSampleAt = nowMs
        if (gap > 2500 || gap < 0) { cleanSince = null; shortageSeconds = 0; stormSeconds = 0 }
        val delta = (underruns - lastUnderruns).coerceAtLeast(0)
        lastUnderruns = underruns
        val healthy = flowing && delta == 0L && stalledMs < 500 && failedRecoveries == 0
        cleanSince = if (healthy) cleanSince ?: nowMs else null
        shortageSeconds = if (flowing && delta > 0) shortageSeconds + 1 else 0
        stormSeconds = if (flowing && delta > 200) stormSeconds + 1 else 0
        rebuildRequested = ((flowing || hasQueuedAudio) && (stalledMs >= 5000 || failedRecoveries >= 3)) ||
            (flowing && stormSeconds >= 3)
        val previous = targetMs
        if (adaptive && shortageSeconds >= 2) {
            targetMs = (targetMs + 10).coerceAtMost(MAX_ADAPTIVE_TARGET_MS)
            shortageSeconds = 0
        } else if (adaptive && cleanSince?.let { nowMs - it >= 30_000 } == true) {
            targetMs = (targetMs - 5).coerceAtLeast(20)
            cleanSince = nowMs
        }
        return targetMs - previous
    }
}
