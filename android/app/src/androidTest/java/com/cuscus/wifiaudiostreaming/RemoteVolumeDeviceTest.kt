package com.cuscus.wifiaudiostreaming

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cuscus.wifiaudiostreaming.data.SettingsDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Silent UDP fixture, without user capture, UI, role changes or saved settings. */
@RunWith(AndroidJUnit4::class)
class RemoteVolumeDeviceTest {
    @Test fun realTransportQueriesAndAcknowledgesSenderWithoutChangingLocalMaster() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loopback = InetAddress.getByName("127.0.0.1")
        val peer = DatagramSocket(0, loopback).apply { soTimeout = 100 }
        val alive = AtomicBoolean(true)
        val senderPercent = AtomicInteger(76)
        val senderWrites = AtomicInteger(0)
        val state = AtomicReference(RemoteVolumeState())
        val serverProblem = AtomicReference<Throwable?>()
        val ended = AtomicReference<SourceFailure?>()
        val localMaster = NetworkManager.clientVolume.value
        val localSenderGain = NetworkManager.serverVolume.value
        val server = Thread {
            var control: RemoteVolumeProtocol.Server? = null
            val packet = DatagramPacket(ByteArray(2048), 2048)
            while (alive.get()) {
                try {
                    packet.length = 2048
                    peer.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.US_ASCII)
                    val reply = if (text.startsWith("HELLO_FROM_CLIENT")) {
                        if (control == null) control = RemoteVolumeProtocol.offer(text, "", "", "",
                            object : RemoteVolumeProtocol.Volume {
                                override fun getPercent() = senderPercent.get()
                                override fun setPercent(value: Int) { senderPercent.set(value); senderWrites.incrementAndGet() }
                            })
                        "HELLO_ACK;v=2" + checkNotNull(control).ackSuffix()
                    } else control?.receive(text)
                    if (reply != null) {
                        val bytes = reply.toByteArray(Charsets.US_ASCII)
                        peer.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
                    }
                } catch (_: SocketTimeoutException) { }
                catch (closed: SocketException) { if (alive.get()) serverProblem.set(closed) }
                catch (failure: Throwable) { serverProblem.set(failure) }
            }
        }.apply { start() }
        val settings = SettingsDataStore(context).settingsFlow.first().copy(
            connectionSoundEnabled = false, sendClientMicrophone = false, networkInterface = "Auto")
        val source = ReceiverTransport(context, "remote-volume-fixture",
            ServerInfo("127.0.0.1", false, peer.localPort, "silent-fixture",
                audioFormat = StreamAudioFormat(48_000, 2, 16)), settings, true, 40,
            "", false, null, checkNotNull(DestinationNetwork.select(context, loopback, "Auto")),
            onStatus = { _, _ -> }, onKey = { }, onStats = { _, _, _, _ -> },
            onFinished = { ended.set(it) }, onMicrophoneReady = { }, onRemoteVolume = { state.set(it) })
        val job = launch(Dispatchers.IO) { source.run() }
        suspend fun waitFor(predicate: () -> Boolean) {
            withTimeout(7_000) { while (!predicate()) delay(25) }
            serverProblem.get()?.let { throw AssertionError("UDP fixture failed", it) }
            ended.get()?.let { throw AssertionError("Transport ended: ${it.reason}") }
        }
        try {
            waitFor { state.get().percent == 76 && !state.get().pending }
            assertEquals(RemoteVolumeSupport.SUPPORTED, state.get().support)
            assertTrue(source.setRemoteVolume(20))
            waitFor { state.get().percent == 20 && !state.get().pending }
            assertEquals(20, senderPercent.get())
            assertEquals(1, senderWrites.get())
            assertEquals(localMaster, NetworkManager.clientVolume.value, 0f)
            assertEquals(localSenderGain, NetworkManager.serverVolume.value, 0f)
            assertNull(state.get().error)
        } finally {
            source.stop(); job.cancelAndJoin()
            alive.set(false); peer.close(); server.join(1_000)
        }
    }
}
