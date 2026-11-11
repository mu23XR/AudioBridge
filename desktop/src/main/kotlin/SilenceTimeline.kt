/**
 * Samples of silence that elapsed between captured frames.
 *
 * A silence header does not carry audio, but it must still move the receiver's sample timeline:
 * a position that never advances makes every later real packet look stale or rewound, and a
 * position that jumps too far looks like a discontinuity. This class converts the wall-clock gap
 * into frames and clamps it so a suspended machine cannot report minutes of silence in one read.
 *
 * Not thread-safe on purpose: one sender loop owns one instance.
 */
internal class SilenceTimeline(
    private val sampleRate: Int,
    private val clampMs: Long = 500L
) {
    private var lastNs: Long? = null
    private var fractionalFrameNumerator = 0L
    private val clampNs: Long

    init {
        require(sampleRate in 4000..192000 && clampMs > 0)
        clampNs = Math.multiplyExact(clampMs, 1_000_000L)
        // Both the nanosecond multiplication and the Int frame result must fit.
        require(clampNs <= (Long.MAX_VALUE - 999_999_999L) / sampleRate)
        require(clampNs * sampleRate + 999_999_999L <= Int.MAX_VALUE.toLong() * 1_000_000_000L)
    }

    /** Records a frame that carried real audio, so the next gap starts from now. */
    fun onAudio(nowNs: Long) {
        lastNs = nowNs
        fractionalFrameNumerator = 0L
    }

    /** Converts the gap since the previous frame into frames of silence and restarts the gap. */
    fun onSilence(nowNs: Long): Int {
        val previous = lastNs ?: run { lastNs = nowNs; return 0 }
        val elapsedNs = nowNs - previous
        // Do not move the origin backwards and then count the same interval twice.
        if (elapsedNs < 0) return 0
        lastNs = nowNs
        val numerator = sampleRate.toLong() * minOf(elapsedNs, clampNs) + fractionalFrameNumerator
        fractionalFrameNumerator = numerator % 1_000_000_000L
        return (numerator / 1_000_000_000L).toInt()
    }
}
