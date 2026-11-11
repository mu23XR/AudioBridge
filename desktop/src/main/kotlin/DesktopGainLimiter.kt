import kotlin.math.abs

/** test21's packet-independent PCM16 knee; unity keeps every input bit intact. */
internal object DesktopGainLimiter {
    private fun validateGain(gain: Float) {
        require(gain.isFinite() && gain in 0f..2f) { "PCM gain must be finite and between 0 and 2" }
    }

    private class Curve(private val gain: Double) {
        var limitedSamples = 0L
            private set
        private val positiveWidth = 32767.0 * 0.25 * (gain - 1.0)
        private val negativeWidth = 32768.0 * 0.25 * (gain - 1.0)

        fun apply(sample: Int): Short {
            var value = sample * gain
            if (gain > 1.0) {
                val limit = if (sample < 0) 32768.0 else 32767.0
                val width = if (sample < 0) negativeWidth else positiveWidth
                val threshold = limit - width
                val magnitude = abs(value)
                if (magnitude > threshold) {
                    val excess = magnitude - threshold
                    value = Math.copySign(threshold + width * excess / (width + excess), value)
                    limitedSamples++
                }
            }
            return Math.round(value).toInt().toShort()
        }
    }

    /** Apply after capture/mic mixing, before any packet is encoded. */
    fun apply(samples: ShortArray, count: Int, gain: Float): Long {
        require(count in 0..samples.size) { "PCM sample count exceeds input" }
        validateGain(gain)
        if (gain == 1f) return 0
        val curve = Curve(gain.toDouble())
        for (i in 0 until count) {
            samples[i] = curve.apply(samples[i].toInt())
        }
        return curve.limitedSamples
    }

    fun applyPcm16(bytes: ByteArray, offset: Int, length: Int, gain: Float): Long {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length && offset % 2 == 0 && length % 2 == 0) {
            "PCM16 range must contain complete samples"
        }
        validateGain(gain)
        if (gain == 1f) return 0
        val curve = Curve(gain.toDouble())
        var i = offset
        while (i < offset + length) {
            val sample = ((bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)).toShort().toInt()
            val value = curve.apply(sample).toInt()
            bytes[i] = value.toByte()
            bytes[i + 1] = (value shr 8).toByte()
            i += 2
        }
        return curve.limitedSamples
    }
}
