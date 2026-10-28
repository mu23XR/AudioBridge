import kotlinx.coroutines.*
import java.lang.reflect.Proxy
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.SourceDataLine

/** Real loopback UDP, with synthetic output only: no OS sound device is opened. */
internal object DesktopReceiverLifecycleChecks {
    private val audio = AudioSettings_V1(48_000f, 16, 2, 1920, 20, adaptiveLatency = false)

    fun runChecks() = runBlocking {
        DesktopReceiverSessions.stopAllAndJoin()
        try {
            DesktopAudioInitializationChecks.runChecks()
            foreignPeerCannotActivateOrStopTheReceiver()
            multipleSourcesAndRetryRemainIndependent()
            cancelledOpenClosesItsReturnedLine()
            timedOutOpenRetainsItsLateCleanup()
            repeatedRetryCannotSkipTheOriginalOwner()
            playbackFailuresExhaustTheirOwnBudget()
            pausedLatencyChangesDoNotOpenAudio()
            usbDoesNotIncreaseManualTarget()
        } finally { DesktopReceiverSessions.stopAllAndJoin() }
        println("Receiver loopback peer isolation, independent retries, late-open cleanup, ownership barriers and playback budgets passed")
    }

    private suspend fun foreignPeerCannotActivateOrStopTheReceiver() {
        Endpoint(sendAudio = false).use { endpoint ->
            val line = FakeOutput()
            val frames = AtomicInteger()
            var streamed = false
            val receiver = receiver(endpoint, line, onFrame = { frames.incrementAndGet() },
                onConnected = { streamed = true })
            val job = CoroutineScope(currentCoroutineContext()).launch(Dispatchers.IO) { receiver.run() }
            try {
                await("Receiver did not negotiate") { endpoint.client != null }
                DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).use { foreign ->
                    endpoint.sendFrom(foreign, "BYE".toByteArray())
                    endpoint.sendFrom(foreign, pcm(60_000))
                }
                delay(120)
                check(!streamed && frames.get() == 0 && job.isActive) { "Foreign data activated or stopped the receiver" }
                endpoint.send(pcm(1))
                await("Selected peer PCM did not activate receiver") { frames.get() == 1 }
                check(streamed)
                DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).use { foreign ->
                    endpoint.sendFrom(foreign, "BYE".toByteArray())
                }
                endpoint.send(pcm(2))
                await("Foreign BYE stopped healthy peer PCM") { frames.get() == 2 }
            } finally { receiver.stop(); job.cancelAndJoin() }
            check(line.closes.get() == 1)
        }
    }

    private suspend fun multipleSourcesAndRetryRemainIndependent() {
        Endpoint().use { first -> Endpoint().use { second ->
            val firstFrames = AtomicInteger()
            val secondFrames = AtomicInteger()
            val firstLines = mutableListOf<FakeOutput>()
            val secondLines = mutableListOf<FakeOutput>()
            val id1 = DesktopReceiverSessions.connect(request(first, { FakeOutput().also { synchronized(firstLines) { firstLines.add(it) } }.line },
                onFrame = { firstFrames.incrementAndGet() }))
            val id2 = DesktopReceiverSessions.connect(request(second, { FakeOutput().also { synchronized(secondLines) { secondLines.add(it) } }.line },
                onFrame = { secondFrames.incrementAndGet() }))
            await("Both independent sources did not stream") { firstFrames.get() >= 4 && secondFrames.get() >= 4 }
            delay(3_200) // Continuous peer PCM, with deliberately no PING traffic.
            check(first.handshakes.get() == 1 && second.handshakes.get() == 1)
            check(DesktopReceiverSessions.sources.value.all { it.connected })
            val secondHandshakes = second.handshakes.get()
            DesktopReceiverSessions.retry(id1)
            await("Explicit source retry did not renegotiate") { first.handshakes.get() >= 2 }
            val before = secondFrames.get()
            await("Retry of first source interrupted second source") { secondFrames.get() >= before + 4 }
            check(second.handshakes.get() == secondHandshakes)
            DesktopReceiverSessions.remove(id1)
            val after = secondFrames.get()
            await("Removal of first source interrupted second source") { secondFrames.get() >= after + 4 }
            check(DesktopReceiverSessions.sources.value.single().id == id2)
            DesktopReceiverSessions.stopAllAndJoin()
            check(synchronized(firstLines) { firstLines.all { it.closes.get() == 1 } })
            check(synchronized(secondLines) { secondLines.all { it.closes.get() == 1 } })
        } }
    }

    private suspend fun cancelledOpenClosesItsReturnedLine() {
        Endpoint().use { endpoint ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val line = FakeOutput()
            val receiver = DesktopWfasReceiver(request(endpoint, {
                entered.countDown(); release.await(); line.line
            }), ConnectionCuePolicy().beginAttempt(false), { 1f }, { false }, {}, { _, _, _ -> }, { _, _, _, _, _ -> })
            val job = CoroutineScope(currentCoroutineContext()).launch(Dispatchers.IO) { receiver.run() }
            try {
                check(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
                job.cancel(); receiver.stop(); release.countDown()
                withTimeout(2_000) { job.join() }
                check(line.closes.get() == 1) { "Cancellation after native open orphaned its line" }
            } finally { release.countDown(); job.cancelAndJoin() }
        }
    }

    private suspend fun repeatedRetryCannotSkipTheOriginalOwner() {
        Endpoint().use { endpoint ->
            val enteredOpen = CountDownLatch(1)
            val releaseOpen = CountDownLatch(1)
            val enteredClose = CountDownLatch(1)
            val releaseClose = CountDownLatch(1)
            val opens = AtomicInteger()
            val lines = mutableListOf<FakeOutput>()
            val remembered = request(endpoint, {
                val count = opens.incrementAndGet()
                val output = FakeOutput(if (count == 1) {
                    { enteredClose.countDown(); awaitUninterruptibly(releaseClose) }
                } else ({}))
                synchronized(lines) { lines.add(output) }
                if (count == 1) { enteredOpen.countDown(); releaseOpen.await() }
                output.line
            })
            val id = DesktopReceiverSessions.connect(remembered)
            try {
                check(withContext(Dispatchers.IO) { enteredOpen.await(2, TimeUnit.SECONDS) })
                DesktopReceiverSessions.pauseAll()
                releaseOpen.countDown()
                check(withContext(Dispatchers.IO) { enteredClose.await(2, TimeUnit.SECONDS) })
                DesktopReceiverSessions.retry(id) // B waits for the original A close.
                delay(40)
                DesktopReceiverSessions.retry(id) // Cancelling B must not discard A.
                delay(180)
                check(opens.get() == 1) { "Repeated retry bypassed an older output owner" }
                DesktopReceiverSessions.remove(id)
                val replacement = DesktopReceiverSessions.connect(remembered)
                check(replacement != id)
                delay(120)
                check(opens.get() == 1) { "Remove plus a new source id bypassed an older output owner" }
                releaseClose.countDown()
                await("Source did not reopen after original cleanup completed") { opens.get() >= 2 }
            } finally {
                releaseOpen.countDown(); releaseClose.countDown()
                DesktopReceiverSessions.stopAllAndJoin()
            }
            check(synchronized(lines) { lines.all { it.closes.get() == 1 } })
        }
    }

    private suspend fun timedOutOpenRetainsItsLateCleanup() {
        for (failClose in listOf(false, true)) {
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val failed = AtomicBoolean()
            val line = FakeOutput { if (failClose) throw IllegalStateException("synthetic late close failure") }
            try {
                val failure = runCatching {
                    DesktopAudioInitialization.open(timeoutMs = 30,
                        onFinished = { finished.countDown() }, onCleanupFailure = { failed.set(true) }) {
                        release.await(); line.line
                    }
                }.exceptionOrNull()
                check(failure is PlaybackOutputFailure)
                check(finished.count == 1L) { "A timed out open forgot its unfinished native owner" }
                release.countDown()
                check(withContext(Dispatchers.IO) { finished.await(1, TimeUnit.SECONDS) })
                check(failed.get() == failClose)
                if (!failClose) check(line.closes.get() == 1) { "Late output was leaked or closed twice" }
            } finally { release.countDown() }
        }
    }

    private suspend fun playbackFailuresExhaustTheirOwnBudget() {
        Endpoint().use { endpoint -> Endpoint().use { active ->
            val opens = AtomicInteger()
            val output = FakeOutput()
            DesktopReceiverSessions.connect(request(active, { output.line }))
            val id = DesktopReceiverSessions.connect(request(endpoint, {
                opens.incrementAndGet()
                throw PlaybackOutputFailure("synthetic output open failure")
            }))
            await("Playback failures did not reach their separate retry budget", 7_000) {
                DesktopReceiverSessions.sources.value.singleOrNull { it.id == id }?.needsAttention == true
            }
            check(opens.get() == 3) {
                "Playback retry budget stopped after ${opens.get()} opens: ${DesktopReceiverSessions.sources.value.single { it.id == id }}"
            }
            check(DesktopReceiverSessions.sources.value.single { it.id == id }.status.contains("Audio output"))
            output.peak.set(0)
            await("Failed source reduced a healthy source's gain") { output.peak.get() >= 750 }
            DesktopReceiverSessions.remove(id)
            DesktopReceiverSessions.stopAllAndJoin()
        } }
    }

    private suspend fun pausedLatencyChangesDoNotOpenAudio() {
        Endpoint().use { endpoint ->
            val opens = AtomicInteger()
            val outputs = mutableListOf<FakeOutput>()
            val id = DesktopReceiverSessions.restore(request(endpoint, {
                opens.incrementAndGet(); FakeOutput().also { synchronized(outputs) { outputs.add(it) } }.line
            }))
            DesktopReceiverSessions.setLatency(id, false, 73)
            delay(100)
            check(opens.get() == 0 && DesktopReceiverSessions.sources.value.single().paused)
            DesktopReceiverSessions.retry(id)
            await("Restored source did not stream after retry") { DesktopReceiverSessions.sources.value.single().connected }
            check(!endpoint.lastHello.contains(";rvol="))
            DesktopReceiverSessions.setRemoteVolumeEnabled(true)
            await("Remote control opt-in did not renegotiate HELLO") {
                endpoint.handshakes.get() >= 2 && endpoint.lastHello.contains(";rvol=1;")
            }
            DesktopReceiverSessions.pauseAllAndJoin()
            val paused = DesktopReceiverSessions.sources.value.single()
            check(paused.id == id && paused.paused && !paused.connected && paused.manualLatencyMs == 73)
            val handshakes = endpoint.handshakes.get()
            DesktopReceiverSessions.setRemoteVolumeEnabled(false)
            delay(80)
            check(endpoint.handshakes.get() == handshakes && DesktopReceiverSessions.sources.value.single().paused)
            check(synchronized(outputs) { outputs.all { it.closes.get() == 1 } })
            DesktopReceiverSessions.stopAllAndJoin()
        }
    }

    private suspend fun usbDoesNotIncreaseManualTarget() {
        val oldLatency = UsbLink.latencyMs
        val oldEnabled = UsbLink.enabled
        val oldPreferred = UsbLink.preferredName
        Endpoint().use { endpoint ->
            val output = FakeOutput()
            val target = AtomicInteger()
            UsbLink.configure(false, 120)
            val request = request(endpoint, { output.line }).let {
                it.copy(peer = it.peer.copy(viaUsb = true), audio = audio.copy(latencyMs = 40),
                    onTargetChanged = { value -> value?.let(target::set) })
            }
            val transport = DesktopWfasReceiver(request, ConnectionCuePolicy().beginAttempt(false),
                { 1f }, { false }, {}, { _, _, _ -> }, { _, _, _, _, _ -> })
            val job = CoroutineScope(currentCoroutineContext()).launch(Dispatchers.IO) { transport.run() }
            try {
                await("USB source did not initialize") { target.get() != 0 }
                check(target.get() == 40) { "USB increased a lower manual target to ${target.get()} ms" }
            } finally {
                transport.stop(); job.cancelAndJoin()
                UsbLink.configure(oldEnabled, oldLatency, oldPreferred)
            }
        }
    }

    private fun receiver(endpoint: Endpoint, line: FakeOutput, onFrame: (ShortArray) -> Unit,
                         onConnected: () -> Unit) = DesktopWfasReceiver(
        request(endpoint, { line.line }, onFrame), ConnectionCuePolicy().beginAttempt(false),
        { 1f }, { false }, {}, { _, _, connected -> if (connected) onConnected() }, { _, _, _, _, _ -> })

    private fun request(endpoint: Endpoint, output: () -> SourceDataLine,
                        onFrame: ((ShortArray) -> Unit)? = null) = DesktopReceiverRequest(
        peer = ServerInfo("127.0.0.1", false, endpoint.port, serverAudioSettings = audio), audio = audio,
        openOutput = { _, _ -> output() }, onFrame = onFrame, expectedEpoch = 0, connectionSoundEnabled = false)

    private suspend fun await(message: String, timeoutMs: Long = 3_000, predicate: () -> Boolean) {
        withTimeoutOrNull(timeoutMs) { while (!predicate()) delay(5) }
        check(predicate()) { message }
    }

    private fun awaitUninterruptibly(latch: CountDownLatch) {
        var interrupted = false
        while (true) try { latch.await(); break } catch (_: InterruptedException) { interrupted = true }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun pcm(sequence: Int): ByteArray = ByteBuffer.allocate(1930).apply {
        put(0x57); put(0x46); put(2); put(0); putShort(sequence.toShort()); putInt(sequence * 480)
        repeat(960) { put(0x20); put(0x03) }
    }.array()

    private class Endpoint(private val sendAudio: Boolean = true) : AutoCloseable {
        private val socket = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).apply { soTimeout = 10 }
        val port get() = socket.localPort
        @Volatile var client: InetSocketAddress? = null
        @Volatile var lastHello = ""
        @Volatile private var running = true
        val handshakes = AtomicInteger()
        private val thread = Thread({
            val buffer = ByteArray(4096)
            val packet = DatagramPacket(buffer, buffer.size)
            var sequence = 0
            var lastAudio = 0L
            try {
                while (running) {
                    try {
                        packet.length = buffer.size; socket.receive(packet)
                        val text = String(buffer, 0, packet.length, Charsets.US_ASCII)
                        if (text.startsWith("HELLO_FROM_CLIENT;")) {
                            lastHello = text
                            client = packet.socketAddress as InetSocketAddress
                            handshakes.incrementAndGet(); sequence = 0
                            send("HELLO_ACK;v=2".toByteArray())
                        }
                    } catch (_: SocketTimeoutException) { }
                    if (sendAudio && client != null && System.nanoTime() - lastAudio >= 10_000_000) {
                        send(pcm(sequence++ and 65535)); lastAudio = System.nanoTime()
                    }
                }
            } catch (failure: Exception) {
                if (running) throw failure
            }
        }, "wfas-test-loopback-peer").apply { isDaemon = true; start() }
        fun send(bytes: ByteArray) = sendFrom(socket, bytes)
        fun sendFrom(sender: DatagramSocket, bytes: ByteArray) {
            val destination = requireNotNull(client)
            sender.send(DatagramPacket(bytes, bytes.size, destination))
        }
        override fun close() { running = false; socket.close(); thread.join(1_000); check(!thread.isAlive) }
    }

    private class FakeOutput(private val onClose: () -> Unit = {}) {
        val closes = AtomicInteger()
        val peak = AtomicInteger()
        private val frames = AtomicLong()
        val line = Proxy.newProxyInstance(SourceDataLine::class.java.classLoader,
            arrayOf(SourceDataLine::class.java)) { proxy, method, args ->
            when (method.name) {
                "getBufferSize", "available" -> 96_000
                "getLongFramePosition" -> frames.get()
                "write" -> {
                    val bytes = args!![0] as ByteArray
                    val offset = args[1] as Int
                    val size = args[2] as Int
                    for (at in offset until offset + size step 2) {
                        val sample = ((bytes[at].toInt() and 255) or (bytes[at + 1].toInt() shl 8)).toShort().toInt()
                        peak.accumulateAndGet(kotlin.math.abs(sample), ::maxOf)
                    }
                    frames.addAndGet(size.toLong() / 4); size
                }
                "close" -> { onClose(); closes.incrementAndGet(); null }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "synthetic receiver lifecycle output"
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
