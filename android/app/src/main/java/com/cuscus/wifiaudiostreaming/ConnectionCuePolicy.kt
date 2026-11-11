package com.cuscus.wifiaudiostreaming

/** One cue per explicit connection, shared by its automatic transport retries. */
internal class ConnectionCuePolicy {
    private var generation = 0L
    private var pending = true

    @Synchronized fun reset() {
        generation++
        pending = true
    }

    @Synchronized fun beginAttempt(enabled: Boolean) = Attempt(this, generation, enabled)

    private @Synchronized fun claim(owner: Long, enabled: Boolean): Boolean {
        // A cancelled transport must not consume the replacement connection's cue.
        if (owner != generation || !pending) return false
        pending = false
        return enabled
    }

    class Attempt internal constructor(
        private val policy: ConnectionCuePolicy,
        private val generation: Long,
        private val enabled: Boolean
    ) {
        /** Call only for a validated, fresh source frame, including silent frames. */
        fun onValidFrame(): Boolean = policy.claim(generation, enabled)
    }
}
