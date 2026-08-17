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
class DiagTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var log: File

    @Before
    fun setUp() {
        log = File(temp.root, "diag.log")
        Diag.init(log)
    }

    @Test
    fun initWritesALaunchMarker() {
        assertTrue(log.exists())
        assertTrue(log.readText().contains("----- launched -----"))
    }

    @Test
    fun logAppendsTaggedLines() {
        Diag.log("conn", "RTSP up, connecting")
        Diag.log("state", "SEARCHING -> CONNECTING")

        val lines = log.readLines()
        assertTrue(lines[lines.size - 2].endsWith("conn: RTSP up, connecting"))
        assertTrue(lines.last().endsWith("state: SEARCHING -> CONNECTING"))
    }

    @Test
    fun readReturnsTheContents() {
        Diag.log("gst", "video decoder: amcvideodec-omxhevcdecoder (hardware)")
        assertTrue(Diag.read()!!.contains("amcvideodec"))
    }

    @Test
    fun readIsNullWhenTheFileIsMissing() {
        log.delete()
        assertNull(Diag.read())
    }

    @Test
    fun readIsNullWhenTheFileIsEmpty() {
        // clear() truncates rather than deletes, and an empty log must still read as "nothing
        // to show" so the Diagnostics screen shows its placeholder instead of a blank page
        Diag.clear()
        assertNull(Diag.read())
    }

    @Test
    fun clearEmptiesTheFileWithoutRemovingIt() {
        Diag.log("app", "something")
        Diag.clear()
        assertTrue(log.exists())
        assertEquals(0L, log.length())
    }

    @Test
    fun trimsToTheTailOnceOverTheCap() {
        // an oversized log with a recognisable head and tail
        val head = "HEAD".repeat(4)
        val filler = "x".repeat((Diag.MAX_BYTES + 1024).toInt())
        log.writeText(head + filler)
        assertTrue(log.length() > Diag.MAX_BYTES)

        Diag.log("app", "after the trim")

        val text = log.readText()
        assertTrue("must drop below the cap", log.length() < Diag.MAX_BYTES)
        assertTrue("keeps roughly the newest half", log.length() > Diag.MAX_BYTES / 4)
        assertTrue("the oldest lines are gone", !text.contains("HEAD"))
        assertTrue("the newest line survives", text.trimEnd().endsWith("app: after the trim"))
    }

    @Test
    fun trimHappensOnlyOnceTheCapIsExceeded() {
        val under = "y".repeat((Diag.MAX_BYTES / 2).toInt())
        log.writeText(under)

        Diag.log("app", "still small")

        assertTrue(log.readText().startsWith("yyy"))
    }

    @Test
    fun loggingBeforeInitIsANoOp() {
        // Diag is a singleton, so a screen reached before MainActivity ran must not crash
        Diag.reset()
        Diag.log("app", "no file yet")
        assertNull(Diag.read())
    }

    @Test
    fun anUnwritableFileDoesNotThrow() {
        // diagnostics must never take the app down with them
        Diag.init(File(temp.root, "no-such-dir/diag.log"))
        Diag.log("app", "into the void")
        assertNotNull(Diag.read() ?: "")
    }
}
