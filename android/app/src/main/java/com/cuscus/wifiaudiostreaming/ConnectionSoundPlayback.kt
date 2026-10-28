package com.cuscus.wifiaudiostreaming

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.cuscus.wifiaudiostreaming.diagnostics.RuntimeDiagnostics

/** Keeps cue failures observable without letting an optional cue end audio reception. */
internal object ConnectionSoundPlayback {
    private var lastFailureAt = -30_000L
    private var suppressedFailures = 0L
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val playback = CuePlaybackLifetime<MediaPlayer>(
        start = { player, finish ->
            player.setOnCompletionListener { finish(null) }
            player.setOnErrorListener { _, what, extra ->
                finish("phase=playback what=$what extra=$extra")
                true
            }
            player.start()
        },
        release = { it.release() },
        scheduleTimeout = { finish ->
            val deadline = Runnable { finish() }
            check(timeoutHandler.postDelayed(deadline, 30_000L)) { "Cue watchdog scheduling failed" }
            val cancel: () -> Unit = { timeoutHandler.removeCallbacks(deadline) }
            cancel
        },
        reportFailure = ::reportFailure
    )

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
        playback.play {
            MediaPlayer.create(context.applicationContext, R.raw.connection_sound)
                ?: throw IllegalStateException("MediaPlayer.create returned null")
        }
    }
}
