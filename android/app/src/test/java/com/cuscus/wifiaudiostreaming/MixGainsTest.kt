package com.cuscus.wifiaudiostreaming

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class MixGainsTest {
    @Test fun singleSourcePreservesGainAndMultipleSourcesStayWithinUnity() {
        assertEquals(1f, MixGains.targets(mapOf("a" to 1f), 1f)["a"]!!, 0f)
        val many = MixGains.targets(mapOf("a" to 1f, "b" to 1f, "c" to .5f), .8f)
        assertEquals(.8f, many.values.sum(), .0001f)
        assertEquals(2f, many["a"]!! / many["c"]!!, .0001f)
        assertEquals(0f, MixGains.targets(mapOf("muted" to 0f), 1f)["muted"]!!, 0f)
    }
    @Test fun subnetSelectionSupportsLanWithoutInternetAndIpv6() {
        fun ip(s: String) = InetAddress.getByName(s)
        assertTrue(DestinationNetwork.sameSubnet(ip("192.168.49.2"), ip("192.168.49.1"), 24))
        assertFalse(DestinationNetwork.sameSubnet(ip("192.168.10.2"), ip("192.168.49.1"), 24))
        assertTrue(DestinationNetwork.sameSubnet(ip("fd00::2"), ip("fd00::1"), 64))
        assertFalse(DestinationNetwork.sameSubnet(ip("fd01::2"), ip("fd00::1"), 64))
    }
}
