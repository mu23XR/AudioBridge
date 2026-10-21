package com.cuscus.wifiaudiostreaming

/** Strongly owns each optional cue until completion, failure or its deadline. */
internal class CuePlaybackLifetime<P : Any>(
    private val start: (P, (String?) -> Unit) -> Unit,
    private val release: (P) -> Unit,
    private val scheduleTimeout: (() -> Unit) -> (() -> Unit),
    private val reportFailure: (String) -> Unit
) {
    private val active = mutableSetOf<Playback>()
    internal val activeCount: Int get() = synchronized(active) { active.size }

    fun play(create: () -> P) {
        val playback = Playback()
        synchronized(active) { active.add(playback) }
        var phase = "timeout_schedule"
        try {
            playback.setTimeoutCancellation(scheduleTimeout { playback.finish("phase=timeout") })
            phase = "create"
            val player = create()
            phase = "start"
            playback.attachAndStart(player)
        } catch (failure: Exception) {
            playback.finish("phase=$phase type=${failure.javaClass.simpleName}")
        }
    }

    private fun releaseSafely(player: P) {
        try { release(player) }
        catch (failure: Exception) { reportFailure("phase=release type=${failure.javaClass.simpleName}") }
    }

    private fun cancelSafely(cancel: (() -> Unit)?) {
        try { cancel?.invoke() }
        catch (failure: Exception) { reportFailure("phase=timeout_cancel type=${failure.javaClass.simpleName}") }
    }

    private inner class Playback {
        private val lock = Any()
        private var player: P? = null
        private var finished = false
        private var cancelTimeout: (() -> Unit)? = null

        fun setTimeoutCancellation(cancel: () -> Unit) {
            val cancelNow = synchronized(lock) {
                if (finished) true else { cancelTimeout = cancel; false }
            }
            // A scheduler may deliver its callback before returning the cancellation handle.
            if (cancelNow) cancelSafely(cancel)
        }

        fun attachAndStart(created: P) {
            val attached = synchronized(lock) {
                if (finished) false else {
                    player = created
                    // Serialize start with timeout/release, without blocking other cues or creation.
                    start(created, ::finish)
                    true
                }
            }
            // Creation may return after its deadline; that player must never be started.
            if (!attached) releaseSafely(created)
        }

        fun finish(error: String? = null) {
            val cleanup = synchronized(lock) {
                if (finished) return
                finished = true
                val retained = player
                player = null
                val cancel = cancelTimeout
                cancelTimeout = null
                retained to cancel
            }
            synchronized(active) { active.remove(this) }
            cancelSafely(cleanup.second)
            error?.let(reportFailure)
            cleanup.first?.let(::releaseSafely)
        }
    }
}
