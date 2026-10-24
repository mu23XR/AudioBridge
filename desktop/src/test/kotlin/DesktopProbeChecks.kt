import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Exercise the production probes over loopback; no remote host or sound device is used. */
internal object DesktopProbeChecks {
    fun runChecks() = runBlocking {
        check(!NetworkHandler_v1.probeIsMulticast("127.0.0.1", 9090))
        check(NetworkHandler_v1.probeIsMulticast("239.255.44.44", 9090))
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 3000
            val foreignSent = CountDownLatch(1)
            val allowSelectedReply = CountDownLatch(1)
            val responder = thread(isDaemon = true, name = "probe-fake-peer") {
                val request = DatagramPacket(ByteArray(128), 128)
                server.receive(request)
                check(String(request.data, 0, request.length, Charsets.UTF_8) == "MODE_PROBE")
                DatagramSocket(0, InetAddress.getLoopbackAddress()).use { foreign ->
                    val reply = "UNICAST".toByteArray()
                    foreign.send(DatagramPacket(reply, reply.size, request.address, request.port))
                }
                foreignSent.countDown()
                check(allowSelectedReply.await(2, TimeUnit.SECONDS))
                val reply = "WFAS_BUSY".toByteArray()
                server.send(DatagramPacket(reply, reply.size, request.address, request.port))
            }
            val probe = async(Dispatchers.IO) { NetworkHandler_v1.pingServerUnicast("localhost", server.localPort) }
            try {
                check(withContext(Dispatchers.IO) { foreignSent.await(2, TimeUnit.SECONDS) })
                delay(80)
                check(!probe.isCompleted) { "A foreign probe reply was trusted" }
                allowSelectedReply.countDown()
                check(probe.await()) { "The selected busy peer was not reachable" }
            } finally {
                allowSelectedReply.countDown()
                probe.cancelAndJoin()
                withContext(Dispatchers.IO) { responder.join(2000) }
            }
        }
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { silent ->
            check(!NetworkHandler_v1.pingServerUnicast("127.0.0.1", silent.localPort)) {
                "A silent endpoint was declared reachable"
            }
            val pending = async(Dispatchers.IO) { NetworkHandler_v1.pingServerUnicast("127.0.0.1", silent.localPort) }
            delay(40)
            pending.cancelAndJoin()
            check(pending.isCancelled)
        }
        println("Production mode/reachability probes reject foreign replies and preserve timeout/cancellation")
    }
}
