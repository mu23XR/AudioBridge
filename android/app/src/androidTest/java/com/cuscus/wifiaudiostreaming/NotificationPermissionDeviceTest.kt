package com.cuscus.wifiaudiostreaming

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.cuscus.wifiaudiostreaming.data.SettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Emulator-only: host revokes/restores permission around this standalone run. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class NotificationPermissionDeviceTest {
    @Suppress("DEPRECATION")
    @Test fun deniedNotificationDoesNotStartTransportOrCrashAfterForegroundDeadline() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        // Some emulators spoof a physical ro.hardware. Require explicit host
        // opt-in instead of silently skipping the regression on those images.
        assumeTrue("Run this fixture only with explicit debug-emulator opt-in",
            context.packageName.endsWith(".debug") &&
                InstrumentationRegistry.getArguments().getString("allowNotificationFixture") == "true")
        fun main(action: () -> Unit) = inst.runOnMainSync(action)
        val permission = Manifest.permission.POST_NOTIFICATIONS
        assumeTrue("Revoke debug notification permission from the host before instrumentation",
            context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
        val prefs = listOf("wfas_client_runtime", "audiobridge_runtime_mode", "wfas_task_runtime")
            .map { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
        val saved = prefs.map { it.all.toMap() }
        val peer = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 150 }
        try {
            main {
                ClientSessionController.userDisconnect(context)
                context.stopService(Intent(context, ClientService::class.java))
            }
            assertFalse(NotificationCenter.canPost(context))
            val settings = runBlocking { SettingsDataStore(context).settingsFlow.first() }.copy(
                connectionSoundEnabled = false, disconnectionSoundEnabled = false,
                sendClientMicrophone = false, networkInterface = "Auto")
            main {
                ClientSessionController.connect(context,
                    ServerInfo("127.0.0.1", false, peer.localPort, "notification-denied-fixture"),
                    settingsOverride = settings)
            }
            val source = ClientSessionController.sources.value.single()
            assertTrue(source.needsAttention)
            assertEquals(context.getString(R.string.source_notification_permission), source.status)
            // Also exercise commands already queued before permission was lost.
            // Promotion must be fulfilled before the service's permission guard
            // stops it; two deliveries reproduce the original deadline crash.
            main {
                repeat(2) { context.startForegroundService(Intent(context, ClientService::class.java)) }
            }
            SystemClock.sleep(35_000) // Exceeds the platform's usual 10–30 s promotion deadline.
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            assertFalse("denied receiver host still running", manager.getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == ClientService::class.java.name })
            assertFalse(NetworkManager.isStreamingCurrent.value)
            assertFalse(ReceiverMixer.hasOutput(source.id))
            try {
                peer.receive(DatagramPacket(ByteArray(2048), 2048))
                fail("notification-denied receiver opened a UDP endpoint")
            } catch (_: SocketTimeoutException) { }
        } finally {
            main {
                ClientSessionController.userDisconnect(context)
                RoleSelectionGate.selectOff(context)
                context.stopService(Intent(context, ClientService::class.java))
            }
            peer.close()
            for ((index, pref) in prefs.withIndex()) {
                val editor = pref.edit().clear()
                for ((key, value) in saved[index]) when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                }
                check(editor.commit()) { "Cannot restore permission fixture state" }
            }
        }
    }
}
