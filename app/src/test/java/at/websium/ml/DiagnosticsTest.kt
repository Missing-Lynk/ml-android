package at.websium.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The diagnostics log is what a user is asked to share after a bad session, so the two
 * things that matter are that it keeps the most recent lines and that it can never grow
 * without bound or throw into the caller.
 */
class DiagnosticsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var log: File

    @Before
    fun setUp() {
        log = File(temp.root, "diag.log")
        Diagnostics.init(log)
    }

    @Test
    fun initWritesALaunchMarker() {
        assertTrue(log.exists())
        assertTrue(log.readText().contains("----- launched -----"))
    }

    @Test
    fun logAppendsTaggedLines() {
        Diagnostics.log("conn", "RTSP up, connecting")
        Diagnostics.log("state", "SEARCHING -> CONNECTING")

        val lines = log.readLines()
        assertTrue(lines[lines.size - 2].endsWith("conn: RTSP up, connecting"))
        assertTrue(lines.last().endsWith("state: SEARCHING -> CONNECTING"))
    }

    @Test
    fun readReturnsTheContents() {
        Diagnostics.log("gst", "video decoder: amcvideodec-omxhevcdecoder (hardware)")
        assertTrue(Diagnostics.read()!!.contains("amcvideodec"))
    }

    @Test
    fun readIsNullWhenTheFileIsMissing() {
        log.delete()
        assertNull(Diagnostics.read())
    }

    @Test
    fun readIsNullWhenTheFileIsEmpty() {
        // clear() truncates rather than deletes, and an empty log must still read as "nothing
        // to show" so the Diagnostics screen shows its placeholder instead of a blank page
        Diagnostics.clear()
        assertNull(Diagnostics.read())
    }

    @Test
    fun clearEmptiesTheFileWithoutRemovingIt() {
        Diagnostics.log("app", "something")
        Diagnostics.clear()
        assertTrue(log.exists())
        assertEquals(0L, log.length())
    }

    @Test
    fun trimsToTheTailOnceOverTheCap() {
        // an oversized log with a recognisable head and tail
        val head = "HEAD".repeat(4)
        val filler = "x".repeat((Diagnostics.MAX_BYTES + 1024).toInt())
        log.writeText(head + filler)
        assertTrue(log.length() > Diagnostics.MAX_BYTES)

        Diagnostics.log("app", "after the trim")

        val text = log.readText()
        assertTrue("must drop below the cap", log.length() < Diagnostics.MAX_BYTES)
        assertTrue("keeps roughly the newest half", log.length() > Diagnostics.MAX_BYTES / 4)
        assertTrue("the oldest lines are gone", !text.contains("HEAD"))
        assertTrue("the newest line survives", text.trimEnd().endsWith("app: after the trim"))
    }

    @Test
    fun trimHappensOnlyOnceTheCapIsExceeded() {
        val under = "y".repeat((Diagnostics.MAX_BYTES / 2).toInt())
        log.writeText(under)

        Diagnostics.log("app", "still small")

        assertTrue(log.readText().startsWith("yyy"))
    }

    @Test
    fun loggingBeforeInitIsANoOp() {
        // Diag is a singleton, so a screen reached before MainActivity ran must not crash
        Diagnostics.reset()
        Diagnostics.log("app", "no file yet")
        assertNull(Diagnostics.read())
    }

    @Test
    fun anUnwritableFileDoesNotThrow() {
        // diagnostics must never take the app down with them
        Diagnostics.init(File(temp.root, "no-such-dir/diag.log"))
        Diagnostics.log("app", "into the void")
        assertNotNull(Diagnostics.read() ?: "")
    }
}
