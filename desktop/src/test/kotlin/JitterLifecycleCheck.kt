import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.SourceDataLine

fun jitterLifecycleChecks() {
    println("== WFAS playout worker lifecycle ==")
    class LineFixture(val blocking: Boolean = false) {
        val closed = AtomicBoolean(false)
        val bytes = AtomicLong(0)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val line = Proxy.newProxyInstance(SourceDataLine::class.java.classLoader,
            arrayOf(SourceDataLine::class.java)) { proxy, method, args ->
            when (method.name) {
                "getBufferSize", "available" -> 12000
                "getLongFramePosition" -> bytes.get() / 4
                "write" -> {
                    entered.countDown()
                    if (blocking) release.await()
                    val n = args!![2] as Int
                    bytes.addAndGet(n.toLong())
                    n
                }
                "close" -> { closed.set(true); release.countDown(); null }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "Fixture audio line"
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
    val fixture = LineFixture()
    val target = PlaybackHealth(false, 100)
    val player = NetworkHandler_v1.JitterAudioPlayer(fixture.line, 48000, 2, health = target)
    player.start()
    try {
        player.noteAudioFlow(true)
        player.submit(ByteArray(7680), 0, 7680) // 40 ms, below half of the 100 ms target.
        check(!fixture.entered.await(100, TimeUnit.MILLISECONDS)) { "Playback primed too early" }
        player.submit(ByteArray(1920), 0, 1920) // Exactly half-target preroll.
        check(fixture.entered.await(500, TimeUnit.MILLISECONDS)) { "Half-target preroll did not start playback" }
    } finally { player.stop() }
    check(fixture.closed.get())
    check(NetworkHandler_v1.playbackTargetMs.value == null)

    val stuck = LineFixture(blocking = true)
    val blockedPlayer = NetworkHandler_v1.JitterAudioPlayer(stuck.line, 48000, 2,
        health = PlaybackHealth(false, 20), watchdogTimeoutMs = 100)
    blockedPlayer.start()
    try {
        blockedPlayer.noteAudioFlow(true)
        blockedPlayer.submit(ByteArray(1920), 0, 1920)
        check(stuck.entered.await(500, TimeUnit.MILLISECONDS))
        val deadline = System.nanoTime() + 2_000_000_000L
        var failure: Throwable? = null
        while (System.nanoTime() < deadline && failure == null) {
            blockedPlayer.noteAudioFlow(true)
            failure = runCatching { blockedPlayer.throwIfFailed() }.exceptionOrNull()
            Thread.sleep(20)
        }
        check(failure is PlaybackOutputFailure) { "Blocked native write was not surfaced to the network worker" }
    } finally { blockedPlayer.stop() }
    check(stuck.closed.get())
    Thread.sleep(50)
    check(Thread.getAllStackTraces().keys.none { it.isAlive && it.name in listOf("wfas-jitter-player", "wfas-playback-watchdog") }) {
        "Playback worker/watchdog leaked after stop"
    }
    println("Half-target preroll, blocked-write watchdog and worker/output cleanup passed")
}
