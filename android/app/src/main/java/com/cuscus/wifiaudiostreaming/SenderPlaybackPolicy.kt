package com.cuscus.wifiaudiostreaming

/** An absent preference preserves the capture behavior of an existing install. */
internal object SenderPlaybackPolicy {
    fun keepPlayingOnDevice(savedChoice: Boolean?, sdk: Int): Boolean =
        savedChoice ?: (sdk >= 33)

    fun interceptHardwareVolume(senderActive: Boolean, shizukuActive: Boolean, localPlayback: Boolean): Boolean =
        senderActive && (!shizukuActive || !localPlayback)
}
