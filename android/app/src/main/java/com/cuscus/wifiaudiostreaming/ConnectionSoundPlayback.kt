package com.cuscus.wifiaudiostreaming

import android.content.Context
import android.media.MediaPlayer
import android.os.SystemClock
import com.cuscus.wifiaudiostreaming.diagnostics.RuntimeDiagnostics
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps cue failures observable without letting an optional cue end audio reception. */
internal object ConnectionSoundPlayback {
    private var lastFailureAt = -30_000L
    private var suppressedFailures = 0L

    private @Synchronized fun reportFailure(detail: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFailureAt < 30_000) {
            suppressedFailures++
            return
        }
        RuntimeDiagnostics.event("CONNECTION_SOUND_FAILED", "$detail suppressed=$suppressedFailures", true)
        lastFailureAt = now
        suppressedFailures = 0
    }

    fun play(context: Context) {
        var player: MediaPlayer? = null
        val finished = AtomicBoolean()
        fun finish(error: String? = null) {
            if (!finished.compareAndSet(false, true)) return
            error?.let(::reportFailure)
            try { player?.release() }
            catch (failure: Exception) { reportFailure("phase=release type=${failure.javaClass.simpleName}") }
        }
        try {
            val created = MediaPlayer.create(context.applicationContext, R.raw.connection_sound)
                ?: throw IllegalStateException("MediaPlayer.create returned null")
            player = created
            created.setOnCompletionListener { finish() }
            created.setOnErrorListener { _, what, extra ->
                finish("phase=playback what=$what extra=$extra")
                true
            }
            created.start()
        } catch (failure: Exception) {
            finish("phase=create_start type=${failure.javaClass.simpleName}")
        }
    }
}
