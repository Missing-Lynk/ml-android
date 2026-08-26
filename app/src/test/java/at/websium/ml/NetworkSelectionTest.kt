package at.websium.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Choosing which of the phone's networks carries the goggle. The trap this guards is that
 * 192.168.x.x is the range home routers hand out, so an address match alone can select WiFi
 * and take every socket away from the goggle.
 */
class NetworkSelectionTest {

    private val prefix = "192.168.3."

    private val goggle = NetworkFacts(
        ethernet = true,
        interfaceName = "eth0",
        addresses = listOf("192.168.3.123"),
    )
    // deliberately on the same subnet as the goggle
    private val homeWifi = NetworkFacts(
        wifi = true,
        interfaceName = "wlan0",
        addresses = listOf("192.168.3.42"),
    )
    private val otherWifi = NetworkFacts(
        wifi = true,
        interfaceName = "wlan0",
        addresses = listOf("192.168.1.42"),
    )
    private val cellular = NetworkFacts(
        cellular = true,
        interfaceName = "rmnet_data0",
        addresses = listOf("10.14.2.9"),
    )

    private fun pick(vararg candidates: NetworkFacts) = pickGoggle(prefix, candidates.toList()) { it }

    // looksLikeGadget
    @Test
    fun theEthernetTransportIdentifiesAGadget() {
        assertTrue(looksLikeGadget(NetworkFacts(ethernet = true)))
    }

    @Test
    fun gadgetInterfaceNamesAreAccepted() {
        // a CDC-ECM gadget is not guaranteed to report a transport, so the name is a fallback
        for (name in listOf("usb0", "eth0", "eth1", "rndis0", "ncm0", "usb")) {
            assertTrue(name, looksLikeGadget(NetworkFacts(interfaceName = name)))
        }
    }

    @Test
    fun otherInterfaceNamesAreNot() {
        for (name in listOf("wlan0", "rmnet_data0", "dummy0", "lo", "ethernet", "usbfoo")) {
            assertFalse(name, looksLikeGadget(NetworkFacts(interfaceName = name)))
        }
    }

    @Test
    fun anUnnamedInterfaceIsNotAGadgetOnNameAlone() {
        assertFalse(looksLikeGadget(NetworkFacts(interfaceName = null)))
    }

    // selection
    @Test
    fun picksTheGoggleWhenItIsTheOnlyNetwork() {
        assertEquals(goggle, pick(goggle))
    }

    @Test
    fun homeWifiOnTheSameSubnetIsRejected() {
        // the bug this change fixes: an address match alone used to select this
        assertNull(pick(homeWifi))
    }

    @Test
    fun theGoggleWinsAgainstWifiOnTheSameSubnet() {
        assertEquals(goggle, pick(homeWifi, goggle))
        assertEquals(goggle, pick(goggle, homeWifi))
    }

    @Test
    fun cellularOnTheSameSubnetIsRejected() {
        val cellularOnSubnet = cellular.copy(addresses = listOf("192.168.3.7"))
        assertNull(pick(cellularOnSubnet))
    }

    @Test
    fun networksOffTheSubnetAreIgnored() {
        assertNull(pick(otherWifi, cellular))
    }

    @Test
    fun aGadgetOffTheSubnetIsIgnored() {
        // a USB gadget is not automatically the goggle; the configured URL decides
        assertNull(pick(goggle.copy(addresses = listOf("10.0.0.2"))))
    }

    @Test
    fun nothingAttachedYieldsNothing() {
        assertNull(pick())
    }

    @Test
    fun anUnidentifiableNetworkOnTheSubnetIsTakenAsALastResort() {
        // no transport reported and an unfamiliar interface name: not provably WiFi or
        // cellular, so an unusual gadget still connects rather than the app sitting blind
        val unknown = NetworkFacts(interfaceName = "ax0", addresses = listOf("192.168.3.9"))
        assertEquals(unknown, pick(unknown))
    }

    @Test
    fun aRealGadgetBeatsTheLastResort() {
        val unknown = NetworkFacts(interfaceName = "ax0", addresses = listOf("192.168.3.9"))
        assertEquals(goggle, pick(unknown, goggle))
    }

    @Test
    fun theLastResortStillExcludesWifi() {
        // an unidentifiable candidate must not open the door to a WiFi network beside it
        val unknown = NetworkFacts(interfaceName = "ax0", addresses = listOf("192.168.3.9"))
        assertEquals(unknown, pick(homeWifi, unknown))
    }

    @Test
    fun aNetworkWithNoAddressesIsIgnored() {
        assertNull(pick(NetworkFacts(ethernet = true, interfaceName = "eth0")))
    }

    @Test
    fun selectionFollowsTheConfiguredSubnet() {
        // pointing the app at a different goggle address moves the whole match with it
        val onOneOhOne = goggle.copy(addresses = listOf("192.168.4.50"))
        assertNull(pickGoggle("192.168.3.", listOf(onOneOhOne)) { it })
        assertEquals(onOneOhOne, pickGoggle("192.168.4.", listOf(onOneOhOne)) { it })
    }
}
