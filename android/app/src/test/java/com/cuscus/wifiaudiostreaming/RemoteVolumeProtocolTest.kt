package com.cuscus.wifiaudiostreaming

import org.junit.Assert.*
import org.junit.Test

class RemoteVolumeProtocolTest {
    private val clientNonce = "1".repeat(32)
    private val authClient = "2".repeat(32)
    private val authServer = "3".repeat(32)
    private class Volume(var value: Int = 50) : RemoteVolumeProtocol.Volume {
        var writes = 0
        override fun getPercent() = value
        override fun setPercent(value: Int) { this.value = value; writes++ }
    }
    private fun pair(volume: Volume = Volume(), key: String = ""): Pair<RemoteVolumeProtocol.Server, RemoteVolumeProtocol.Client> {
        val server = RemoteVolumeProtocol.offer("HELLO_FROM_CLIENT;v=2" + RemoteVolumeProtocol.helloSuffix(clientNonce),
            key, authClient, authServer, volume)!!
        val client = RemoteVolumeProtocol.accept("HELLO_ACK;v=2" + server.ackSuffix(),
            clientNonce, key, authClient, authServer)!!
        return server to client
    }

    @Test fun oldPeerRemainsOptionalAndReconnectReadsRememberedSenderValue() {
        assertNull(RemoteVolumeProtocol.offer("HELLO_FROM_CLIENT;v=2", "", "", "", Volume()))
        assertNull(RemoteVolumeProtocol.accept("HELLO_ACK;v=2", clientNonce, "", "", ""))
        val volume = Volume(83)
        repeat(2) {
            val (server, client) = pair(volume)
            client.query()
            assertNull(client.state().percent)
            assertTrue(client.receive(server.receive(client.nextMessage(0)!!)!!))
            assertEquals(83, client.state().percent)
            assertFalse(client.state().pending)
        }
        assertEquals(0, volume.writes)
    }

    @Test fun remoteSetIsAcknowledgedAuthoritativeAndDuplicateRetryIsInert() {
        val volume = Volume()
        val (server, client) = pair(volume)
        client.receive(server.snapshot())
        client.setPercent(100)
        assertEquals(50, client.state().percent)
        assertTrue(client.state().pending)
        val request = client.nextMessage(0)!!
        val firstAck = server.receive(request)!!
        assertEquals(100, volume.value)
        assertEquals(1, volume.writes)
        assertNull(client.nextMessage(999))
        assertEquals(request, client.nextMessage(1_000))
        assertNotNull(server.receive(request))
        assertEquals(1, volume.writes)
        assertTrue(client.receive(firstAck))
        assertEquals(100, client.state().percent)
        assertFalse(client.state().pending)
    }

    @Test fun oldRequestsAndReorderedAcksCannotOverwriteLatestOperation() {
        val volume = Volume()
        val (server, client) = pair(volume)
        client.setPercent(20)
        val first = client.nextMessage(0)!!
        val staleAck = server.receive(first)!!
        client.setPercent(80)
        val second = client.nextMessage(1)!!
        val secondAck = server.receive(second)!!
        assertNull(server.receive(first))
        assertTrue(client.receive(staleAck))
        assertTrue(client.state().pending) // first operation cannot confirm second.
        assertTrue(client.receive(secondAck))
        assertFalse(client.receive(staleAck))
        assertEquals(80, client.state().percent)
        assertFalse(client.state().pending)
    }

    @Test fun newerPushMayOvertakeAckWithoutRollingBackOrLeavingPending() {
        val volume = Volume()
        val (server, client) = pair(volume)
        client.setPercent(70)
        val delayedAck = server.receive(client.nextMessage(0)!!)!!
        volume.value = 90
        assertTrue(client.receive(server.snapshot()))
        assertTrue(client.state().pending)
        assertTrue(client.receive(delayedAck))
        assertEquals(90, client.state().percent)
        assertFalse(client.state().pending)
    }

    @Test fun timeoutHasFiniteRetriesAndDoesNotPretendToApplyRequestedValue() {
        val (server, client) = pair()
        client.receive(server.snapshot())
        client.setPercent(2)
        assertNotNull(client.nextMessage(0))
        assertNotNull(client.nextMessage(1_000))
        assertNotNull(client.nextMessage(2_000))
        assertNull(client.nextMessage(3_000))
        assertNull(client.nextMessage(99_000))
        assertEquals(50, client.state().percent)
        assertEquals("TIMEOUT", client.state().error)
        assertFalse(client.state().pending)
        client.query()
        client.receive(server.receive(client.nextMessage(100_000)!!)!!)
        assertNull(client.state().error)
    }

    @Test fun authenticatedControlsRejectTamperingReflectionAndWrongKey() {
        val volume = Volume()
        val (server, client) = pair(volume, "shared secret")
        client.setPercent(60)
        val request = client.nextMessage(0)!!
        assertNull(server.receive(request.replace("pct=60", "pct=61")))
        assertNull(server.receive(request.substringBefore(";mac=")))
        val response = server.receive(request)!!
        assertFalse(client.receive(response.replace("pct=60", "pct=61")))
        assertNull(server.receive(response))
        assertTrue(client.receive(response))
        assertNull(RemoteVolumeProtocol.accept("HELLO_ACK;v=2" + server.ackSuffix(),
            clientNonce, "wrong secret", authClient, authServer))
        assertNull(RemoteVolumeProtocol.accept("HELLO_ACK;v=2" + server.ackSuffix(),
            "4".repeat(32), "shared secret", authClient, authServer))
        assertEquals(1, volume.writes)
    }

    @Test fun freshSessionRejectsCapturedRequestsEvenWithoutKey() {
        val volume = Volume()
        val (firstServer, firstClient) = pair(volume)
        firstClient.setPercent(0)
        val captured = firstClient.nextMessage(0)!!
        val (nextServer, nextClient) = pair(volume)
        assertNull(nextServer.receive(captured))
        assertFalse(nextClient.receive(firstServer.snapshot()))
        assertEquals(50, volume.value)
    }

    @Test fun malformedOrOutOfRangeControlsNeverReachVolumeSetter() {
        val volume = Volume()
        val (server, client) = pair(volume)
        client.setPercent(100)
        val valid = client.nextMessage(0)!!
        assertNull(server.receive(valid.replace("pct=100", "pct=101")))
        assertNull(server.receive(valid.replace("pct=100", "pct=+1")))
        assertNull(server.receive(valid + ";pct=0"))
        assertNull(server.receive(valid.replace("n=1", "n=-1")))
        assertNull(server.receive(valid.replace("n=1", "n=9223372036854775808")))
        assertNull(server.receive(valid + ";extra=1"))
        assertEquals(0, volume.writes)
        assertNotNull(server.receive(valid)) // malformed packets do not consume a sequence.
        assertEquals(1, volume.writes)
    }
}
