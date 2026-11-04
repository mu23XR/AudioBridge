import kotlinx.coroutines.*
import java.lang.reflect.Proxy
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.SourceDataLine

/** Production receiver/coordinator against persist=false peers, without an OS audio device. */
internal object DesktopReceiverLivenessChecks {
    private val audio = AudioSettings_V1(48_000f, 16, 2, 1920, 20, adaptiveLatency = false)

    fun runChecks() = runBlocking {
        DesktopReceiverSessions.stopAllAndJoin()
        try {
            slowRouteProbeDoesNotPauseReceiving()
            validPcmAndSilenceSurviveDelayedPing()
            invalidTrafficDoesNotKeepADeadSourceAlive()
            restartsDoNotStopAShizukuStyleSender()
            outputFailureRetriesWithoutStoppingTheSender()
            strictPeerMustReleaseItsOldHeartbeatBeforeRetry()
            explicitOffNotifiesTheSenderOnce()
        } finally { DesktopReceiverSessions.stopAllAndJoin() }
        println("Receiver liveness: valid PCM/silence/PONG, invalid expiry, restart versus user stop and strict-peer takeover passed")
    }

    @JvmStatic fun main(args: Array<String>) = runBlocking { slowRouteProbeDoesNotPauseReceiving() }

    private suspend fun slowRouteProbeDoesNotPauseReceiving() = coroutineScope {
        Sender(automaticAudio = true, automaticPing = true).use { sender ->
            val probing = CompletableDeferred<Unit>()
            val changed = java.util.concurrent.atomic.AtomicBoolean()
            val frames = AtomicInteger()
            val failure = AtomicReference<Throwable?>()
            val state = AtomicReference("not started")
            val path = DesktopDestinationPath.select(InetAddress.getLoopbackAddress(), "Auto")!!.fingerprint
            val receiver = DesktopWfasReceiver(request(sender) { fakeLine() }.copy(onFrame = { frames.incrementAndGet() }),
                ConnectionCuePolicy().beginAttempt(false), { 1f }, { false }, {}, { key, _, _ -> state.set(key) },
                { _, _, _, _, _ -> }, routeProbe = { _, _ ->
                    probing.complete(Unit)
                    Thread.sleep(350) // A Windows adapter query, not a network receive.
                    if (changed.get()) null else path
                }, routeCheckIntervalMs = 50)
            val job = launch(Dispatchers.IO) { failure.set(runCatching { receiver.run() }.exceptionOrNull()) }
            try {
                withTimeoutOrNull(10_000) { while (!(
                    probing.isCompleted || failure.get() != null || !job.isActive
                )) delay(5) }
                check(failure.get() == null) { "Receiver failed before route probing: ${failure.get()}" }
                check(probing.isCompleted) {
                    "Route probe not reached: state=${state.get()} handshakes=${sender.handshakes.get()} frames=${frames.get()} active=${job.isActive}"
                }
                val before = frames.get()
                val pongs = sender.pongs.get()
                delay(200)
                check(frames.get() >= before + 5) { "Slow route probe paused PCM reception: ${frames.get() - before}frames" }
                check(sender.pongs.get() > pongs) { "Slow route probe paused PONG responses" }
                changed.set(true)
                await("Changed route was no longer detected") { !job.isActive }
                val problem = failure.get()
                check(problem is DesktopSourceFailure && problem.key == "status_server_disconnected" && problem.retryable)
                check(sender.byes.get() == 0 && !sender.stopped) { "Route change stopped the remote sender" }
                println("Slow route probe: ${frames.get() - before} PCM packets and ${sender.pongs.get() - pongs} PONG responses continued; changed route still retries")
            } finally {
                receiver.stop(); job.cancelAndJoin()
            }
            check(receiver.isCleanupComplete()) { "Route monitor escaped receiver cleanup ownership" }
        }
    }

    private suspend fun validPcmAndSilenceSurviveDelayedPing() = coroutineScope {
        Sender().use { sender ->
            val clock = AtomicLong()
            val liveness = DesktopReceiverLiveness(clock::get)
            val frames = AtomicInteger()
            val running = launchReceiver(sender, liveness) { frames.incrementAndGet() }
            try {
                await("Receiver did not negotiate initial PCM") { sender.client != null }
                sender.send(pcm(1))
                await("Initial PCM did not reach the receiver") { frames.get() > 0 }
                for (sequence in 2..7) {
                    val at = clock.addAndGet(20_000)
                    sender.send(if (sequence <= 3) pcm(sequence) else silence(sequence))
                    await("Valid PCM/silence did not refresh the source") { liveness.lastActivityMs == at }
                    check(running.job.isActive && running.failure.get() == null)
                }
                check(sender.pongs.get() == 0) { "Fixture accidentally supplied PING during the no-PING interval" }
                val at = clock.addAndGet(20_000)
                sender.send("PING".toByteArray())
                await("Selected peer PING did not receive PONG") { sender.pongs.get() == 1 && liveness.lastActivityMs == at }
                check(running.job.isActive && sender.handshakes.get() == 1)
            } finally { running.stop() }
            check(sender.byes.get() == 0) { "Transport cancellation was sent as explicit CLIENT_BYE" }
        }
    }

    private suspend fun invalidTrafficDoesNotKeepADeadSourceAlive() = coroutineScope {
        Sender().use { sender ->
            val clock = AtomicLong()
            val liveness = DesktopReceiverLiveness(clock::get)
            val frames = AtomicInteger()
            val running = launchReceiver(sender, liveness) { frames.incrementAndGet() }
            try {
                await("Receiver never negotiated") { sender.client != null }
                sender.send(pcm(1))
                await("First packet was not admitted") { frames.get() == 1 }
                clock.set(20_000)
                DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).use { foreign ->
                    repeat(8) {
                        sender.sendFrom(foreign, pcm(2 + it))
                        sender.sendFrom(foreign, "PING".toByteArray())
                        // Valid magic/version, but incomplete stereo frame; and
                        // an empty non-silence header from the selected endpoint.
                        sender.send(silence(100 + it).copyOf(13).also { bytes -> bytes[3] = 0 })
                        sender.send(silence(200 + it).also { bytes -> bytes[3] = 0 })
                        delay(20)
                    }
                    check(liveness.lastActivityMs == 0L && running.job.isActive)
                    clock.set(30_001)
                    sender.sendFrom(foreign, "PING".toByteArray())
                    await("Unselected/malformed traffic prevented real source expiry") { !running.job.isActive }
                }
                val failure = running.failure.get()
                check(failure is DesktopSourceFailure && failure.key == "status_server_disconnected")
                check(sender.byes.get() == 0 && !sender.stopped) { "Activity timeout stopped the sending program" }
            } finally { running.stop() }
        }
    }

    private suspend fun restartsDoNotStopAShizukuStyleSender() {
        Sender(automaticAudio = true, automaticPing = true).use { sender ->
            val request = request(sender) { fakeLine() }
            val id = DesktopReceiverSessions.connect(request)
            try {
                awaitConnected(id)
                await("Receiver never acknowledged sender liveness") { sender.pongs.get() > 0 }
                DesktopReceiverSessions.setLatency(id, false, 73)
                await("Latency update could not retake the same-IP sender") { sender.handshakes.get() >= 2 && connected(id) }
                DesktopReceiverSessions.retry(id)
                await("Explicit retry stopped the non-persistent sender") { sender.handshakes.get() >= 3 && connected(id) }
                DesktopReceiverSessions.setRemoteVolumeEnabled(true)
                await("Capability renegotiation stopped the non-persistent sender") {
                    sender.handshakes.get() >= 4 && connected(id) && sender.lastHello.contains(";rvol=1;")
                }
                check(DesktopReceiverSessions.connect(request) == id)
                await("Replacing a same-source request stopped its sender") { sender.handshakes.get() >= 5 && connected(id) }
                check(sender.byes.get() == 0 && !sender.stopped)
                DesktopReceiverSessions.remove(id)
                await("Explicit source removal did not send CLIENT_BYE") { sender.stopped }
                check(sender.byes.get() == 1)
            } finally { DesktopReceiverSessions.stopAllAndJoin() }
        }
    }

    private suspend fun outputFailureRetriesWithoutStoppingTheSender() {
        Sender(automaticAudio = true).use { sender ->
            val opens = AtomicInteger()
            val id = DesktopReceiverSessions.connect(request(sender) {
                if (opens.incrementAndGet() == 1) throw PlaybackOutputFailure("injected first output failure")
                fakeLine()
            })
            try {
                await("Output failure could not recover without stopping capture", 5_000) {
                    sender.handshakes.get() >= 2 && connected(id)
                }
                check(opens.get() == 2 && sender.byes.get() == 0 && !sender.stopped)
                DesktopReceiverSessions.remove(id)
                await("Removal after recovery was not explicit") { sender.byes.get() == 1 }
            } finally { DesktopReceiverSessions.stopAllAndJoin() }
        }
    }

    private suspend fun strictPeerMustReleaseItsOldHeartbeatBeforeRetry() {
        val senderClock = AtomicLong()
        Sender(automaticAudio = true, automaticPing = true, strictEndpoint = true, clockMs = senderClock::get).use { sender ->
            val id = DesktopReceiverSessions.connect(request(sender) { fakeLine() })
            try {
                awaitConnected(id)
                await("Strict peer never saw a negotiated PONG") { sender.pongs.get() > 0 }
                DesktopReceiverSessions.retry(id)
                await("Strict sender did not reject the changed local endpoint") { sender.busies.get() > 0 }
                check(sender.handshakes.get() == 1 && sender.byes.get() == 0 && !sender.stopped)
                // Android's ordinary capture server retains the exact old peer
                // until its PONG deadline, unlike Shizuku's same-IP handoff.
                senderClock.set(8_001)
                await("Strict peer did not release the expired old session") { sender.heartbeatReleases.get() == 1 }
                await("Receiver did not back off and reconnect after BUSY", 5_000) { sender.handshakes.get() >= 2 && connected(id) }
                check(sender.byes.get() == 0 && !sender.stopped)
                DesktopReceiverSessions.remove(id)
                await("Strict peer did not receive explicit removal") { sender.byes.get() == 1 }
            } finally { DesktopReceiverSessions.stopAllAndJoin() }
        }
    }

    private suspend fun explicitOffNotifiesTheSenderOnce() {
        Sender(automaticAudio = true).use { sender ->
            val id = DesktopReceiverSessions.connect(request(sender) { fakeLine() })
            try {
                awaitConnected(id)
                DesktopReceiverSessions.pauseAllAndJoin()
                await("Explicit OFF did not stop the non-persistent sender") { sender.stopped }
                val state = DesktopReceiverSessions.sources.value.single()
                check(state.id == id && state.paused && !state.connected && sender.byes.get() == 1)
                DesktopReceiverSessions.stopAllAndJoin()
                check(sender.byes.get() == 1) { "Final cleanup sent a second stop command" }
            } finally { DesktopReceiverSessions.stopAllAndJoin() }
        }
    }

    private fun connected(id: String) = DesktopReceiverSessions.sources.value.any { it.id == id && it.connected }
    private suspend fun awaitConnected(id: String) = await("Source did not stream") { connected(id) }
    private fun request(sender: Sender, open: () -> SourceDataLine) = DesktopReceiverRequest(
        peer = ServerInfo("127.0.0.1", false, sender.port, serverAudioSettings = audio), audio = audio,
        openOutput = { _, _ -> open() }, expectedEpoch = 0, connectionSoundEnabled = false)

    private class Running(val receiver: DesktopWfasReceiver, val job: Job, val failure: AtomicReference<Throwable?>) {
        suspend fun stop() { receiver.stop(); job.cancelAndJoin() }
    }

    private fun CoroutineScope.launchReceiver(sender: Sender, liveness: DesktopReceiverLiveness,
                                              onFrame: () -> Unit): Running {
        val failure = AtomicReference<Throwable?>()
        val receiver = DesktopWfasReceiver(request(sender) { fakeLine() }.copy(onFrame = { onFrame() }),
            ConnectionCuePolicy().beginAttempt(false), { 1f }, { false }, {}, { _, _, _ -> },
            { _, _, _, _, _ -> }, liveness)
        val job = launch(Dispatchers.IO) { failure.set(runCatching { receiver.run() }.exceptionOrNull()) }
        return Running(receiver, job, failure)
    }

    private suspend fun await(message: String, timeoutMs: Long = 3_000, condition: () -> Boolean) {
        withTimeoutOrNull(timeoutMs) { while (!condition()) delay(5) }
        check(condition()) { message }
    }

    private fun pcm(sequence: Int) = ByteBuffer.allocate(1930).apply {
        put(0x57); put(0x46); put(2); put(0); putShort(sequence.toShort()); putInt(sequence * 480)
        repeat(960) { put(0x20); put(0x03) }
    }.array()

    private fun silence(sequence: Int) = ByteBuffer.allocate(10).apply {
        put(0x57); put(0x46); put(2); put(1); putShort(sequence.toShort()); putInt(sequence * 480)
    }.array()

    /** Shizuku accepts same-IP handoff; ordinary capture waits for the old exact peer's PONG expiry. */
    private class Sender(private val automaticAudio: Boolean = false, private val automaticPing: Boolean = false,
                         private val strictEndpoint: Boolean = false,
                         private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 }) : AutoCloseable {
        private val socket = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).apply { soTimeout = 10 }
        val port get() = socket.localPort
        val handshakes = AtomicInteger()
        val byes = AtomicInteger()
        val pongs = AtomicInteger()
        val busies = AtomicInteger()
        val heartbeatReleases = AtomicInteger()
        @Volatile var client: InetSocketAddress? = null
            private set
        @Volatile var lastHello = ""
            private set
        @Volatile var stopped = false
            private set
        @Volatile private var running = true
        private var problem: Throwable? = null
        private val thread = Thread({
            val packet = DatagramPacket(ByteArray(4096), 4096)
            var lastPong = clockMs()
            var pongCapable = false
            var lastAudio = 0L
            var lastPing = 0L
            var sequence = 0
            try {
                while (running) {
                    if (client != null && pongCapable && clockMs() - lastPong > 8_000) {
                        client = null; pongCapable = false; heartbeatReleases.incrementAndGet()
                    }
                    try {
                        packet.length = packet.data.size
                        socket.receive(packet)
                        val remote = packet.socketAddress as InetSocketAddress
                        val text = String(packet.data, 0, packet.length, Charsets.US_ASCII)
                        when {
                            !stopped && text.startsWith("HELLO_FROM_CLIENT;") -> {
                                val old = client
                                if (old != null && old != remote && (strictEndpoint || old.address != remote.address)) {
                                    busies.incrementAndGet(); sendTo(remote, "WFAS_BUSY".toByteArray())
                                } else {
                                    client = remote; lastHello = text; lastPong = clockMs(); pongCapable = false
                                    handshakes.incrementAndGet(); sequence = 0
                                    send("HELLO_ACK;v=2".toByteArray())
                                }
                            }
                            remote == client && text == "PONG" -> {
                                lastPong = clockMs(); pongCapable = true; pongs.incrementAndGet()
                            }
                            remote == client && text == "CLIENT_BYE" -> {
                                byes.incrementAndGet(); stopped = true; client = null
                            }
                        }
                    } catch (_: SocketTimeoutException) { }
                    val now = System.nanoTime()
                    if (!stopped && client != null) {
                        if (automaticAudio && now - lastAudio >= 10_000_000) {
                            send(pcm(sequence++ and 65535)); lastAudio = now
                        }
                        // Fast fixture cadence; the expiry uses its independent
                        // sender clock and still requires the negotiated PONG.
                        if (automaticPing && now - lastPing >= 50_000_000) {
                            send("PING".toByteArray()); lastPing = now
                        }
                    }
                }
            } catch (failure: Throwable) { if (running) problem = failure }
        }, "persist-false-liveness-peer").apply { isDaemon = true; start() }

        fun send(bytes: ByteArray) = sendTo(checkNotNull(client), bytes)
        fun sendFrom(other: DatagramSocket, bytes: ByteArray) {
            val remote = checkNotNull(client)
            other.send(DatagramPacket(bytes, bytes.size, remote))
        }
        private fun sendTo(remote: InetSocketAddress, bytes: ByteArray) = socket.send(DatagramPacket(bytes, bytes.size, remote))
        override fun close() {
            running = false; socket.close(); thread.join(1_000)
            check(!thread.isAlive && problem == null) { "Loopback sender failed: $problem" }
        }
    }

    private fun fakeLine(): SourceDataLine {
        val frames = AtomicLong()
        return Proxy.newProxyInstance(SourceDataLine::class.java.classLoader, arrayOf(SourceDataLine::class.java)) { proxy, method, args ->
            when (method.name) {
                "getBufferSize", "available" -> 96_000
                "getLongFramePosition" -> frames.get()
                "write" -> (args!![2] as Int).also { frames.addAndGet(it.toLong() / 4) }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as SourceDataLine
    }
}
