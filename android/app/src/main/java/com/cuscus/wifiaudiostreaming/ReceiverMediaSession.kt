package com.cuscus.wifiaudiostreaming

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.cuscus.wifiaudiostreaming.diagnostics.RuntimeDiagnostics

/** One focus/session owner for all receiver tracks, never one per source. */
internal class ReceiverMediaSession(private val context: Context) {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val session = MediaSession(context, "AudioBridge receiver")
    private var focusRequested = false
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        ReceiverMixer.focusGain = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> 1f
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> .2f
            else -> 0f
        }
        RuntimeDiagnostics.event("RECEIVER_AUDIO_FOCUS", "change=$change")
        ClientSessionController.updateMasterVolume()
    }
    private val request = if (Build.VERSION.SDK_INT >= 26)
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper())).build() else null

    init {
        session.setCallback(object : MediaSession.Callback() {
            override fun onStop() = RuntimeModeController.selectOff(context)
            override fun onPause() = RuntimeModeController.selectOff(context)
            override fun onPlay() = RuntimeModeController.selectReceiver(context)
        })
        session.isActive = true
    }
    @Suppress("DEPRECATION") fun requestFocus() {
        focusRequested = true
        val result = if (Build.VERSION.SDK_INT >= 26) manager.requestAudioFocus(request!!)
        else manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        ReceiverMixer.focusGain = if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) 1f else 0f
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            RuntimeDiagnostics.event("RECEIVER_AUDIO_FOCUS_DENIED", "code=$result", true)
    }
    fun update(active: Boolean) {
        if (active && !focusRequested) requestFocus()
        session.setPlaybackState(PlaybackState.Builder()
            .setState(if (active) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (active) 1f else 0f)
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP).build())
    }
    @Suppress("DEPRECATION") fun release() {
        if (Build.VERSION.SDK_INT >= 26) manager.abandonAudioFocusRequest(request!!)
        else manager.abandonAudioFocus(listener)
        session.isActive = false; session.release()
        ReceiverMixer.focusGain = 1f
    }
}
