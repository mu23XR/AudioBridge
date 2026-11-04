package com.cuscus.wifiaudiostreaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerEndpointPolicyTest {
    private fun verdict(
        sameAddress: Boolean = true,
        multicast: Boolean = false,
        expectedPort: Int = 9090,
        sourcePort: Int = 9090,
        learnedPort: Int = -1,
        handshakeReply: Boolean = false
    ) = PeerEndpointPolicy.verdict(sameAddress, multicast, expectedPort, sourcePort, learnedPort, handshakeReply)

    @Test fun aDifferentAddressIsNeverThePeer() {
        assertEquals(PeerEndpointPolicy.Verdict.REJECT,
            verdict(sameAddress = false, handshakeReply = true))
    }

    @Test fun theAdvertisedPortIsAcceptedWithoutInspectingThePayload() {
        assertEquals(PeerEndpointPolicy.Verdict.ACCEPT, verdict())
    }

    @Test fun multicastTrustsTheAddressBecauseSendersShareOnePort() {
        assertEquals(PeerEndpointPolicy.Verdict.ACCEPT,
            verdict(multicast = true, expectedPort = 9090, sourcePort = 54561))
    }

    @Test fun aVpnRewrittenPortIsAdoptedOnlyForAHandshakeReply() {
        // A userspace VPN re-originated the sender's replies from another port, so
        // the HELLO_ACK never matched the advertised port and the client spent the
        // whole session in NO_ACK. A handshake reply may re-point the session.
        assertEquals(PeerEndpointPolicy.Verdict.LEARN,
            verdict(sourcePort = 54561, handshakeReply = true))
        // Audio from an unknown port stays rejected.
        assertEquals(PeerEndpointPolicy.Verdict.REJECT,
            verdict(sourcePort = 54561, handshakeReply = false))
    }

    @Test fun theLearnedPortAcceptsAudioWithoutReinspectingThePayload() {
        assertEquals(PeerEndpointPolicy.Verdict.ACCEPT,
            verdict(sourcePort = 54561, learnedPort = 54561))
    }

    @Test fun theAdvertisedPortOutranksAStaleLearnedPort() {
        // A session that learned a rewritten port must still accept the advertised
        // one, so a learned port can never displace the real endpoint.
        assertEquals(PeerEndpointPolicy.Verdict.ACCEPT,
            verdict(expectedPort = 9090, sourcePort = 9090, learnedPort = 54561))
    }

    @Test fun anUnrelatedPortFromTheSameAddressIsStillRejected() {
        assertEquals(PeerEndpointPolicy.Verdict.REJECT,
            verdict(sourcePort = 54562, learnedPort = 54561))
    }

    @Test fun anotherHandshakePortCannotReplaceAnAlreadyLearnedPort() {
        assertEquals(PeerEndpointPolicy.Verdict.REJECT,
            verdict(sourcePort = 54562, learnedPort = 54561, handshakeReply = true))
    }

    @Test fun onlyHandshakeRepliesCanRebindTheSession() {
        assertTrue(PeerEndpointPolicy.isHandshakeReply("HELLO_ACK;v=2"))
        assertTrue(PeerEndpointPolicy.isHandshakeReply("HELLO_ACK;v=2;rvol=1;rvsid=7"))
        assertTrue(PeerEndpointPolicy.isHandshakeReply("WFAS_INCOMPATIBLE;v=1"))
        assertTrue(PeerEndpointPolicy.isHandshakeReply("WFAS_BUSY"))
        assertTrue(PeerEndpointPolicy.isHandshakeReply("WFAS_PENDING;v=2"))
        assertTrue(PeerEndpointPolicy.isHandshakeReply("WFAS_UNAUTHORIZED"))
        assertTrue(PeerEndpointPolicy.isHandshakeReply("WFAS_AUTH_REQUIRED;v=2;snonce=abc"))
    }

    @Test fun keepaliveAndVolumeTrafficCannotRebindTheSession() {
        // Three or four arbitrary bytes must not be enough for another socket on
        // the same host to tear the session down or starve the real source.
        assertFalse(PeerEndpointPolicy.isHandshakeReply("PING"))
        assertFalse(PeerEndpointPolicy.isHandshakeReply("PONG"))
        assertFalse(PeerEndpointPolicy.isHandshakeReply("BYE"))
        assertFalse(PeerEndpointPolicy.isHandshakeReply("RVOL_STATE;sid=1;q=0;rev=2;pct=50"))
        assertFalse(PeerEndpointPolicy.isHandshakeReply("RVOL_GET;sid=1"))
    }

    @Test fun anAudioFrameIsNeverAHandshakeReply() {
        // 0x57 0x46 0x02 ... is the audio magic plus the encrypted flag.
        assertFalse(PeerEndpointPolicy.isHandshakeReply(String(byteArrayOf(0x57, 0x46, 0x02, 0x01), Charsets.US_ASCII)))
    }
}
