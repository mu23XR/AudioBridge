import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object DesktopGainLimiterChecks {
    fun runChecks() {
        val all = ShortArray(65536) { (it - 32768).toShort() }
        val unity = all.copyOf()
        check(DesktopGainLimiter.apply(unity, unity.size, 1f) == 0L)
        check(unity.contentEquals(all)) { "Unity gain must be bit exact" }
        val amplified = all.copyOf()
        check(DesktopGainLimiter.apply(amplified, amplified.size, 2f) > 0L)
        check((0 until amplified.lastIndex).all { amplified[it] <= amplified[it + 1] }) {
            "Amplified PCM must remain monotonic without overflow or flattened hard-clipping plateaus"
        }
        check(amplified.first() > Short.MIN_VALUE && amplified.last() < Short.MAX_VALUE)
        // The knee trades peak resolution for headroom, so upper codes repeat a
        // few inputs in a row. A rail would instead flatten a whole tail: the
        // longest equal run separates quantisation from hard clipping.
        var run = 1
        var longestRun = 1
        for (index in 1..amplified.lastIndex) {
            run = if (amplified[index] == amplified[index - 1]) run + 1 else 1
            if (run > longestRun) longestRun = run
        }
        check(longestRun <= 32) { "Amplification must not hard-clip a large tail to a rail (longest run $longestRun)" }
        check(amplified.toSet().size > 30000) { "The knee must keep the range resolvable instead of collapsing it" }
        val bytes = ByteArray(all.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(all)
        val split = bytes.copyOf()
        val fullCount = DesktopGainLimiter.applyPcm16(bytes, 0, bytes.size, 1.37f)
        var splitCount = 0L
        var at = 0
        while (at < split.size) {
            val n = minOf(254, split.size - at)
            splitCount += DesktopGainLimiter.applyPcm16(split, at, n, 1.37f)
            at += n
        }
        check(bytes.contentEquals(split) && fullCount == splitCount) { "Packet boundaries must not change the limiter response" }
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 2.01f)) {
            check(runCatching { DesktopGainLimiter.apply(shortArrayOf(1), 1, invalid) }.isFailure)
        }
        check(runCatching { DesktopGainLimiter.applyPcm16(ByteArray(4), 0, 3, 1f) }.isFailure)
        val zero = shortArrayOf(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE)
        DesktopGainLimiter.apply(zero, zero.size, 0f)
        check(zero.all { it == 0.toShort() })
        println("test21 PCM gain: unity identity, smooth bounded peaks, packet invariance and invalid input checks passed")
    }
}
