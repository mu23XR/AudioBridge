package com.cuscus.wifiaudiostreaming

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.concurrent.thread

/** Run its two methods in separate instrumentation processes, in this order. */
@RunWith(AndroidJUnit4::class)
class VolumePersistenceDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = context.getSharedPreferences("audiobridge_volume", Context.MODE_PRIVATE)
    private val checkpoint = context.getSharedPreferences("test20-volume-fixture", Context.MODE_PRIVATE)
    private fun waitSaved(sender: Float, receiver: Float) {
        val until = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < until) {
            if (prefs.getFloat("sender_gain", -1f) == sender && prefs.getFloat("receiver_gain", -1f) == receiver) return
            SystemClock.sleep(20)
        }
        fail("Latest sender/receiver gain was not persisted")
    }

    @Test fun saveConcurrentChangesForSeparateProcessRestore() {
        check(checkpoint.edit().putFloat("sender", NetworkManager.serverVolume.value)
            .putFloat("receiver", NetworkManager.clientVolume.value).commit())
        val sender = thread { repeat(100) { NetworkManager.setServerVolume(it / 50f) } }
        val receiver = thread { repeat(100) { NetworkManager.setClientVolume(it / 100f) } }
        sender.join(); receiver.join()
        NetworkManager.setServerVolume(1.54f)
        NetworkManager.setClientVolume(.35f)
        waitSaved(1.54f, .35f)
    }

    @Test fun restoreOnNewApplicationProcessThenReturnOriginalValues() {
        check(checkpoint.contains("sender")) { "Run saveConcurrentChanges in a separate process first" }
        try {
            // No setter before these checks: Application.onCreate restored them.
            assertEquals(1.54f, NetworkManager.serverVolume.value, 0f)
            assertEquals(.35f, NetworkManager.clientVolume.value, 0f)
            assertEquals(77, VolumeScale.percent(NetworkManager.serverVolume.value, 2f))
        } finally {
            val sender = checkpoint.getFloat("sender", 1f)
            val receiver = checkpoint.getFloat("receiver", 1f)
            NetworkManager.setServerVolume(sender)
            NetworkManager.setClientVolume(receiver)
            waitSaved(sender, receiver)
            check(checkpoint.edit().clear().commit())
        }
    }
}
