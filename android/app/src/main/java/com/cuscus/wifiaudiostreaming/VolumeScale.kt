package com.cuscus.wifiaudiostreaming

import kotlin.math.roundToInt

/** Display percent is independent of DSP gain: sender 100% retains the old 2x ceiling. */
internal object VolumeScale {
    fun percent(gain: Float, maximum: Float): Int {
        require(gain.isFinite() && maximum.isFinite() && maximum > 0)
        return (gain.coerceIn(0f, maximum) / maximum * 100).roundToInt()
    }
    fun gain(percent: Int, maximum: Float): Float {
        require(maximum.isFinite() && maximum > 0)
        return percent.coerceIn(0, 100) / 100f * maximum
    }
}
