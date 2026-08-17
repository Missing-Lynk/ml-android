package at.websium.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the app decides which of the phone's networks is the goggle: it derives a /24 prefix
 * from the configured stream host and matches interface addresses against it.
 *
 * [wifiOnTheSameSubnetAlsoMatches] pins CURRENT behaviour, not desired behaviour: the match
 * is address-only, so a phone whose WiFi is on 192.168.3.0/24 selects WiFi. When the
 * transport/interface filter lands, that test flips to asserting the goggle interface wins.
 */
class SubnetMatchingTest {

    // ---- subnetPrefix ----

    @Test
    fun derivesThePrefixFromADottedAddress() {
        assertEquals("192.168.3.", subnetPrefix("192.168.3.101"))
    }

    @Test
    fun derivesThePrefixFromAHostname() {
        // not useful for matching, but it must not throw or return something surprising
        assertEquals("goggle.local.", subnetPrefix("goggle.local.lan"))
    }

    @Test
    fun rejectsAHostWithNoDot() {
        assertNull(subnetPrefix("goggle"))
    }

    @Test
    fun rejectsAHostStartingWithADot() {
        // lastIndexOf == 0 would otherwise yield an empty prefix that matches everything
        assertNull(subnetPrefix(".101"))
    }

    @Test
    fun rejectsAnEmptyHost() {
        assertNull(subnetPrefix(""))
    }

    @Test
    fun rejectsANullHost() {
        assertNull(subnetPrefix(null))
    }

    @Test
    fun rejectsAnIpv6Literal() {
        assertNull(subnetPrefix("fe80::1"))
    }

    // ---- addressesMatch ----

    @Test
    fun matchesAnAddressInTheSubnet() {
        assertTrue(addressesMatch("192.168.3.", listOf("192.168.3.222")))
    }

    @Test
    fun matchesWhenOnlyOneOfSeveralAddressesIsInTheSubnet() {
        assertTrue(addressesMatch("192.168.3.", listOf("10.0.0.5", "fe80::1", "192.168.3.222")))
    }

    @Test
    fun doesNotMatchANeighbouringSubnet() {
        // the trailing dot in the prefix is what stops 192.168.3. matching 192.168.30.x
        assertFalse(addressesMatch("192.168.3.", listOf("192.168.30.5")))
    }

    @Test
    fun doesNotMatchADifferentSubnet() {
        assertFalse(addressesMatch("192.168.3.", listOf("192.168.1.50")))
    }

    @Test
    fun doesNotMatchAnEmptyInterface() {
        assertFalse(addressesMatch("192.168.3.", emptyList()))
    }

    @Test
    fun toleratesANullAddress() {
        // LinkAddress.getAddress().getHostAddress() is platform-nullable
        assertFalse(addressesMatch("192.168.3.", listOf(null)))
    }

    @Test
    fun wifiOnTheSameSubnetAlsoMatches() {
        // CURRENT behaviour, and the bug: nothing here distinguishes a USB-ethernet gadget
        // from a home router that happens to use the same private range.
        val homeWifiAddresses = listOf("192.168.3.42")
        assertTrue(addressesMatch("192.168.3.", homeWifiAddresses))
    }
}
