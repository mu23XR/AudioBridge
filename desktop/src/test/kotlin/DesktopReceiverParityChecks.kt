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

/** Production session/handshake paths with loopback peers and no system audio device. */
internal object DesktopReceiverParityChecks {
    private val defaults = AudioSettings_V1(48_000f, 16, 2, 1920, 40, adaptiveLatency = true)

    fun runChecks() = runBlocking {
        reconnectPreservesPerSourceLatency()
        correctedKeyGetsANewReplyBudget()
        keyInteractionDoesNotExtendTheAbsoluteHandshakeCap()
        wrongMulticastKeyCanBeCorrected()
        changedMulticastKeyRequiresNewPairing()
        println("Receiver parity: independent latency, corrected-key timing, absolute cap and bounded multicast key recovery passed")
    }

    @JvmStatic fun main(args: Array<String>) {
        if (args.isEmpty()) runChecks()
        else runBlocking {
            when (args.single()) {
                "latency" -> reconnectPreservesPerSourceLatency()
                "handshake" -> { correctedKeyGetsANewReplyBudget(); keyInteractionDoesNotExtendTheAbsoluteHandshakeCap() }
                "multicast" -> { wrongMulticastKeyCanBeCorrected(); changedMulticastKeyRequiresNewPairing() }
                else -> error("Unknown receiver parity check: ${args.single()}")
            }
        }
    }

    private suspend fun reconnectPreservesPerSourceLatency() {
        DesktopReceiverSessions.stopAllAndJoin()
        try {
            Peer().use { oldPeer -> Peer().use { newPeer ->
                val request = request(oldPeer.port)
                val id = DesktopReceiverSessions.restore(request, gain = .37f, muted = true,
                    adaptive = false, manualMs = 133)
                check(DesktopReceiverSessions.connect(request) == id)
                await("Reconnected source did not receive PCM") { source(id).connected }
                val existing = source(id)
                check(!existing.adaptive && existing.manualLatencyMs == 133) {
                    "Reconnect replaced source latency with defaults: $existing"
                }
                check(existing.gain == .37f && existing.muted) { "Reconnect changed unrelated gain/mute" }
                val fresh = DesktopReceiverSessions.connect(request(newPeer.port))
                await("New source did not receive PCM") { source(fresh).connected }
                check(source(fresh).adaptive && source(fresh).manualLatencyMs == 40) {
                    "New source did not inherit current defaults"
                }
                check(source(id).manualLatencyMs == 133 && !source(id).adaptive)
            } }
        } finally { DesktopReceiverSessions.stopAllAndJoin() }
    }

    private suspend fun correctedKeyGetsANewReplyBudget() = coroutineScope {
        Peer(requireCorrectedKey = true).use { peer ->
            val clock = AtomicLong()
            val saved = AtomicReference<String?>()
            val prompts = AtomicInteger()
            val failure = AtomicReference<Throwable?>()
            val state = AtomicReference("not started")
            val receiver = receiver(request(peer.port).copy(requestKey = { wrong ->
                check(wrong)
                prompts.incrementAndGet()
                clock.addAndGet(7000) // Human input exceeds the six-second network watchdog.
                Peer.KEY
            }), clock = clock, onKey = saved::set, onState = state::set)
            val job = launch(Dispatchers.IO) { failure.set(runCatching { receiver.run() }.exceptionOrNull()) }
            try {
                withTimeoutOrNull(10_000) { while (saved.get() == null && job.isActive) delay(5) }
                check(saved.get() == Peer.KEY && prompts.get() == 1 && failure.get() == null) {
                    "Corrected key failed to negotiate: state=${state.get()} hellos=${peer.hellos.get()} prompts=${prompts.get()} failure=${failure.get()}"
                }
            } finally { receiver.stop(); job.cancelAndJoin() }
        }
    }

    private suspend fun keyInteractionDoesNotExtendTheAbsoluteHandshakeCap() = coroutineScope {
        Peer(requireCorrectedKey = true).use { peer ->
            val clock = AtomicLong()
            val saved = AtomicReference<String?>()
            val failure = AtomicReference<Throwable?>()
            val receiver = receiver(request(peer.port).copy(requestKey = {
                clock.addAndGet(120_001)
                Peer.KEY
            }), clock = clock, onKey = saved::set)
            val job = launch(Dispatchers.IO) { failure.set(runCatching { receiver.run() }.exceptionOrNull()) }
            try {
                withTimeout(3000) { job.join() }
                val problem = failure.get()
                check(problem is DesktopSourceFailure && problem.key == "status_handshake_failed") {
                    "Key correction extended the absolute handshake cap: $problem"
                }
                check(saved.get() == null)
            } finally { receiver.stop(); job.cancelAndJoin() }
        }
    }

    private suspend fun wrongMulticastKeyCanBeCorrected() {
        val clock = AtomicLong()
        val liveness = DesktopReceiverLiveness(clock::get)
        val prompts = AtomicInteger()
        val saved = AtomicReference<String?>()
        val epochs = AtomicInteger()
        val request = request(9090).copy(peer = request(9090).peer.copy(isMulticast = true),
            key = "wrong-saved-key", expectedEpoch = 7, requestKey = { wrong ->
                check(wrong)
                prompts.incrementAndGet()
                clock.addAndGet(45_000)
                Peer.KEY
            })
        val receiver = receiver(request, clock, saved::set, liveness) { _, epoch ->
            check(epoch == 7L); epochs.incrementAndGet()
        }
        val beacon = WfasCrypto.buildMcastBeacon(Peer.KEY, 7, 0, ByteArray(WfasCrypto.SALT_BYTES) { 3 })
        repeat(5) { check(!receiver.groupBeacon(beacon)) }
        check(prompts.get() == 0 && epochs.get() == 0 && saved.get() == null)
        check(!receiver.groupBeacon(beacon))
        check(prompts.get() == 1) { "Wrong saved multicast key never offered correction" }
        check(!liveness.hasExpired()) { "User key input consumed the new stream's activity budget" }
        check(receiver.groupBeacon(beacon)) { "Corrected multicast key did not authenticate the next beacon" }
        check(saved.get() == Peer.KEY && epochs.get() == 1)
        check(receiver.groupBeacon(beacon) && epochs.get() == 1) { "Repeated beacon reset the group session" }
    }

    private suspend fun changedMulticastKeyRequiresNewPairing() {
        val prompts = AtomicInteger()
        val request = request(9090).copy(peer = request(9090).peer.copy(isMulticast = true),
            key = Peer.KEY, expectedEpoch = 7, requestKey = { prompts.incrementAndGet(); "unexpected" })
        val receiver = receiver(request)
        val salt = ByteArray(WfasCrypto.SALT_BYTES) { 5 }
        check(receiver.groupBeacon(WfasCrypto.buildMcastBeacon(Peer.KEY, 7, 0, salt)))
        val changed = WfasCrypto.buildMcastBeacon("changed-group-key", 8, 0, salt)
        repeat(5) { check(!receiver.groupBeacon(changed)) }
        val failure = runCatching { receiver.groupBeacon(changed) }.exceptionOrNull()
        check(failure is DesktopSourceFailure && failure.key == "status_group_rekeyed" && !failure.retryable) {
            "Changed multicast group key stayed in an automatic retry loop: $failure"
        }
        check(prompts.get() == 0) { "Live group rekey silently replaced the established credential" }
    }

    private fun source(id: String) = DesktopReceiverSessions.sources.value.first { it.id == id }

    private fun request(port: Int) = DesktopReceiverRequest(
        peer = ServerInfo("127.0.0.1", false, port, serverAudioSettings = defaults), audio = defaults,
        openOutput = { _, _ -> fakeLine() }, connectionSoundEnabled = false, expectedEpoch = 7)

    private fun receiver(request: DesktopReceiverRequest, clock: AtomicLong = AtomicLong(),
                         onKey: (String) -> Unit = {},
                         liveness: DesktopReceiverLiveness = DesktopReceiverLiveness(clock::get),
                         onState: (String) -> Unit = {},
                         storeEpoch: (String, Long) -> Unit = { _, _ -> }) =
        DesktopWfasReceiver(request, ConnectionCuePolicy().beginAttempt(false), { 1f }, { false },
            onKey, { state, _, _ -> onState(state) }, { _, _, _, _, _ -> }, liveness = liveness,
            clockMs = clock::get, storeGroupEpoch = storeEpoch)

    private suspend fun await(message: String, condition: () -> Boolean) {
        withTimeoutOrNull(3000) { while (!condition()) delay(5) }
        check(condition()) { message }
    }

    private class Peer(private val requireCorrectedKey: Boolean = false) : AutoCloseable {
        private val socket = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).apply { soTimeout = 100 }
        val port get() = socket.localPort
        val hellos = AtomicInteger()
        @Volatile private var running = true
        private var problem: Throwable? = null
        private val thread = Thread({
            try {
                var rejected = false
                val serverNonce = WfasAuth.nonceHex()
                val packet = DatagramPacket(ByteArray(4096), 4096)
                while (running) {
                    try { packet.length = packet.data.size; socket.receive(packet) }
                    catch (_: SocketTimeoutException) { continue }
                    val text = String(packet.data, 0, packet.length, Charsets.US_ASCII)
                    if (!text.startsWith("HELLO_FROM_CLIENT;")) continue
                    hellos.incrementAndGet()
                    val remote = packet.socketAddress
                    fun send(text: String) {
                        val bytes = text.toByteArray(Charsets.US_ASCII)
                        socket.send(DatagramPacket(bytes, bytes.size, remote))
                    }
                    if (requireCorrectedKey && !rejected) {
                        rejected = true; send("WFAS_UNAUTHORIZED"); continue
                    }
                    if (requireCorrectedKey) {
                        val clientNonce = WfasAuth.getToken(text, "cnonce").orEmpty()
                        val proof = WfasAuth.getToken(text, "cproof")
                        if (proof == null) {
                            send("WFAS_AUTH_REQUIRED;snonce=$serverNonce;sproof=${WfasAuth.proof(KEY, 'S', clientNonce, serverNonce)}")
                            continue
                        }
                        check(proof == WfasAuth.proof(KEY, 'C', clientNonce, serverNonce))
                    }
                    send("HELLO_ACK;v=2")
                    val pcm = ByteBuffer.allocate(1930).apply {
                        put(0x57); put(0x46); put(2); put(0); putShort(1); putInt(480)
                        repeat(960) { put(0x20); put(0x03) }
                    }.array()
                    socket.send(DatagramPacket(pcm, pcm.size, remote))
                }
            } catch (failure: Throwable) { if (running) problem = failure }
        }, "receiver-parity-peer").apply { isDaemon = true; start() }

        override fun close() {
            running = false; socket.close(); thread.join(1000)
            check(!thread.isAlive && problem == null) { "Receiver parity peer failed: $problem" }
        }

        companion object { const val KEY = "parity-fixture-key" }
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
