package com.cuscus.wifiaudiostreaming

import android.content.Context
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.cuscus.wifiaudiostreaming.diagnostics.RuntimeDiagnostics

/** Remote-only sender volume controls; never requests focus from the captured player. */
internal class SenderVolumeSession(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val session = MediaSession(context, "AudioBridge transmission volume")
    private var released = false
    private val provider = object : VolumeProvider(
        VolumeProvider.VOLUME_CONTROL_ABSOLUTE, 100, VolumeScale.percent(NetworkManager.serverVolume.value, 2f)
    ) {
        override fun onAdjustVolume(direction: Int) {
            main.post {
                if (released) return@post
                val step = when (direction) {
                    AudioManager.ADJUST_RAISE -> 5
                    AudioManager.ADJUST_LOWER -> -5
                    else -> 0
                }
                if (step != 0) applyPercent(VolumeScale.percent(NetworkManager.serverVolume.value, 2f) + step)
            }
        }

        override fun onSetVolumeTo(volume: Int) {
            main.post { if (!released) applyPercent(volume) }
        }
    }

    init {
        // This is a volume session, not a controller for the captured player's
        // transport. Do not publish play/pause actions or request audio focus.
        try {
            session.setCallback(object : MediaSession.Callback() {}, main)
            session.setPlaybackToRemote(provider)
            session.setPlaybackState(PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .setActions(0L).build())
            session.isActive = true
            RuntimeDiagnostics.event("SENDER_VOLUME_SESSION_START", "mode=remote_only")
        } catch (error: RuntimeException) {
            session.release()
            throw error
        }
    }

    private fun applyPercent(percent: Int) {
        NetworkManager.setServerVolume(VolumeScale.gain(percent, 2f))
        update(NetworkManager.serverVolume.value)
    }

    fun update(gain: Float) {
        if (!released) provider.setCurrentVolume(VolumeScale.percent(gain, 2f))
    }

    fun release() {
        if (released) return
        released = true
        main.removeCallbacksAndMessages(null)
        session.isActive = false
        session.release()
        RuntimeDiagnostics.event("SENDER_VOLUME_SESSION_STOP")
    }
}
