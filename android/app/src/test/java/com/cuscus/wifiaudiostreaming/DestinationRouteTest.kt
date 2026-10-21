package com.cuscus.wifiaudiostreaming

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class DestinationRouteTest {
    private fun route(name: String, prefix: Int, direct: Boolean = true,
                      vpn: Boolean = false, managed: Boolean = true) =
        DestinationNetwork.Candidate(name, prefix, direct, vpn, managed)

    @Test fun sharingInterfaceBeatsBroaderManagedRoute() {
        assertEquals("sharing", DestinationNetwork.preferred(listOf(
            route("wifi", 16, direct = false), route("sharing", 24, managed = false))))
        assertEquals("sharing", DestinationNetwork.preferred(listOf(
            route("wifi", 16), route("sharing", 24, managed = false))))
    }

    @Test fun identicalManagedPathWinsButLocalTrafficAvoidsVpn() {
        assertEquals("wifi", DestinationNetwork.preferred(listOf(
            route("raw-wifi", 24, managed = false), route("wifi", 24), route("vpn", 32, vpn = true))))
        assertEquals("vpn", DestinationNetwork.preferred(listOf(route("vpn", 24, vpn = true))))
        assertNull(DestinationNetwork.preferred(listOf(route("default", 0))))
    }

    @Test fun specificGatewayRouteBeatsBroaderDirectSubnet() {
        assertEquals("gateway", DestinationNetwork.preferred(listOf(
            route("local", 24), route("gateway", 32, direct = false))))
    }

    @Test fun privateSharingSubnetMatchesOnlyItsOwnPrefix() {
        val local = InetAddress.getByName("192.168.49.1")
        assertTrue(DestinationNetwork.sameSubnet(InetAddress.getByName("192.168.49.2"), local, 24))
        assertFalse(DestinationNetwork.sameSubnet(InetAddress.getByName("192.168.10.2"), local, 24))
        assertFalse(DestinationNetwork.sameSubnet(InetAddress.getByName("::1"), local, 24))
    }
}
