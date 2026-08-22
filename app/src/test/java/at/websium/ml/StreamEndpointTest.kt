package at.websium.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parsing the configured URL. Everything the link needs from it is derived here, so these are
 * the cases that decide whether a probe reaches the goggle at all.
 */
class StreamEndpointTest {

    @Test
    fun parsesTheShippedDefault() {
        val endpoint = StreamEndpoint.parse("rtsp://192.168.3.101:554/venc8/stream")
        assertEquals(
            StreamEndpoint(
                url = "rtsp://192.168.3.101:554/venc8/stream",
                host = "192.168.3.101",
                port = 554,
                subnetPrefix = "192.168.3.",
            ),
            endpoint,
        )
    }

    @Test
    fun fallsBackToTheRtspPortWhenTheUrlNamesNone() {
        assertEquals(554, StreamEndpoint.parse("rtsp://192.168.3.101/venc8/stream")?.port)
    }

    @Test
    fun keepsAnExplicitPort() {
        assertEquals(8554, StreamEndpoint.parse("rtsp://192.168.3.101:8554/stream")?.port)
    }

    @Test
    fun keepsTheUrlVerbatimForThePlayer() {
        val url = "rtsp://192.168.3.101:554/venc8/stream"
        assertEquals(url, StreamEndpoint.parse(url)?.url)
    }

    @Test
    fun aDottedHostnameYieldsAPrefixNoInterfaceCanMatch() {
        // subnetPrefix is textual, so a name gets a prefix; no link address starts with it
        val endpoint = StreamEndpoint.parse("rtsp://goggle.local/stream")
        assertEquals("goggle.local", endpoint?.host)
        assertEquals(554, endpoint?.port)
        assertEquals("goggle.", endpoint?.subnetPrefix)
    }

    @Test
    fun aBareLabelOffersNoSubnetEither() {
        val endpoint = StreamEndpoint.parse("rtsp://goggle/stream")
        assertEquals("goggle", endpoint?.host)
        assertNull(endpoint?.subnetPrefix)
    }

    @Test
    fun anIpv6LiteralParsesButOffersNoSubnet() {
        val endpoint = StreamEndpoint.parse("rtsp://[fe80::1]:554/stream")
        assertNull(endpoint?.subnetPrefix)
    }

    @Test
    fun aNeighbouringSubnetGetsItsOwnPrefix() {
        assertEquals("192.168.30.", StreamEndpoint.parse("rtsp://192.168.30.5/s")?.subnetPrefix)
    }

    @Test
    fun rejectsAUrlWithNoHost() {
        assertNull(StreamEndpoint.parse("rtsp:///venc8/stream"))
    }

    @Test
    fun rejectsAnEmptyUrl() {
        assertNull(StreamEndpoint.parse(""))
    }

    @Test
    fun rejectsSomethingThatIsNotAUrl() {
        assertNull(StreamEndpoint.parse("192.168.3.101"))
    }

    @Test
    fun rejectsAUrlWithAnIllegalCharacter() {
        assertNull(StreamEndpoint.parse("rtsp://192.168.3.101 :554/stream"))
    }
}
