package com.cuscus.wifiaudiostreaming

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cuscus.wifiaudiostreaming.data.SettingsDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Silent loopback transport; replaces only the cue output with a counter. */
@RunWith(AndroidJUnit4::class)
class ConnectionCueDeviceTest {
    @Test fun validatedSilenceAnnouncesOnceAndExplicitReconnectCanAnnounceAgain() = runBlocking {
        val policy = ConnectionCuePolicy()
        val cues = AtomicInteger()
        runSilentAttempt(policy, cues, true, 0, 1)
        runSilentAttempt(policy, cues, true, 1, 1) // Automatic transport replacement.
        policy.reset()
        runSilentAttempt(policy, cues, true, 1, 2)
        policy.reset()
        runSilentAttempt(policy, cues, false, 2, 2)
        assertEquals(2, cues.get())
    }

    private suspend fun runSilentAttempt(
        policy: ConnectionCuePolicy, cues: AtomicInteger, enabled: Boolean,
        before: Int, after: Int
    ) = coroutineScope {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loopback = InetAddress.getByName("127.0.0.1")
        val path = requireNotNull(DestinationNetwork.select(context, loopback, "Auto"))
        val settings = SettingsDataStore(context).settingsFlow.first().copy(
            connectionSoundEnabled = enabled, sendClientMicrophone = false, networkInterface = "Auto")
        val peer = DatagramSocket(0, loopback).apply { soTimeout = 4_000 }
        val foreign = DatagramSocket(0, loopback)
        val connected = AtomicBoolean()
        val ended = AtomicReference<SourceFailure?>()
        val id = "cue-${UUID.randomUUID()}"
        val source = ReceiverTransport(context, id,
            ServerInfo("127.0.0.1", false, peer.localPort, "silent-cue-fixture",
                audioFormat = StreamAudioFormat(48_000, 2, 16)),
            settings, true, 40, "", false, null, path,
            onStatus = { _, value -> if (value) connected.set(true) },
            onKey = { throw AssertionError("OFF fixture must not request a key") },
            onStats = { _, _, _, _ -> }, onFinished = { ended.set(it) },
            onMicrophoneReady = { }, onRemoteVolume = { },
            connectionCue = policy.beginAttempt(enabled), onConnectionCue = { cues.incrementAndGet() })
        val job = launch(Dispatchers.IO) { source.run() }
        try {
            withContext(Dispatchers.IO) {
                val packet = DatagramPacket(ByteArray(2048), 2048)
                peer.receive(packet)
                assertTrue(String(packet.data, 0, packet.length).startsWith("HELLO_FROM_CLIENT"))
                val endpoint = packet.socketAddress
                fun send(socket: DatagramSocket, bytes: ByteArray) =
                    socket.send(DatagramPacket(bytes, bytes.size, endpoint))
                fun control(text: String) = send(peer, text.toByteArray(Charsets.US_ASCII))
                fun barrier() {
                    control("PING")
                    do {
                        packet.length = packet.data.size
                        peer.receive(packet)
                    } while (String(packet.data, 0, packet.length, Charsets.US_ASCII) != "PONG")
                    ended.get()?.let { throw AssertionError("Transport ended: ${it.reason}") }
                    assertTrue(job.isActive)
                }

                control("HELLO_ACK;v=2")
                // All are processed before the selected socket's PING response.
                // None is a valid source frame or may consume cue eligibility.
                send(foreign, silence(30_000))
                send(peer, silence(99).copyOf(11).apply { this[3] = 0 }) // Partial stereo frame.
                send(peer, silence(99).apply { this[3] = 0 }) // Empty non-silent payload.
                barrier()
                assertEquals("ACK/control/rejected traffic announced a connection", before, cues.get())
                assertFalse("Rejected traffic marked transport connected", connected.get())

                send(peer, silence(100))
                barrier()
                assertTrue("Validated silent source did not establish the connection", connected.get())
                assertEquals("Silent source cue eligibility was incorrect", after, cues.get())

                send(peer, silence(100)) // Duplicate.
                send(peer, silence(98)) // Late silence must not change logical state.
                send(peer, silence(101).copyOf(14).apply { this[3] = 0 }) // Zero PCM also counts as silence.
                barrier()
                assertEquals("Additional frames repeated the cue", after, cues.get())
                assertFalse("Silent cue fixture must not allocate an output", ReceiverMixer.hasOutput(id))
            }
        } finally {
            source.stop()
            withContext(NonCancellable) { withTimeout(5_000) { job.cancelAndJoin() } }
            peer.close()
            foreign.close()
        }
    }

    private fun silence(sequence: Int) = ByteArray(10).apply {
        this[0] = 0x57; this[1] = 0x46; this[2] = 2; this[3] = 1
        this[4] = (sequence shr 8).toByte(); this[5] = sequence.toByte()
    }
}
