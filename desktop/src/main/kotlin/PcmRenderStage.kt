/**
 * Render only the next small output block. Clock correction must still affect
 * a received burst's unplayed tail, like Android's track rate and test17's
 * consumer-side resampler. An independent output FIFO retains partial writes.
 */
internal class PcmRenderStage(
    private val input: PcmQueue,
    sampleRate: Int,
    channels: Int,
    private val resampler: DesktopPcmDriftResampler,
    private val producer: Any
) {
    private val frameBytes = channels * 2
    private val inputBatchBytes = (sampleRate.toLong() * 20 / 1000).coerceAtLeast(1).toInt() * frameBytes
    val output = PcmQueue(frameBytes, (sampleRate.toLong() * 40 / 1000).coerceAtLeast(1).toInt() * frameBytes)
    private var pendingBoundary = false

    fun bytes(): Int = input.bytes() + output.bytes()
    fun discardedBytes(): Long = input.discardedBytes + output.discardedBytes
    fun hasInput(): Boolean = input.bytes() > 0

    fun renderNext(): Boolean = synchronized(producer) {
        if (output.bytes() > 0) return@synchronized true
        // A carried interpolation endpoint can consume a one-frame chunk
        // without output. One more chunk is sufficient to produce a frame.
        repeat(2) {
            if (input.bytes() == 0) return@synchronized false
            val boundary = input.takeDiscontinuity()
            if (boundary) {
                pendingBoundary = true
                val ratio = resampler.ratio
                resampler.reset()
                resampler.ratio = ratio
            }
            input.drain(inputBatchBytes) { pcm, offset, length ->
                val rendered = resampler.process(pcm.copyOfRange(offset, offset + length))
                if (rendered.isNotEmpty()) {
                    output.offer(rendered, boundary = pendingBoundary)
                    pendingBoundary = false
                }
                length
            }
            if (output.bytes() > 0) return@synchronized true
        }
        false
    }

    fun clear() = synchronized(producer) {
        input.clear(); output.clear(); pendingBoundary = false
    }
}
