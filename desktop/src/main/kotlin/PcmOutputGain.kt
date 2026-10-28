/**
 * Apply live gain only to a disposable device-write copy. Software queues keep
 * unscaled PCM, and only accepted output frames advance a gain transition.
 */
internal class PcmOutputGain(private val channels: Int, private val transitionFrames: Int) {
    private val frameBytes = channels * 2
    private var currentGain = 1f
    private var startGain = 1f
    private var targetGain = 1f
    private var position = transitionFrames
    private var hasAccepted = false
    private var preparedFrames = 0

    init { require(channels in 1..2 && transitionFrames > 0) }

    fun applyInPlace(pcm: ByteArray, offset: Int, length: Int, gain: Float) {
        require(offset >= 0 && length >= 0 && offset <= pcm.size - length &&
            offset % frameBytes == 0 && length % frameBytes == 0)
        require(gain.isFinite() && gain in 0f..2f) { "Invalid receiver gain: $gain" }
        if (!hasAccepted) {
            // No previous output exists to fade from. Preserve static gain and
            // limiter behavior, including when an earlier write accepted zero.
            currentGain = gain; startGain = gain; targetGain = gain; position = transitionFrames
        } else if (gain != targetGain) {
            startGain = currentGain; targetGain = gain; position = 0
        }
        preparedFrames = length / frameBytes
        val changingFrames = minOf(preparedFrames, transitionFrames - position)
        for (frame in 0 until changingFrames) {
            apply(pcm, offset + frame * frameBytes, frameBytes, gainAt(position + frame + 1))
        }
        val changedBytes = changingFrames * frameBytes
        apply(pcm, offset + changedBytes, length - changedBytes, targetGain)
    }

    fun acceptedBytes(bytes: Int) {
        require(bytes >= 0 && bytes % frameBytes == 0 && bytes / frameBytes <= preparedFrames)
        if (bytes == 0) return
        hasAccepted = true
        position += minOf(bytes / frameBytes, transitionFrames - position)
        currentGain = gainAt(position)
        preparedFrames = 0
    }

    private fun gainAt(frame: Int): Float = if (frame >= transitionFrames) targetGain else
        startGain + (targetGain - startGain) * frame / transitionFrames

    private fun apply(pcm: ByteArray, offset: Int, length: Int, gain: Float) {
        if (gain == 1f || length == 0) return
        if (gain > 1f) {
            DesktopGainLimiter.applyPcm16(pcm, offset, length, gain)
            return
        }
        // Keep the established attenuation quantization below unity; boost
        // retains the shared soft knee instead of introducing hard clipping.
        var at = offset
        while (at < offset + length) {
            val sample = ((pcm[at].toInt() and 255) or (pcm[at + 1].toInt() shl 8)).toShort().toInt()
            val value = (sample * gain).toInt()
            pcm[at] = value.toByte(); pcm[at + 1] = (value shr 8).toByte()
            at += 2
        }
    }
}
