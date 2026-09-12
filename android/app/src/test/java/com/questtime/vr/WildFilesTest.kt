package com.questtime.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The parser against files it has never seen.
 *
 * The curated fixtures were chosen because they work. These were pulled off the open
 * web without knowing what was in them - three archives, 1995 to 2007 - and the point
 * is the opposite one: that every file is either classified correctly or refused by
 * name, and that nothing produces a confident wrong answer.
 *
 * They are downloaded rather than committed (see reference/README.md), so this whole
 * class skips when they are absent.
 */
class WildFilesTest {

    private val dir = File("../../reference/testdata/wild")

    private fun wild(name: String): ByteArray {
        val f = File(dir, name)
        assumeTrue("wild corpus missing: ${f.path}", f.isFile)
        return f.readBytes()
    }

    /** Cubic files from three different authoring eras, all detected as cubic. */
    @Test
    fun detectsCubicInTheWild() {
        for (name in listOf("p31.mov", "MonaLisa.mov", "chichen-itza.mov")) {
            val d = wild(name)
            assertTrue("$name should be cubic", Qtvr.isCubic(d))
        }
    }

    /**
     * Cylindrical files, and the finding that prompted all of this: every one of
     * them is the legacy rotated form. Not one panorama stored upright turned up.
     */
    @Test
    fun cylindricalFilesInTheWildAreAllStoredRotated() {
        for (name in listOf("apollo12.mov", "taj_mahal.mov")) {
            val d = wild(name)
            assertTrue("$name should not be cubic", !Qtvr.isCubic(d))
            val tracks = MovParser.parseTracks(d)
            val pano = tracks.first { it.handler == "pano" }
            val r = pano.sampleRanges().first()
            val info = PanoInfo.parseV2(
                d.copyOfRange(r.first.toInt(), (r.first + r.second).toInt())
            )!!
            assertTrue("$name should be stored rotated", info.storedRotated)
            assertEquals("$name should be single-node", 1, pano.sampleRanges().size)
        }
    }

    /** An unsupported codec is named, not guessed at. */
    @Test
    fun refusesSorensonByName() {
        val e = runCatching { Qtvr.extract(wild("ff_romscene.mov")) }.exceptionOrNull()
        assertTrue("should be UnsupportedCodec, was $e", e is UnsupportedCodec)
        assertEquals("SVQ1", (e as UnsupportedCodec).fourCC)
    }

    /** A file that only looks like one of ours, because it shares the container. */
    @Test
    fun refusesAnOrdinaryMovie() {
        val e = runCatching { Qtvr.extract(wild("arounder4.mov")) }.exceptionOrNull()
        assertTrue("should be refused, was $e", e is IllegalArgumentException)
        assertTrue(
            "message should say it is an ordinary movie, was: ${e?.message}",
            e?.message?.contains("ordinary movie") == true,
        )
    }
}
