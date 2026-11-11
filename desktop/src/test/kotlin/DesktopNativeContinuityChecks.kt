import kotlinx.coroutines.*
import java.lang.reflect.Proxy
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.SourceDataLine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** The real receiver chooses capacity; a clocked finite device then checks continuous PCM. */
internal object DesktopNativeContinuityChecks {
    fun runChecks() = runBlocking {
        for ((target, adaptive) in listOf(41 to false, 20 to false, 400 to false, 20 to true)) {
            val capacity = requestedCapacity(target, adaptive)
            continuousClock(target, capacity)
            if (adaptive) continuousClock(80, capacity) // Growth must fit without closing a healthy line.
            if (!adaptive && target in listOf(20, 41)) {
                continuousClock(target, capacity, 250, jitter = true)
                continuousClock(target, capacity, -250, jitter = true)
            }
        }
        for (target in listOf(63, 191, 400)) {
            continuousClock(target, requestedCapacity(target, false), arrivalBatchMs = 100)
        }
        for (ppm in listOf(-250, 250)) continuousClock(63, requestedCapacity(63, false), ppm, arrivalBatchMs = 100)
        continuousClock(63, requestedCapacity(63, false), playerStallMs = 40)
        continuousClock(191, requestedCapacity(191, false), playerStallMs = 100)
        for (batch in listOf(100, 300, 400, 500)) {
            continuousClock(68, requestedCapacity(68, false), arrivalBatchMs = batch, packetFrames = 168)
        }
        for (target in listOf(20, 41, 80, 191, 400)) {
            continuousClock(target, requestedCapacity(target, false), arrivalBatchMs = 500, packetFrames = 168)
        }
        for (ppm in listOf(-250, 250)) {
            continuousClock(68, requestedCapacity(68, false), ppm, arrivalBatchMs = 400, packetFrames = 168)
        }
        continuousClock(68, requestedCapacity(68, false), arrivalBatchMs = 400, packetFrames = 168,
            playerTickMs = 5, writeCostUs = 750)
        continuousClock(68, requestedCapacity(68, false), arrivalBatchMs = 400, packetFrames = 168, durationMs = 2_100_000)
        println("Receiver continuity: native capacity/adaptive growth,100–500ms batches,±250ppm,40/100ms player stalls and35 virtual minutes passed")
    }

    private suspend fun requestedCapacity(target: Int, adaptive: Boolean): Int = coroutineScope {
        val loopback = InetAddress.getByName("127.0.0.1")
        DatagramSocket(InetSocketAddress(loopback, 0)).use { peer ->
            peer.soTimeout = 3000
            val capacity = AtomicInteger()
            val line = Proxy.newProxyInstance(SourceDataLine::class.java.classLoader,
                arrayOf(SourceDataLine::class.java)) { proxy, method, args ->
                when (method.name) {
                    "getBufferSize", "available" -> 96_000
                    "getLongFramePosition" -> 0L
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
            val audio = AudioSettings_V1(48_000f, 16, 2, 1920, target, adaptiveLatency = adaptive)
            val request = DesktopReceiverRequest(ServerInfo(loopback.hostAddress, false, peer.localPort), audio,
                openOutput = { _, ms -> capacity.set(ms); line })
            val receiver = DesktopWfasReceiver(request, ConnectionCuePolicy().beginAttempt(false),
                { 1f }, { false }, {}, { _, _, _ -> }, { _, _, _, _, _ -> })
            val job = launch(Dispatchers.IO) { receiver.run() }
            try {
                val hello = DatagramPacket(ByteArray(4096), 4096)
                withContext(Dispatchers.IO) { peer.receive(hello) }
                val ack = "HELLO_ACK;v=2".toByteArray()
                peer.send(DatagramPacket(ack, ack.size, hello.socketAddress))
                withTimeout(3000) { while (capacity.get() == 0) delay(2) }
                capacity.get()
            } finally { receiver.stop(); job.cancelAndJoin() }
        }
    }

    /** Ninety virtual seconds, ordinary WFAS packets and10ms native progress granularity. */
    private fun continuousClock(targetMs: Int, capacityMs: Int, sourcePpm: Int = 0,
                                jitter: Boolean = false, arrivalBatchMs: Int = 1, playerStallMs: Int = 0,
                                packetFrames: Int = 345, playerTickMs: Int = 1, writeCostUs: Int = 0,
                                durationMs: Int = 90_000) {
        val sampleRate = 48_000
        val frameBytes = 4
        val queue = PcmQueue(frameBytes, sampleRate * frameBytes * DesktopPlayoutLimits.QUEUE_CAPACITY_MS / 1000)
        var clockMs = 0L
        var nativeCallNanos = 0L
        val drift = DesktopPlayoutDrift(sampleRate)
        val level = DesktopPlayoutLevel()
        val resampler = DesktopPcmDriftResampler(2)
        val rendered = PcmRenderStage(queue, sampleRate, 2, resampler, Any())
        val pump = PcmDrainPump(rendered.output, sampleRate, frameBytes,
            refill = rendered::renderNext, hasInput = rendered::hasInput,
            nowNanos = { clockMs * 1_000_000 + nativeCallNanos })
        var nativeFrames = 0L
        var playedFrames = 0L
        var previousSample: Int? = null
        var discontinuities = 0
        var underflowFrames = 0L
        val underrunEvents = mutableListOf<String>()
        var maxBufferedFrames = 0L
        val native = object : PlaybackOutput {
            override val capacityBytes = sampleRate * frameBytes * capacityMs / 1000
            override fun available() = capacityBytes - nativeFrames.toInt() * frameBytes
            override fun framePosition() = playedFrames
            override fun start() {}
            override fun close() {}
            override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                check(length <= available())
                val pcm = ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                while (pcm.hasRemaining()) {
                    val sample = pcm.get().toInt()
                    pcm.get()
                    //997Hz at12k amplitude changes by at most1580 units/frame, even at1.008x.
                    if (previousSample?.let { abs(sample - it) > 1800 } == true) discontinuities++
                    previousSample = sample
                }
                nativeFrames += length / frameBytes
                nativeCallNanos += writeCostUs * 1000L
                return length
            }
        }
        val output = RecoverableAudioOutput(native, frameBytes, clockMs = { clockMs },
            maxBufferedBytes = { sampleRate * frameBytes * (targetMs + 15) / 1000 })
        var inputFrames = 0L
        var primed = false
        try {
            for (tick in 0..durationMs) {
                clockMs = tick.toLong()
                nativeCallNanos = 0
                if (tick % 10 == 0 && primed) {
                    val consumed = minOf(nativeFrames, 480)
                    if (tick > 1000) {
                        underflowFrames += 480 - consumed
                        if (consumed < 480 && underrunEvents.size < 10) underrunEvents.add("$tick:${480 - consumed}")
                    }
                    nativeFrames -= consumed; playedFrames += consumed
                }
                val deliveryDelay = if (jitter) (inputFrames / packetFrames % 3).toInt() else 0
                val dueFrames = (tick - deliveryDelay) * sampleRate.toDouble() * (1 + sourcePpm / 1_000_000.0) / 1000
                while (tick % arrivalBatchMs == 0 && inputFrames + packetFrames <= dueFrames) {
                    val pcm = ByteArray(packetFrames * frameBytes)
                    val shorts = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    repeat(packetFrames) { frame ->
                        val value = (12_000 * sin(2 * PI * 997 * (inputFrames + frame) / sampleRate)).toInt().toShort()
                        shorts.put(value); shorts.put(value)
                    }
                    inputFrames += packetFrames
                    queue.offer(pcm)
                }
                if (!primed) primed = rendered.bytes() >= sampleRate * frameBytes * DesktopPlayoutLimits.startupPrerollMs(targetMs) / 1000
                if (!primed) continue
                if (tick % playerTickMs != 0) continue
                // A settled stream keeps receiving and consuming while the
                // playback worker is descheduled; do not pause either clock.
                if (tick >= 20_000 && tick % 1000 in 200 until 200 + playerStallMs) continue
                var result: PcmDrainPump.Result
                do {
                    result = pump.drain(targetMs, { nativeFrames }) { pcm, offset, length ->
                        output.writeSome(pcm, offset, length)
                    }
                } while (result.limited && writeCostUs == 0 && playerTickMs == 1)
                level.observe(clockMs, nativeFrames + rendered.bytes() / frameBytes)
                maxBufferedFrames = maxOf(maxBufferedFrames, nativeFrames + rendered.bytes() / frameBytes)
                if (tick % 200 == 0) level.clockFrames(sampleRate.toLong() * targetMs / 1000)?.let { floor ->
                    resampler.ratio = drift.sample(true, floor, sampleRate.toLong() * targetMs / 1000,
                        warmingUp = level.floorFrames() == null)
                }
            }
            println("[continuity] target=$targetMs nativeCapacity=$capacityMs sourcePpm=$sourcePpm jitter=$jitter arrivalBatchMs=$arrivalBatchMs playerStallMs=$playerStallMs packetFrames=$packetFrames playerTickMs=$playerTickMs writeCostUs=$writeCostUs durationMs=$durationMs discarded=${rendered.discardedBytes()} " +
                "waveformEdges=$discontinuities underrunFrames=$underflowFrames underrunEvents=$underrunEvents maxBufferedMs=${maxBufferedFrames * 1000 / sampleRate} ratio=${drift.ratio}")
            check(rendered.discardedBytes() == 0L && discontinuities == 0 && underflowFrames == 0L) {
                "Continuous input was broken: target=$targetMs capacity=$capacityMs discarded=${rendered.discardedBytes()} " +
                    "waveformEdges=$discontinuities underrunFrames=$underflowFrames"
            }
            check(maxBufferedFrames <= sampleRate.toLong() * (targetMs + arrivalBatchMs + playerStallMs + 40) / 1000) {
                "A burst reserve became permanent extra latency: $maxBufferedFrames frames"
            }
            check(abs(drift.ratio - (1 + sourcePpm / 1_000_000.0)) < .001) {
                "The packet-burst reserve was mistaken for clock error: ratio=${drift.ratio}"
            }
        } finally { output.close() }
    }
}
