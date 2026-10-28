package com.cuscus.wifiaudiostreaming

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cuscus.wifiaudiostreaming.data.SettingsDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Opt-in host UDP fixture, exercising a managed non-loopback Network.bindSocket.
 * Pass fixtureIp and fixturePort as instrumentation arguments. The host replies
 * HELLO_ACK;v=2 and sends header-only WFAS v2 silence every 200 ms with sequential
 * sequence numbers. Another socket sends BYE, PING and zero-PCM packets carrying
 * sequence 30000 from the wrong source port. No user audio/settings/role changes.
 */
@RunWith(AndroidJUnit4::class)
class DestinationPathDeviceTest {
    @Test fun legacyAckAndSilenceStayHealthyWithWrongPortTraffic() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val fixtureIp = arguments.getString("fixtureIp")
        val portText = arguments.getString("fixturePort")
        assumeTrue("Opt-in host fixture requires fixtureIp and fixturePort",
            !fixtureIp.isNullOrBlank() && !portText.isNullOrBlank())
        val port = requireNotNull(portText?.toIntOrNull()?.takeIf { it in 1..65535 }) {
            "fixturePort must be 1..65535"
        }
        val address = InetAddress.getByName(fixtureIp)
        assertFalse("Use an actual network path rather than loopback", address.isLoopbackAddress)
        assertFalse("Fixture must be a specific unicast address", address.isAnyLocalAddress || address.isMulticastAddress)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = requireNotNull(DestinationNetwork.select(context, address, "Auto")) {
            "No route to the supplied host fixture"
        }
        assertNotNull("Fixture must exercise ConnectivityManager Network.bindSocket", path.network)

        val settings = SettingsDataStore(context).settingsFlow.first().copy(
            connectionSoundEnabled = false, sendClientMicrophone = false, networkInterface = "Auto")
        val savedMaster = NetworkManager.clientVolume.value
        val savedSender = NetworkManager.serverVolume.value
        val metrics = AtomicReference(LinkMetrics.Snapshot())
        val remote = AtomicReference(RemoteVolumeState())
        val ended = AtomicReference<SourceFailure?>()
        val streaming = AtomicBoolean(false)
        val sourceId = "destination-path-${UUID.randomUUID()}"
        val source = ReceiverTransport(context, sourceId,
            ServerInfo(address.hostAddress!!, false, port, "destination-path-fixture",
                audioFormat = StreamAudioFormat(48_000, 2, 16)),
            settings, true, 40, "", false, null, path,
            onStatus = { _, connected -> if (connected) streaming.set(true) },
            onKey = { throw AssertionError("Legacy OFF fixture must not request a key") },
            onStats = { snapshot, _, _, _ -> metrics.set(snapshot) },
            onFinished = { ended.set(it) }, onMicrophoneReady = { },
            onRemoteVolume = { remote.set(it) })
        val job = launch(Dispatchers.IO) { source.run() }
        fun assertHealthy() {
            ended.get()?.let { throw AssertionError("Source unexpectedly ended: ${it.reason}") }
            assertTrue("Source task stopped before explicit cleanup", job.isActive)
        }
        try {
            withTimeout(15_000) {
                while (metrics.get().packets < 3) {
                    assertHealthy()
                    delay(25)
                }
            }
            assertTrue("Valid silence must establish the audio transport", streaming.get())
            assertEquals(RemoteVolumeSupport.UNSUPPORTED, remote.get().support)
            val before = metrics.get().packets
            val until = SystemClock.elapsedRealtime() + 2_000
            while (SystemClock.elapsedRealtime() < until) { assertHealthy(); delay(25) }
            assertHealthy()
            assertTrue("Valid packets must continue despite wrong-port BYE/PING", metrics.get().packets >= before + 3)
            // A wrongly accepted rogue sequence 30000 would cause a huge loss
            // jump or classify the following legitimate packets as reordered.
            assertTrue("Wrong-port PCM polluted source sequence history", metrics.get().lost < 50)
            assertEquals("Wrong-port PCM must not reorder the selected source", 0L, metrics.get().reordered)
            assertEquals(savedMaster, NetworkManager.clientVolume.value, 0f)
            assertEquals(savedSender, NetworkManager.serverVolume.value, 0f)
            assertEquals(RemoteVolumeSupport.UNSUPPORTED, remote.get().support)
            assertFalse("Old peer has no remote volume setter", source.setRemoteVolume(20))
        } finally {
            source.stop()
            withContext(NonCancellable) {
                withTimeout(5_000) { job.cancelAndJoin() }
            }
        }
        assertTrue("Explicit stop must complete transport cleanup", job.isCompleted)
        assertFalse("Explicit stop must release its mixer output", ReceiverMixer.hasOutput(sourceId))
    }
}
