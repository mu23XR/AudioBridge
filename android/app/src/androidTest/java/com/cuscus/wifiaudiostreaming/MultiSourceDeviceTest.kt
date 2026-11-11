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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Loopback-only fixtures. App playback gain is zero; no capture, user media or OEM settings. */
@RunWith(AndroidJUnit4::class)
class MultiSourceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(label: String, timeout: Long = 10000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) { if (condition()) return; SystemClock.sleep(30) }
        fail("$label: ${ClientSessionController.sources.value.map { it.status to it.playedFrames }}")
    }

    @Test fun isolatedSourcesRecoverMixAndRetainFrameProgressWithoutAudibleOutput() {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("wfas_client_runtime", Context.MODE_PRIVATE)
        val rolePrefs = context.getSharedPreferences("audiobridge_runtime_mode", Context.MODE_PRIVATE)
        val saved = prefs.all.toMap()
        val savedRole = rolePrefs.all.toMap()
        val master = NetworkManager.clientVolume.value
        val first = Fixture(48000, 2)
        val second = Fixture(44100, 1)
        val secure = Fixture(48000, 2, "fixture-only-key")
        val wrong = Fixture(48000, 2, "another-fixture-key")
        try {
            val defaults = runBlocking { SettingsDataStore(context).settingsFlow.first() }.copy(
                adaptiveLatency = false, latencyMs = 45, connectionSoundEnabled = false,
                disconnectionSoundEnabled = false, sendClientMicrophone = false, developerMode = false,
                networkInterface = "Auto")
            main {
                ClientSessionController.userDisconnect(context)
                NetworkManager.setClientVolume(0f)
                ClientSessionController.connect(context, first.peer, settingsOverride = defaults)
                ClientSessionController.connect(context, second.peer, settingsOverride = defaults)
            }
            await("two independent outputs advance") {
                ClientSessionController.sources.value.size == 2 &&
                    ClientSessionController.sources.value.all { it.connected && it.playedFrames > 480 }
            }
            val secondId = ClientSessionController.sources.value.first { it.peer.port == second.port }.id
            val firstId = ClientSessionController.sources.value.first { it.peer.port == first.port }.id
            val helloBefore = second.hellos.get()
            main { ClientSessionController.setLatency(secondId, false, 73) }
            await("manual buffer applies locally") {
                ClientSessionController.sources.value.first { it.id == secondId }.targetMs == 73
            }
            assertEquals("buffer adjustment must not re-handshake", helloBefore, second.hellos.get())
            assertEquals(0, second.byes.get())

            first.silent.set(true)
            SystemClock.sleep(6500) // Beyond the old stale-snapshot watchdog threshold.
            assertTrue(ClientSessionController.sources.value.all { it.connected })
            assertEquals("received keepalives are not network loss", 0L,
                ClientSessionController.sources.value.first { it.id == firstId }.metrics.lost)
            first.silent.set(false)
            await("PCM resumes on the same source") {
                ClientSessionController.sources.value.first { it.id == firstId }.playedFrames > 1000
            }
            main { ClientSessionController.connect(context, secure.peer, "fixture-only-key", defaults) }
            await("encrypted third source advances") {
                ClientSessionController.sources.value.firstOrNull { it.peer.port == secure.port }?.playedFrames?.let { it > 480 } == true
            }
            main { ClientSessionController.connect(context, wrong.peer, "incorrect-fixture-key", defaults) }
            await("wrong key pauses only its source") {
                ClientSessionController.sources.value.firstOrNull { it.peer.port == wrong.port }?.needsAttention == true
            }
            assertTrue(ClientSessionController.sources.value.filter { it.peer.port != wrong.port }.all { it.connected })
            assertFalse(prefs.getString("sources_v2", "").orEmpty().contains("fixture-key"))
            assertFalse(prefs.getString("sources_v2", "").orEmpty().contains("fixture-only-key"))

            main { ClientSessionController.remove(firstId) }
            await("clean stop reaches only the selected sender") { first.byes.get() >= 1 }
            assertEquals(0, second.byes.get())
            assertTrue(ClientSessionController.sources.value.first { it.id == secondId }.connected)
            val before = ClientSessionController.sources.value.first { it.id == secondId }.playedFrames
            main { ClientSessionController.retry(secondId) }
            await("explicit reconnect produces advancing replacement output") {
                second.hellos.get() > helloBefore &&
                    ClientSessionController.sources.value.first { it.id == secondId }.connected &&
                    ClientSessionController.sources.value.first { it.id == secondId }.playedFrames > 480
            }
            assertTrue(before > 0)
            main { ClientSessionController.userDisconnect(context) }
            await("all source outputs are released") {
                !ReceiverMixer.hasOutput(firstId) && !ReceiverMixer.hasOutput(secondId)
            }
            assertTrue(ClientSessionController.sources.value.isEmpty())
            assertEquals("[]", prefs.getString("sources_v2", ""))
        } finally {
            main {
                ClientSessionController.userDisconnect(context)
                RoleSelectionGate.selectOff(context)
                context.stopService(Intent(context, ClientService::class.java))
                NetworkManager.setClientVolume(master)
            }
            first.close(); second.close(); secure.close(); wrong.close()
            fun restore(p: android.content.SharedPreferences, data: Map<String, *>) {
                val editor = p.edit().clear()
                for ((key, value) in data) when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                }
                check(editor.commit()) { "Could not restore fixture preferences" }
            }
            restore(prefs, saved); restore(rolePrefs, savedRole)
        }
    }

    private class Fixture(private val rate: Int, private val channels: Int, private val key: String = "") {
        private val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 2 }
        val port get() = socket.localPort
        val peer get() = ServerInfo("127.0.0.1", false, port, "fixture-$port",
            securityMode = if (key.isEmpty()) "OFF" else "KEY", encrypted = key.isNotEmpty(),
            audioFormat = StreamAudioFormat(rate, channels, 16))
        val hellos = AtomicInteger()
        val byes = AtomicInteger()
        val silent = AtomicBoolean()
        private val running = AtomicBoolean(true)
        private var client: SocketAddress? = null
        private var cnonce = ""
        private val snonce = WfasAuth.nonceHex()
        private var sendDir: WfasCrypto.Dir? = null
        private var seq = 0
        private var position = 0L
        private val thread = Thread {
            val buffer = ByteArray(2048)
            val packet = DatagramPacket(buffer, buffer.size)
            var last = SystemClock.elapsedRealtime()
            try {
                while (running.get()) {
                    try {
                        packet.length = buffer.size; socket.receive(packet)
                        val command = String(buffer, 0, packet.length, Charsets.US_ASCII)
                        if (command.startsWith("HELLO_FROM_CLIENT")) {
                            val address = packet.socketAddress
                            val nonce = WfasAuth.getToken(command, "cnonce").orEmpty()
                            if (key.isNotEmpty() && WfasAuth.getToken(command, "cproof") != WfasAuth.proof(key, 'C', nonce, snonce)) {
                                reply(address, "WFAS_AUTH_REQUIRED;snonce=$snonce;sproof=${WfasAuth.proof(key, 'S', nonce, snonce)}")
                            } else {
                                hellos.incrementAndGet(); client = address; cnonce = nonce
                                sendDir = if (key.isNotEmpty()) WfasCrypto.deriveUnicast(key, cnonce, snonce).second else null
                                reply(address, "HELLO_ACK;v=2" + if (key.isNotEmpty()) ";enc=1" else "")
                            }
                        } else if (command == "CLIENT_BYE" && packet.socketAddress == client) {
                            byes.incrementAndGet(); client = null
                        }
                    } catch (_: SocketTimeoutException) { }
                    val destination = client
                    val now = SystemClock.elapsedRealtime()
                    if (destination != null && now - last >= 4) {
                        val frames = rate * 4 / 1000
                        val pcm = if (silent.get()) ByteArray(0) else ByteArray(frames * channels * 2).apply {
                            for (at in 0 until size step 2) { this[at] = 0x20; this[at + 1] = 0x03 }
                        }
                        val encoded = sendDir?.let { WfasCrypto.encryptPacket(it, seq, position, silent.get(), pcm) }
                            ?: ByteArray(10 + pcm.size).apply {
                                this[0] = 0x57; this[1] = 0x46; this[2] = 2; this[3] = if (silent.get()) 1 else 0
                                this[4] = (seq shr 8).toByte(); this[5] = seq.toByte()
                                for (at in 0..3) this[6 + at] = (position shr (24 - at * 8)).toByte()
                                System.arraycopy(pcm, 0, this, 10, pcm.size)
                            }
                        socket.send(DatagramPacket(encoded, encoded.size, destination))
                        seq = (seq + 1) and 65535
                        if (!silent.get()) position = (position + frames) and 0xffffffffL
                        last = now
                    }
                }
            } catch (_: SocketException) { if (running.get()) throw IllegalStateException("Fixture socket failed") }
        }.apply { isDaemon = true; name = "wfas-loopback-fixture"; start() }
        private fun reply(address: SocketAddress, text: String) {
            val bytes = text.toByteArray(Charsets.US_ASCII)
            socket.send(DatagramPacket(bytes, bytes.size, address))
        }
        fun close() { running.set(false); socket.close(); thread.join(1000) }
    }
}
