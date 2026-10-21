package com.cuscus.wifiaudiostreaming

import org.junit.Assert.*
import org.junit.Test

class ManualEndpointTest {
    @Test fun explicitPortsAndIpv6RemainDistinctSources() {
        assertEquals(19090, ManualEndpoint.parse("192.168.1.2:19090", 9090).port)
        assertEquals("fd00::2", ManualEndpoint.parse("[fd00::2]:19091", 9090).host)
        assertFalse(ManualEndpoint.parse("fd00::2", 9090).explicitPort)
    }
    @Test(expected = IllegalArgumentException::class) fun invalidPortIsNotSilentlyReplaced() {
        ManualEndpoint.parse("192.168.1.2:wrong", 9090)
    }
    @Test(expected = IllegalArgumentException::class) fun namesCannotTriggerDnsFromValidation() {
        ManualEndpoint.parse("example.com:9090", 9090)
    }
}
