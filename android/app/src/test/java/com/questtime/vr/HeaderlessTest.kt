package com.questtime.vr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Rebuilding a panorama from media alone.
 *
 * The test that matters is the first one: the same file, decoded with its real
 * header and decoded without it, must produce identical pixels. Anything less and
 * this is a decoder that silently produces wrong output, which the project does not
 * ship.
 *
 * The rest guard the thing that cannot be recovered - the node count - because a
 * multi-node scene rebuilt naively looks like a panorama and is not.
 */
class HeaderlessTest {

    private val imports = File("../../Imports")

    private fun forked(name: String): ByteArray? {
        val f = File(imports, name)
        return if (f.isFile) f.readBytes() else null
    }

    /** The data fork of a classic file: media, no header. */
    private fun headerless(name: String): ByteArray {
        val d = forked(name)
        assumeTrue("Imports/$name missing", d != null)
        return d!!
    }

    /** The same file with its resource-fork header put back, as a zip upload does. */
    private fun whole(name: String): ByteArray {
        val f = File(imports, "$name/..namedfork/rsrc")
        assumeTrue("no resource fork for $name", f.isFile)
        val rsrc = f.readBytes()
        val moov = AppleZip.findResource(rsrc, "moov")
        assumeTrue("no moov resource in $name", moov != null)
        return headerless(name) + moov!!
    }

    @Test
    fun aRebuiltPanoramaIsIdenticalToTheRealOne() {
        val name = "White House - South Portico"
        val fromHeader = Qtvr.extract(whole(name))
        val fromMediaOnly = Qtvr.extract(headerless(name))

        assertEquals("width", fromHeader.width, fromMediaOnly.width)
        assertEquals("height", fromHeader.height, fromMediaOnly.height)
        assertArrayEquals("pixels differ", fromHeader.rgb, fromMediaOnly.rgb)
    }

    /** And the geometry handed to the compositor has to match too, not just pixels. */
    @Test
    fun theRebuiltGeometryMatches() {
        val name = "White House - South Portico"
        val fromHeader = Qtvr.extract(whole(name))
        val fromMediaOnly = Qtvr.extract(headerless(name))
        assertEquals(fromHeader.centralAngle, fromMediaOnly.centralAngle, 1e-6f)
        assertEquals(fromHeader.aspectRatio, fromMediaOnly.aspectRatio, 1e-6f)
    }

    @Test
    fun inspectAgreesWithExtract() {
        val v = Qtvr.inspect(headerless("White House - South Portico"))
        assertTrue("${v.summary} / ${v.detail}", v.opens)
        assertTrue(v.summary, v.summary.contains("rebuilt"))
        assertTrue(v.worthKeeping)
    }

    /**
     * The guard that earns its keep. WHouseVR is 13 nodes; its media holds every
     * node's tiles, so a naive walk stacks them into something far too wide to be
     * one panorama. It must be refused, not rendered.
     */
    @Test
    fun aMultiNodeSceneIsNotMistakenForAPanorama() {
        val d = forked("WHouseVR.MOV")
        assumeTrue("Imports/WHouseVR.MOV missing", d != null)
        assertNull("a 13-node scene must not rebuild", Headerless.scan(d!!))
        val v = Qtvr.inspect(d)
        assertFalse("${v.summary} / ${v.detail}", v.opens)
    }

    @Test
    fun theAspectGuardRejectsAStackOfNodes() {
        val one = Headerless.Media(List(24) { 0 to 100 }, tileWidth = 768, tileHeight = 116)
        assertTrue("2784x768 is an ordinary panorama", Headerless.plausible(one))
        val many = Headerless.Media(List(24 * 13) { 0 to 100 }, 768, 116)
        assertFalse("13 nodes stacked must be refused", Headerless.plausible(many))
    }

    @Test
    fun nonsenseIsNotRebuilt() {
        assertNull(Headerless.scan(ByteArray(0)))
        assertNull(Headerless.scan(ByteArray(4096) { 0x41 }))
        // A real movie with a header must be left entirely alone.
        val whole = whole("White House - South Portico")
        assertNull("a file with a moov must not take this path", Headerless.scan(whole))
    }
}
