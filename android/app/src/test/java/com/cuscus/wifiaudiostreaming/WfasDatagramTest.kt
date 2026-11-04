package com.cuscus.wifiaudiostreaming

import org.junit.Assert.*
import org.junit.Test

class WfasDatagramTest {
    @Test fun optionalVolumeControlsCannotCollideWithBinaryAudioMagic() {
        for (kind in listOf(RemoteVolumeProtocol.GET, RemoteVolumeProtocol.SET, RemoteVolumeProtocol.STATE)) {
            assertFalse(kind.startsWith("WF"))
            val packet = "$kind;sid=session;n=1".toByteArray()
            assertFalse(WfasDatagram.isAudio(packet, packet.size, false))
        }
    }
    @Test fun onlyKnownMulticastBeaconPrecedesMagicAndStillNeedsAuthentication() {
        val key = "fixture-key"
        val text = WfasCrypto.buildMcastBeacon(key, 5, 1000, ByteArray(16) { it.toByte() })
        val packet = text.toByteArray()
        assertFalse(WfasDatagram.isAudio(packet, packet.size, true))
        assertNotNull(WfasCrypto.parseMcastBeacon(key, text, 4))
        assertNull(WfasCrypto.parseMcastBeacon("wrong-key", text, 4))
        assertTrue(WfasDatagram.isAudio(packet, packet.size, false))
        val unrelated = "WFAS_MCAST_ENCevil;epoch=5".toByteArray()
        assertTrue(WfasDatagram.isAudio(unrelated, unrelated.size, true))
    }
    @Test fun incompatibleBinaryVersionsRemainAudioForVersionValidation() {
        for (version in listOf(2, 65, 255)) {
            val packet = byteArrayOf(0x57, 0x46, version.toByte(), 1, 0, 0, 0, 0, 0, 0)
            assertTrue(WfasDatagram.isAudio(packet, packet.size, false))
            assertTrue(WfasDatagram.isAudio(packet, packet.size, true))
        }
        assertFalse(WfasDatagram.isAudio(ByteArray(0), 0, false))
    }
}
