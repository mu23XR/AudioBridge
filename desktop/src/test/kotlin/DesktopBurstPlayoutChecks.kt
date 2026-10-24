import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sound.sampled.SourceDataLine

/** A receive thread can admit a burst while a device call owns the player thread. */
internal object DesktopBurstPlayoutChecks {
    fun runChecks() {
        burstDuringNativeWriteKeepsEveryFrame()
        persistentBacklogUsesOneCombinedBudget()
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

    private fun persistentBacklogUsesOneCombinedBudget() {
        fun frames(ms: Int) = 48L * ms
        fun bytes(ms: Int) = 48 * 4 * ms
        val policy = ReceiverBacklogPolicy(48_000, 4)
        // The191ms already in the device counts toward the latency budget.
        check(policy.softwareLimit(0, bytes(300), frames(191), 191) == null)
        check(policy.softwareLimit(249, bytes(300), frames(191), 191) == null)
        check(policy.softwareLimit(250, bytes(300), frames(191), 191) == bytes(15))

        policy.reset()
        check(policy.softwareLimit(0, bytes(100), frames(78), 63) == null)
        check(policy.softwareLimit(100, bytes(30), frames(78), 63) == null) // Burst drained below budget.
        check(policy.softwareLimit(200, bytes(100), frames(78), 63) == null)
        check(policy.softwareLimit(449, bytes(100), frames(78), 63) == null)
        check(policy.softwareLimit(450, bytes(100), frames(78), 63) == 0)

        policy.reset()
        check(policy.softwareLimit(0, bytes(200), 0, 20, true) == null)
        check(policy.softwareLimit(300, bytes(200), 0, 20, true) == null)
        check(policy.softwareLimit(301, bytes(200), 0, 20) == bytes(35))
        check(policy.softwareLimit(500, bytes(200), 0, 20) == null)
        check(policy.softwareLimit(400, bytes(200), 0, 20) == null) // Clock rollback restarts grace.
        check(policy.softwareLimit(649, bytes(200), 0, 20) == null)
        check(policy.softwareLimit(650, bytes(200), 0, 20) == bytes(35))
    }

    private fun periodicArrivalsDoNotBiasTheClockSample() {
        val level = DesktopPlayoutLevel()
        for (ms in 0L..1000L) {
            //100ms of new audio every100ms: the200ms control tick always
            // lands on the peak, but the time-average is about50ms.
            level.observe(ms, 4800 - (ms % 100) * 48)
            if (ms > 0 && ms % 200 == 0L) check(level.averageFrames() in 2400..2448) {
                "Periodic arrivals aliased the clock-control measurement"
            }
        }
        level.observe(500, 96) // Monotonic clock anomaly must not retain the old interval.
        check(level.averageFrames() == 96L)
    }
}

/** Focused development entry point; the normal protocol suite also runs these checks. */
fun main() = DesktopBurstPlayoutChecks.runChecks()
