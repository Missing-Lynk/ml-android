package at.websium.ml

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reading a cause out of `rtmp2sink`'s bus text. Every `reason` below is the shape the element
 * actually posts: the cause sits in an inner clause and the wrapper names a transport, so a
 * classifier that read the wrapper would call a rejected stream key a connection failure.
 */
class RestreamFailureTest {

    @Test
    fun aDeniedPublishIsTheStreamKey() {
        assertEquals(
            R.string.stream_error_key_rejected,
            egressFailureText(
                "Failed to connect: gst-resource-error-quark 5 publish denied: " +
                    "Publishing to this stream is not allowed"
            )
        )
    }

    @Test
    fun aRejectedConnectIsTheStreamKey() {
        assertEquals(
            R.string.stream_error_key_rejected,
            egressFailureText(
                "Failed to connect: 'connect' cmd returned 'NetConnection.Connect.Rejected': " +
                    "[ AccessManager.Reject ] : [ authmod=jwt ] : "
            )
        )
    }

    @Test
    fun aFailedAuthenticationIsTheStreamKey() {
        assertEquals(
            R.string.stream_error_key_rejected,
            egressFailureText(
                "Not authorized to connect: authentication failed; wrong credentials?: denied"
            )
        )
    }

    @Test
    fun aKeyAlreadyLiveIsToldApartFromOneThatIsWrong() {
        assertEquals(
            R.string.stream_error_key_in_use,
            egressFailureText(
                "Failed to connect: publish denied; stream already exists: " +
                    "NetStream.Publish.BadName"
            )
        )
    }

    /**
     * Observed on a device against a destination saved as `rtmp://`: only the scheme is checked
     * before arming, so an incomplete URL reaches `rtmp2sink` and is refused there.
     */
    @Test
    fun anIncompleteUrlIsToldApartFromAnUnreachableHost() {
        assertEquals(
            R.string.stream_error_url_incomplete,
            egressFailureText("Failed to connect: Host is not set")
        )
    }

    @Test
    fun aRefusedSocketIsTheDestination() {
        assertEquals(
            R.string.stream_error_unreachable,
            egressFailureText(
                "Connection refused: Could not connect to 127.0.0.1: Connection refused"
            )
        )
    }

    @Test
    fun anUnresolvableHostIsTheDestination() {
        assertEquals(
            R.string.stream_error_unreachable,
            egressFailureText(
                "Failed to connect: Error resolving \u201Cnosuchhost.invalid\u201D: " +
                    "Name or service not known"
            )
        )
    }

    /**
     * The same cause under a different wrapper, which is the whole reason the wrapper is not what
     * gets matched: a refused socket arrives under `Connection refused` and a dead one under
     * `Failed to connect`.
     */
    @Test
    fun anUnansweredHostIsTheDestination() {
        assertEquals(
            R.string.stream_error_unreachable,
            egressFailureText(
                "Failed to connect: Could not connect to 192.0.2.1: Socket I/O timed out"
            )
        )
    }

    @Test
    fun noRouteOffTheGoggleNetworkIsTheDestination() {
        assertEquals(
            R.string.stream_error_unreachable,
            egressFailureText(
                "Failed to connect: g-io-error-quark 42 Network is unreachable"
            )
        )
    }

    @Test
    fun aRejectedCertificateIsNotReportedAsASocketFailure() {
        assertEquals(
            R.string.stream_error_tls,
            egressFailureText(
                "Failed to connect: g-tls-error-quark 3 Unacceptable TLS certificate"
            )
        )
    }

    /**
     * `atl` is Twitch's Atlanta ingest and `atls` contains `tls`, which is why the TLS clause
     * matches whole words.
     */
    @Test
    fun anIngestHostnameContainingTlsIsNotReadAsATlsFailure() {
        assertEquals(
            R.string.stream_error_unreachable,
            egressFailureText(
                "Connection refused: Could not connect to atls.contribute.live-video.net: " +
                    "Connection refused"
            )
        )
    }

    @Test
    fun anUnclassifiedFailureIsGenericRatherThanGStreamerVocabulary() {
        assertEquals(
            R.string.stream_error_failed,
            egressFailureText("restream pipeline failed to build: no element \"flvmux\"")
        )
    }
}
