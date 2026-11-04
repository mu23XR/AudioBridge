import kotlin.math.roundToInt

/** Matches Android test21 while retaining the existing physical DSP gain. */
object DesktopVolumeScale {
    const val SENDER_MAX = 2f
    const val RECEIVER_MAX = 1f

    fun percent(gain: Float, maximum: Float): Int {
        require(gain.isFinite() && maximum.isFinite() && maximum > 0)
        return (gain.coerceIn(0f, maximum) / maximum * 100f).roundToInt()
    }

    fun gain(percent: Int, maximum: Float): Float {
        require(percent in 0..100 && maximum.isFinite() && maximum > 0)
        return percent / 100f * maximum
    }
}
