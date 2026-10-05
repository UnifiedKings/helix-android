package com.example.helixapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HelixClientTest {
    @Test
    fun readTimeoutHeaderAcceptsSensibleSeconds() {
        assertEquals(60, HelixClient.readTimeoutOverride("60"))
        assertEquals(60, HelixClient.readTimeoutOverride(" 60 "))
        assertNull(HelixClient.readTimeoutOverride(null))
        assertNull(HelixClient.readTimeoutOverride("soon"))
        assertNull(HelixClient.readTimeoutOverride("0"))
        assertNull(HelixClient.readTimeoutOverride("100000"))
    }
}
