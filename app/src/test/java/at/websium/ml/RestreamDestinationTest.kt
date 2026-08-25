package at.websium.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The destination string and what may be shown of it. The trap here is that the value ends in a
 * credential: Diagnostics has a Share button, so a stream key that reaches the log lets whoever
 * reads the bug report broadcast to the reporter's channel.
 */
class RestreamDestinationTest {

    @Test
    fun `masks the stream key`() {
        assertEquals(
            "rtmp://live.twitch.tv/app/***",
            redactStreamKey("rtmp://live.twitch.tv/app/live_123456_abcdefSECRET")
        )
    }

    @Test
    fun `masks a key carrying the inspector query`() {
        assertEquals(
            "rtmp://live.twitch.tv/app/***",
            redactStreamKey("rtmp://live.twitch.tv/app/live_123?bandwidthtest=true")
        )
    }

    @Test
    fun `leaves a host-only url alone rather than masking the host`() {
        assertEquals("rtmp://live.twitch.tv", redactStreamKey("rtmp://live.twitch.tv"))
    }

    @Test
    fun `leaves a trailing slash alone, since nothing follows it to hide`() {
        assertEquals("rtmp://live.twitch.tv/app/", redactStreamKey("rtmp://live.twitch.tv/app/"))
    }

    @Test
    fun `accepts rtmp and rtmps`() {
        assertTrue(isRestreamUrl("rtmp://live.twitch.tv/app/key"))
        assertTrue(isRestreamUrl("rtmps://a.rtmps.youtube.com/live2/key"))
    }

    @Test
    fun `rejects an unset or non-rtmp destination`() {
        assertFalse(isRestreamUrl(null))
        assertFalse(isRestreamUrl(""))
        assertFalse(isRestreamUrl("https://example.com/"))
        assertFalse(isRestreamUrl("rtsp://192.168.3.101:554/venc8/stream"))
    }

    @Test
    fun `h264 goes anywhere`() {
        assertTrue(isCodecAccepted("rtmp://live.twitch.tv/app/key", "H264"))
        assertTrue(isCodecAccepted("rtmps://a.rtmps.youtube.com/live2/key", "H264"))
    }

    @Test
    fun `h265 is refused for twitch and kick`() {
        assertFalse(isCodecAccepted("rtmp://live.twitch.tv/app/key", "H265"))
        assertFalse(isCodecAccepted("rtmp://kick.com/live/key", "H265"))
    }

    /**
     * The regional ingest names are what Twitch's dashboard actually hands out; twitch.tv appears
     * only in its documentation. Matching the documented name alone passed every real URL.
     */
    @Test
    fun `h265 is refused for twitch's real ingest hostnames`() {
        assertFalse(isCodecAccepted("rtmp://sfo.contribute.live-video.net/app/key", "H265"))
        assertFalse(
            isCodecAccepted("rtmps://fra02.contribute.live-video.net/app/key:1935", "H265")
        )
    }

    @Test
    fun `a port does not hide the host`() {
        assertFalse(isCodecAccepted("rtmp://live.twitch.tv:1935/app/key", "H265"))
    }

    @Test
    fun `h265 is allowed to youtube and to an unrecognised self-hosted server`() {
        assertTrue(isCodecAccepted("rtmps://a.rtmps.youtube.com/live2/key", "H265"))
        assertTrue(isCodecAccepted("rtmp://192.168.1.100:1935/live/test", "H265"))
    }
}
