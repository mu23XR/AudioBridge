package com.cuscus.wifiaudiostreaming

import android.content.Context
import com.cuscus.wifiaudiostreaming.diagnostics.RuntimeDiagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Coalesce wakeups, not values: concurrent UI/remote callers cannot queue stale snapshots. */
internal object VolumeMemory {
    private data class Snapshot(val sender: Float, val receiver: Float)
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var initialized = false

    fun initialize(context: Context) {
        check(!initialized) { "Volume memory initialized twice" }
        val prefs = context.getSharedPreferences("audiobridge_volume", Context.MODE_PRIVATE)
        fun read(key: String, max: Float): Float {
            val value = try { prefs.getFloat(key, 1f) } catch (invalid: ClassCastException) {
                RuntimeDiagnostics.event("VOLUME_STORAGE_INVALID", "field=$key type=wrong", true)
                return 1f
            }
            if (!value.isFinite() || value !in 0f..max) {
                RuntimeDiagnostics.event("VOLUME_STORAGE_INVALID", "field=$key", true)
                return 1f
            }
            return value
        }
        NetworkManager.restoreVolumes(read("sender_gain", 2f), read("receiver_gain", 1f))
        initialized = true
        scope.launch {
            for (signal in writes) {
                try {
                    val snapshot = Snapshot(NetworkManager.serverVolume.value,
                        NetworkManager.clientVolume.value)
                    check(prefs.edit().putFloat("sender_gain", snapshot.sender)
                        .putFloat("receiver_gain", snapshot.receiver).commit()) { "Volume commit rejected" }
                } catch (failure: Exception) {
                    RuntimeDiagnostics.event("VOLUME_SAVE_FAILED", failure.javaClass.simpleName, true)
                }
            }
        }
    }

    fun save() {
        check(initialized) { "Volume memory is not initialized" }
        check(writes.trySend(Unit).isSuccess) { "Volume writer is unavailable" }
    }
}
