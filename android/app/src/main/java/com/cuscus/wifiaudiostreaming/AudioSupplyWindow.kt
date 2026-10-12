package com.cuscus.wifiaudiostreaming

/** Liveness datagrams are not evidence that the player has non-silent PCM to consume. */
internal class AudioSupplyWindow {
    @Volatile private var lastPcmAt = -1L
    fun observe(now: Long, data: ByteArray, offset: Int, length: Int, silence: Boolean = false) {
        if (!silence && length > 0 && !PcmSilence.isZero(data, offset, length)) lastPcmAt = now
    }
    fun flowing(now: Long): Boolean = lastPcmAt >= 0 && now - lastPcmAt in 0 until 3000L
}
