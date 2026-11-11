package com.cuscus.wifiaudiostreaming

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cuscus.wifiaudiostreaming.data.SettingsDataStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** A silent loopback peer: no capture or playback; exercises real service wakeups. */
@RunWith(AndroidJUnit4::class)
class ReceiverRetryDeviceTest {
    @Test fun missingAckCannotBypassBackoffThroughServiceObservers() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        fun main(block: () -> Unit) = inst.runOnMainSync(block)
        val prefs = listOf("wfas_client_runtime", "audiobridge_runtime_mode").map {
            context.getSharedPreferences(it, Context.MODE_PRIVATE)
        }
        val saved = prefs.map { it.all.toMap() }
        val peer = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 100 }
        val endpoints = ConcurrentHashMap<Int, Long>()
        val running = AtomicBoolean(true)
        val reader = Thread {
            val packet = DatagramPacket(ByteArray(2048), 2048)
            while (running.get()) {
                try {
                    packet.length = 2048; peer.receive(packet)
                    if (String(packet.data, 0, packet.length).startsWith("HELLO_FROM_CLIENT"))
                        endpoints.putIfAbsent(packet.port, SystemClock.elapsedRealtime())
                } catch (_: SocketTimeoutException) { }
                catch (closed: SocketException) { if (running.get()) throw closed }
            }
        }.apply { start() }
        try {
            val settings = runBlocking { SettingsDataStore(context).settingsFlow.first() }.copy(
                connectionSoundEnabled = false, sendClientMicrophone = false, networkInterface = "Auto")
            main {
                ClientSessionController.userDisconnect(context)
                ClientSessionController.connect(context,
                    ServerInfo("127.0.0.1", false, peer.localPort, "no-ack-fixture"), settingsOverride = settings)
            }
            val initialDeadline = SystemClock.elapsedRealtime() + 5000
            while (endpoints.isEmpty() && SystemClock.elapsedRealtime() < initialDeadline) SystemClock.sleep(30)
            assertEquals("first HELLO missing", 1, endpoints.size)
            val first = endpoints.values.single()
            while (SystemClock.elapsedRealtime() < first + 6700) {
                main { ClientSessionController.resumeIfNeeded(context) }
                SystemClock.sleep(40)
            }
            assertEquals("observer bypassed 1s backoff after 6s ACK timeout", 1, endpoints.size)
            val retryDeadline = SystemClock.elapsedRealtime() + 3000
            while (endpoints.size < 2 && SystemClock.elapsedRealtime() < retryDeadline) SystemClock.sleep(30)
            assertTrue("retry never resumed", endpoints.size >= 2)
            val times = endpoints.values.sorted()
            assertTrue("retry started before deadline", times[1] - times[0] >= 6900)
        } finally {
            main {
                ClientSessionController.userDisconnect(context)
                RoleSelectionGate.selectOff(context)
                context.stopService(Intent(context, ClientService::class.java))
            }
            running.set(false); peer.close(); reader.join(1000)
            for ((index, pref) in prefs.withIndex()) {
                val editor = pref.edit().clear()
                for ((key, value) in saved[index]) when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                }
                check(editor.commit()) { "Cannot restore regression fixture state" }
            }
        }
    }
}
