import kotlinx.coroutines.*
import java.lang.reflect.Proxy
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.SourceDataLine

/** Production receiver + loopback UDP; no OS sound device is opened. */
internal object DesktopVpnEndpointChecks {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val audio = AudioSettings_V1(48_000f, 16, 2, 1920, 20, adaptiveLatency = false)

    fun runChecks() = runBlocking {
        endpointBindsOnlyOneHandshakePort()
        rewrittenTransport(false)
        rewrittenTransport(true)
        println("VPN endpoint: one handshake adoption, frozen peer identity and real plain/encrypted transport passed")
    }

    private fun endpointBindsOnlyOneHandshakePort() {
        val remote = InetSocketAddress(loopback, 9090)
        fun packet(text: String, port: Int, host: String = "127.0.0.1") = text.toByteArray().let {
            DatagramPacket(it, it.size, InetAddress.getByName(host), port)
        }
        val endpoint = DesktopPeerEndpoint(remote, false)
        for (message in listOf("PING", "PONG", "BYE", "RVOL_STATE;value=20", "HELLO_ACK_INVALID")) {
            check(!endpoint.accepts(packet(message, 9091), handshake = true))
        }
        check(!endpoint.accepts(packet("HELLO_ACK;v=2", 9091, "127.0.0.2"), handshake = true))
        check(endpoint.accepts(packet("WFAS_AUTH_REQUIRED;snonce=fixture", 9091), handshake = true))
        check(!endpoint.accepts(packet("HELLO_ACK;v=2", 9092), handshake = true))
        endpoint.bind()
        check(endpoint.accepts(packet("PING", 9091)))
        check(endpoint.accepts(packet("PING", 9090)))
        check(!endpoint.accepts(packet("HELLO_ACK;v=2", 9092), handshake = true))
        val nextAttempt = DesktopPeerEndpoint(remote, false)
        check(!nextAttempt.accepts(packet("PING", 9091)))
        check(nextAttempt.accepts(packet("HELLO_ACK;v=2", 9092), handshake = true))
        val multicast = DesktopPeerEndpoint(remote, true)
        multicast.bind()
        check(multicast.accepts(packet("PING", 9092)))
        check(!multicast.accepts(packet("PING", 9092, "127.0.0.2")))
    }

    private suspend fun rewrittenTransport(encrypted: Boolean) = coroutineScope {
        DatagramSocket(InetSocketAddress(loopback, 0)).use { advertised ->
            DatagramSocket(InetSocketAddress(loopback, 0)).use { rewritten ->
                DatagramSocket(InetSocketAddress(loopback, 0)).use { foreign ->
                    advertised.soTimeout = 3000
                    val receivedFrames = AtomicInteger()
                    val microphoneReady = AtomicInteger()
                    val closes = AtomicInteger()
                    val played = AtomicLong()
                    val line = Proxy.newProxyInstance(SourceDataLine::class.java.classLoader, arrayOf(SourceDataLine::class.java)) { proxy, method, args ->
                        when (method.name) {
                            "getBufferSize", "available" -> 96_000
                            "getLongFramePosition" -> played.get()
                            "write" -> (args!![2] as Int).also { played.addAndGet(it.toLong() / 4) }
                            "close" -> { closes.incrementAndGet(); null }
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
                    val key = if (encrypted) "vpn-loopback-fixture" else ""
                    val request = DesktopReceiverRequest(
                        ServerInfo(loopback.hostAddress, false, advertised.localPort), audio,
                        openOutput = { _, _ -> line }, key = key,
                        onFrame = { receivedFrames.incrementAndGet() },
                        onMicrophoneReady = { direction ->
                            check((direction != null) == encrypted)
                            microphoneReady.incrementAndGet(); null
                        })
                    val receiver = DesktopWfasReceiver(request, ConnectionCuePolicy().beginAttempt(false),
                        { 1f }, { false }, {}, { _, _, _ -> }, { _, _, _, _, _ -> })
                    val job = launch(Dispatchers.IO) { receiver.run() }
                    try {
                        val incoming = DatagramPacket(ByteArray(4096), 4096)
                        withContext(Dispatchers.IO) { advertised.receive(incoming) }
                        val destination = incoming.socketAddress
                        fun send(socket: DatagramSocket, bytes: ByteArray) = socket.send(DatagramPacket(bytes, bytes.size, destination))
                        fun text(socket: DatagramSocket, value: String) = send(socket, value.toByteArray(Charsets.US_ASCII))
                        // These must not consume the one allowed adoption before ACK/auth.
                        text(foreign, "PING")
                        send(foreign, pcm(60000))
                        var direction: WfasCrypto.Dir? = null
                        if (encrypted) {
                            val hello = String(incoming.data, 0, incoming.length, Charsets.US_ASCII)
                            val nonce = requireNotNull(WfasAuth.getToken(hello, "cnonce"))
                            val serverNonce = WfasAuth.nonceHex()
                            text(rewritten, "WFAS_AUTH_REQUIRED;snonce=$serverNonce;sproof=${WfasAuth.proof(key, 'S', nonce, serverNonce)}")
                            incoming.length = incoming.data.size
                            withContext(Dispatchers.IO) { advertised.receive(incoming) }
                            val proved = String(incoming.data, 0, incoming.length, Charsets.US_ASCII)
                            check(WfasAuth.getToken(proved, "cproof") == WfasAuth.proof(key, 'C', nonce, serverNonce))
                            direction = WfasCrypto.deriveUnicast(key, nonce, serverNonce).second
                        }
                        text(rewritten, "HELLO_ACK;v=2${if (encrypted) ";enc=1" else ""}")
                        await { microphoneReady.get() == 1 }
                        // A third-port ACK/BYE/audio cannot alter an established attempt.
                        text(foreign, "HELLO_ACK;v=2")
                        text(foreign, "BYE")
                        send(foreign, pcm(60001))
                        if (encrypted) send(rewritten, pcm(50000))
                        delay(100)
                        check(job.isActive && receivedFrames.get() == 0)
                        val plain = pcm(1)
                        val valid = direction?.let { WfasCrypto.encryptPacket(it, 1, 480, false, plain.copyOfRange(10, plain.size)) } ?: plain
                        send(rewritten, valid)
                        await { receivedFrames.get() == 1 }
                        check(job.isActive)
                        text(rewritten, "PING")
                        val pong = DatagramPacket(ByteArray(4096), 4096)
                        withContext(Dispatchers.IO) { advertised.receive(pong) }
                        check(String(pong.data, 0, pong.length, Charsets.US_ASCII) == "PONG")
                    } finally {
                        receiver.stop(); job.cancelAndJoin()
                    }
                    check(closes.get() == 1 && receiver.isCleanupComplete())
                }
            }
        }
    }

    private fun pcm(sequence: Int): ByteArray = ByteArray(1930).also { bytes ->
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            .putShort(0x5746.toShort()).put(2).put(0).putShort(sequence.toShort()).putInt(sequence * 480)
        ByteBuffer.wrap(bytes, 10, 1920).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().apply {
            while (hasRemaining()) put(400)
        }
    }

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(3000) { while (!condition()) delay(5) }
    }
}
