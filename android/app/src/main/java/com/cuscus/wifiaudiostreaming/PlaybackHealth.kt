package com.cuscus.wifiaudiostreaming

/** One-second playback samples, shared by unicast and multicast receivers. */
internal class PlaybackHealth(private val adaptive: Boolean) {
    data class Decision(val targetDeltaMs: Int = 0, val rebuild: Boolean = false)

    private var lastUnderruns = 0
    private var shortageSeconds = 0
    private var stormSeconds = 0
    private var cleanSince: Long? = null
    private var lastSampleAt: Long? = null

    fun sample(now: Long, underruns: Int, flowing: Boolean,
               stalledMs: Long, failedRecoveries: Int): Decision {
        if (lastSampleAt?.let { now - it > 2500 } == true) {
            cleanSince = null
            shortageSeconds = 0
            stormSeconds = 0
        }
        lastSampleAt = now
        val delta = (underruns - lastUnderruns).coerceAtLeast(0)
        lastUnderruns = underruns
        val healthy = flowing && delta == 0 && stalledMs < 500 && failedRecoveries == 0
        cleanSince = if (healthy) cleanSince ?: now else null
        shortageSeconds = if (flowing && delta > 0) shortageSeconds + 1 else 0
        stormSeconds = if (flowing && delta > 200) stormSeconds + 1 else 0
        // Repeated flush/play failures survive the governor's local resets.
        // Adaptive at its ceiling must still escalate a dead playback track.
        val rebuild = flowing && (stalledMs >= 5000 || failedRecoveries >= 3 || stormSeconds >= 3)
        var targetDelta = 0
        if (adaptive && shortageSeconds >= 2) {
            targetDelta = 10
            shortageSeconds = 0
        } else if (adaptive && cleanSince?.let { now - it >= 30_000 } == true) {
            targetDelta = -5
            cleanSince = now
        }
        return Decision(targetDelta, rebuild)
    }
}
