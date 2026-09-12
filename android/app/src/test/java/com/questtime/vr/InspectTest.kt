package com.questtime.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The compatibility checker the upload page reports with.
 *
 * Worth testing properly: it is the only part of the app whose entire output is
 * words a person reads and acts on, so "it said something" is not the bar - it has
 * to say the right thing about each kind of file.
 */
class InspectTest {

    private val data = File("../../reference/testdata")

    private fun fixture(name: String): ByteArray {
        val f = File(data, name)
        assumeTrue("fixture missing: ${f.path}", f.isFile)
        return f.readBytes()
    }

    @Test
    fun acceptsTheNineteenNinetyFiveCylinder() {
        val v = Qtvr.inspect(fixture("eciqtvr_hr1.mov"))
        assertTrue("should open: ${v.summary} / ${v.detail}", v.opens)
        assertTrue(v.summary, v.summary.contains("1.0"))
        assertTrue(v.summary, v.summary.contains("cylindrical"))
        assertTrue(v.summary, v.summary.contains("2496x768"))
        assertTrue(v.summary, v.summary.contains("cvid"))
        assertEquals("", v.detail)
        assertTrue(v.worthKeeping)
    }

    @Test
    fun acceptsTheVersionTwoCylinder() {
        val v = Qtvr.inspect(fixture("chapel_hi.mov"))
        assertTrue("should open: ${v.summary} / ${v.detail}", v.opens)
        assertTrue(v.summary, v.summary.contains("2.x"))
        assertTrue(v.summary, v.summary.contains("cylindrical"))
        assertTrue(v.summary, v.summary.contains("8832x1508"))
    }

    @Test
    fun acceptsTheCube() {
        val v = Qtvr.inspect(fixture("street-1.mov"))
        assertTrue("should open: ${v.summary} / ${v.detail}", v.opens)
        assertTrue(v.summary, v.summary.contains("cubic"))
        assertTrue(v.summary, v.summary.contains("696"))
    }

    /**
     * The failure a browser upload will actually hit. The media arrives intact and
     * only the header is missing, so the message has to say that rather than
     * "not a QuickTime file" - the file is fine, the transfer could not carry it all.
     */
    @Test
    fun headerlessFileBlamesTheResourceFork() {
        val whole = fixture("eciqtvr_hr1.mov")
        val moovAt = topLevelOffsetOf(whole, "moov")
        assertTrue("expected a top-level moov in the fixture", moovAt > 0)
        val dataForkOnly = whole.copyOfRange(0, moovAt)

        val v = Qtvr.inspect(dataForkOnly)
        assertFalse(v.opens)
        assertFalse("must not be stored", v.worthKeeping)
        assertTrue(v.summary, v.summary.contains("Classic Mac", ignoreCase = true))
        assertTrue(v.detail, v.detail.contains("resource fork"))
        assertTrue(v.detail, v.detail.contains("flatten", ignoreCase = true))
    }

    @Test
    fun garbageIsRejectedPlainly() {
        val v = Qtvr.inspect(ByteArray(64) { 0x41 })
        assertFalse(v.opens)
        assertTrue(v.summary, v.summary.contains("Not a QuickTime file"))
    }

    @Test
    fun emptyInputDoesNotCrash() {
        val v = Qtvr.inspect(ByteArray(0))
        assertFalse(v.opens)
    }

    /** Walk top-level atoms rather than searching for the bytes, which can collide. */
    private fun topLevelOffsetOf(d: ByteArray, type: String): Int {
        var pos = 0
        while (pos + 8 <= d.size) {
            val size = ((d[pos].toLong() and 0xFF) shl 24) or
                ((d[pos + 1].toLong() and 0xFF) shl 16) or
                ((d[pos + 2].toLong() and 0xFF) shl 8) or (d[pos + 3].toLong() and 0xFF)
            if (String(d, pos + 4, 4, Charsets.ISO_8859_1) == type) return pos
            if (size < 8 || pos + size > d.size) return -1
            pos += size.toInt()
        }
        return -1
    }
}
