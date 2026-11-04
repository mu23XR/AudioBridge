package com.cuscus.wifiaudiostreaming

import android.media.AudioTrack
import com.cuscus.wifiaudiostreaming.diagnostics.RuntimeDiagnostics
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

internal object MixGains {
    fun targets(weights: Map<String, Float>, master: Float): Map<String, Float> {
        val safe = weights.mapValues { it.value.coerceIn(0f, 1f) }
        val divisor = maxOf(1f, safe.values.sum())
        return safe.mapValues { it.value / divisor * master.coerceIn(0f, 1f) }
    }
}

/** Only this worker changes gains; decreases precede increases to preserve headroom. */
internal object ReceiverMixer {
    private class Entry(val track: AudioTrack, var gain: Float = 0f, var lastErrorAt: Long = -10000L)
    private val tracks = ConcurrentHashMap<String, Entry>()
    @Volatile private var weights: Map<String, Float> = emptyMap()
    @Volatile private var master = 1f
    @Volatile var focusGain = 1f
    private var worker: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized fun register(id: String, track: AudioTrack) {
        check(tracks[id] == null) { "Previous source output has not been released" }
        check(track.setVolume(0f) == AudioTrack.SUCCESS) { "Cannot initialize source gain" }
        tracks[id] = Entry(track)
        ensureWorker()
    }

    private fun ensureWorker() {
        if (worker?.isActive == true) return
        val next = scope.launch(start = CoroutineStart.LAZY) {
            val owner = currentCoroutineContext()[Job]
            try {
            while (isActive && tracks.isNotEmpty()) {
                val desired = MixGains.targets(weights.filterKeys { tracks.containsKey(it) }, master)
                val changes = tracks.entries.map { (id, e) ->
                    val target = (desired[id] ?: 0f) * focusGain
                    Triple(id, e, e.gain + (target - e.gain).coerceIn(-0.04f, 0.04f))
                }.sortedBy { it.third - it.second.gain }
                for ((id, e, gain) in changes) {
                    if (tracks[id] !== e || gain == e.gain) continue
                    try {
                        val remaining = (1f - tracks.values.filter { it !== e }.sumOf { it.gain.toDouble() }.toFloat()).coerceAtLeast(0f)
                        val applied = minOf(gain, remaining).coerceIn(0f, 1f)
                        val result = e.track.setVolume(applied)
                        if (result != AudioTrack.SUCCESS) throw IllegalStateException("setVolume=$result")
                        e.gain = applied
                    } catch (failure: Exception) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - e.lastErrorAt >= 10000L) {
                            e.lastErrorAt = now
                            RuntimeDiagnostics.event("SOURCE_GAIN_FAILED", "source=$id ${failure.message}", true)
                        }
                        // Retain the last applied gain in the budget; the owning
                        // player detects/releases a failed track, not this worker.
                    }
                }
                delay(10)
            }
            } finally {
                synchronized(ReceiverMixer) {
                    if (worker === owner) {
                        worker = null
                        if (tracks.isNotEmpty()) ensureWorker()
                    }
                }
            }
        }
        worker = next
        next.start()
    }
    fun hasOutput(id: String): Boolean = tracks.containsKey(id)

    fun setWeights(weights: Map<String, Float>, master: Float) {
        this.weights = weights
        this.master = master
    }

    @Synchronized fun unregister(id: String, track: AudioTrack) {
        val entry = tracks[id] ?: return
        if (entry.track !== track) return
        // The caller has stopped/released this output before freeing its budget.
        tracks.remove(id, entry)
    }
}
