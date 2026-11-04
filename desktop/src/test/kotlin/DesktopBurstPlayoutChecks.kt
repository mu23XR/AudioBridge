import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sound.sampled.SourceDataLine

/** A receive thread can admit a burst while a device call owns the player thread. */
internal object DesktopBurstPlayoutChecks {
    fun runChecks() {
        burstDuringNativeWriteKeepsEveryFrame()
        periodicArrivalsDoNotBiasTheClockSample()
        println("Real player: a100ms receive burst during native write preserves every PCM frame")
    }

    private fun burstDuringNativeWriteKeepsEveryFrame() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val captured = ByteArrayOutputStream()
        var firstWrite = true
        var playedFrames = 0L
        val line = Proxy.newProxyInstance(SourceDataLine::class.java.classLoader,
            arrayOf(SourceDataLine::class.java)) { proxy, method, args ->
            when (method.name) {
                "getBufferSize", "available" -> 96_000
                "getLongFramePosition" -> synchronized(captured) { playedFrames }
                "write" -> {
                    if (firstWrite) {
                        firstWrite = false
                        entered.countDown()
                        check(release.await(3, TimeUnit.SECONDS)) { "Receive burst fixture was not released" }
                    }
                    synchronized(captured) {
                        val count = args!![2] as Int
                        captured.write(args[0] as ByteArray, args[1] as Int, count)
                        playedFrames += count / 4
                        count
                    }
                }
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
        val initial = pcm(480)
        val packet = pcm(345)
        val expected = ByteArrayOutputStream().apply {
            write(initial)
            repeat(14) { write(packet) }
        }.toByteArray()
        val player = DesktopJitterAudioPlayer(line, 48_000, 2, health = PlaybackHealth(false, 20))
        player.start()
        try {
            player.submit(initial, 0, initial.size)
            check(entered.await(3, TimeUnit.SECONDS)) { "Player never entered the native write" }
            // No physical device or real recording: all14 ordinary WFAS packets
            // arrive before the playback worker can resume its first write.
            repeat(14) { player.submit(packet, 0, packet.size) }
            release.countDown()
            val deadline = System.nanoTime() + 3_000_000_000L
            while (System.nanoTime() < deadline) {
                player.throwIfFailed()
                val snapshot = player.snapshot()
                if (snapshot.discardedBytes > 0 || synchronized(captured) { captured.size() >= expected.size }) break
                Thread.sleep(2)
            }
            val actual = synchronized(captured) { captured.toByteArray() }
            println("[receive burst] expectedBytes=${expected.size} acceptedBytes=${actual.size} " +
                "discardedBytes=${player.snapshot().discardedBytes}")
            check(actual.contentEquals(expected) && player.snapshot().discardedBytes == 0L) {
                "A fresh100ms receive burst lost PCM during a native write: " +
                    "expected=${expected.size} accepted=${actual.size} discarded=${player.snapshot().discardedBytes}"
            }
        } finally { release.countDown(); player.stop() }
        check(player.awaitStopped(2_000)) { "Receive burst fixture did not release its output" }
    }

    private fun pcm(frames: Int) = ByteArray(frames * 4) { if (it % 2 == 0) 0x10 else 0x27 }

    private fun periodicArrivalsDoNotBiasTheClockSample() {
        val level = DesktopPlayoutLevel()
        for (ms in 0L..2000L) {
            //100ms of new audio every100ms: the200ms control tick always
            // lands on the peak, but the persistent reserve is at the valley.
            level.observe(ms, 4800 - (ms % 100) * 48)
            if (ms < 1000) {
                check(level.floorFrames() == null)
                check(checkNotNull(level.clockFrames(960)) <= 960) {
                    "Startup burst incorrectly asked clock control to consume reserve faster"
                }
            }
            else if (ms % 200 == 0L) check(level.floorFrames() == 48L) {
                "A periodic burst reserve was mistaken for hardware clock drift"
            }
        }
        level.observe(500, 96) // Monotonic clock anomaly must not retain the old interval.
        check(level.floorFrames() == null)
        level.observe(1500, 48_000)
        check(level.floorFrames() == null) { "Missing observations were treated as sustained high backlog" }
    }
}

/** Focused development entry point; the normal protocol suite also runs these checks. */
fun main() = DesktopBurstPlayoutChecks.runChecks()
