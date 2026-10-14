package com.cuscus.wifiaudiostreaming

internal object WfasDatagram {
    private val beaconPrefix = "WFAS_MCAST_ENC;".toByteArray(Charsets.US_ASCII)

    fun isAudio(bytes: ByteArray, length: Int, multicast: Boolean): Boolean {
        require(length in 0..bytes.size)
        if (length < 10 || bytes[0] != 0x57.toByte() || bytes[1] != 0x46.toByte()) return false
        // The legacy authenticated multicast beacon predates the control-prefix
        // rule. Recognize only its full prefix; unknown WF versions remain audio.
        if (multicast && length in beaconPrefix.size..2048 &&
            beaconPrefix.indices.all { bytes[it] == beaconPrefix[it] }) return false
        return true
    }
}
