package com.cuscus.wifiaudiostreaming

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cuscus.wifiaudiostreaming.shizuku.ShizukuAudioBridgeManager
import com.cuscus.wifiaudiostreaming.shizuku.ShizukuBridgeHostService
import org.junit.Assert.*
import org.junit.Assume.*
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.Shizuku

/** Only run on a quiet, already-authorized device. Never starts or captures user media. */
@RunWith(AndroidJUnit4::class)
class CapturePolicyDeviceTest {
    @Test fun authorizedShellPolicyStartsAndRestoresVolumeAfterStop() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        assumeTrue(Shizuku.pingBinder())
        assumeTrue(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        assumeFalse("User media is active; leave it alone", audio.isMusicActive)
        assumeTrue("Communication audio is active", audio.mode == AudioManager.MODE_NORMAL)
        val originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val originalMute = audio.isStreamMute(AudioManager.STREAM_MUSIC)
        val runtime = context.getSharedPreferences("wfas_shizuku_runtime", Context.MODE_PRIVATE)
        val mode = context.getSharedPreferences("audiobridge_runtime_mode", Context.MODE_PRIVATE)
        val savedRuntime = runtime.all.toMap(); val savedMode = mode.all.toMap()
        try {
            instrumentation.runOnMainSync {
                RoleSelectionGate.selectSender(context)
                ShizukuAudioBridgeManager.start(context, ShizukuAudioBridgeManager.Config(
                    port = 45997, sampleRate = 48000, channels = 2, packetBytes = 612,
                    keepPlayingOnDevice = true, persistAfterClient = true))
            }
            val end = SystemClock.elapsedRealtime() + 12000
            while (SystemClock.elapsedRealtime() < end &&
                ShizukuAudioBridgeManager.state.value !is ShizukuAudioBridgeManager.State.Running &&
                ShizukuAudioBridgeManager.state.value !is ShizukuAudioBridgeManager.State.Error)
                SystemClock.sleep(50)
            val state = ShizukuAudioBridgeManager.state.value
            assertTrue("Capture result: $state", state is ShizukuAudioBridgeManager.State.Running)
            val detail = (state as ShizukuAudioBridgeManager.State.Running).detail
            assertTrue("Policy path must work instead of relying on fallback: $detail", detail.contains("AudioPolicy"))
        } finally {
            instrumentation.runOnMainSync {
                ShizukuAudioBridgeManager.stop(context)
                RoleSelectionGate.selectOff(context)
                context.stopService(Intent(context, ShizukuBridgeHostService::class.java))
            }
            fun restore(prefs: android.content.SharedPreferences, saved: Map<String, *>) {
                val editor = prefs.edit().clear()
                for ((key, value) in saved) when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                }
                check(editor.commit()) { "Fixture preferences could not be restored" }
            }
            restore(runtime, savedRuntime); restore(mode, savedMode)
            assertEquals("Media volume restored", originalVolume, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertEquals("Media mute restored", originalMute, audio.isStreamMute(AudioManager.STREAM_MUSIC))
        }
    }
}
