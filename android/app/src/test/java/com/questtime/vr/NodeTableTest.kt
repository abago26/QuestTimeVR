package com.questtime.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The node table of a multi-node QuickTime VR 1.0 scene.
 *
 * Every offset in [NodeTable] was read off these files rather than taken from a
 * specification, so these tests are the specification. They assert real names and
 * real numbers - "DCwalk.03", nine nodes, ids that skip - because an offset that is
 * four bytes out still parses, still produces plausible-looking numbers, and is
 * still wrong. Only the values give that away.
 *
 * The archive is not in the repository, so a clone without it skips.
 */
class NodeTableTest {

    private val imports = File("../../Imports")

    /** The file with its resource-fork header put back, as a zip upload does. */
    private fun scene(name: String): ByteArray {
        val data = File(imports, name)
        assumeTrue("Imports/$name missing", data.isFile)
        val rsrc = File(imports, "$name/..namedfork/rsrc")
        val bytes = data.readBytes()
        if (!rsrc.isFile) return bytes
        val moov = AppleZip.findResource(rsrc.readBytes(), "moov")
        assumeTrue("no moov resource in $name", moov != null)
        return bytes + moov!!
    }

    private val lincoln get() = scene("Lincoln Memorial (9 nodes)")
    private val whiteHouse get() = scene("WHouseVR.MOV")

    @Test
    fun readsEveryNodeWithItsName() {
        val nodes = Qtvr.nodes(lincoln)
        assertEquals("node count", 9, nodes.size)
        assertEquals(
            (1..9).map { "DCwalk.%02d".format(it) },
            nodes.map { it.name },
        )
        assertEquals("ids run 1..9 here", (1..9).toList(), nodes.map { it.id })
        assertEquals("index is position", (0..8).toList(), nodes.map { it.index })
    }

    /**
     * The one that stops an index being used as an id.
     *
     * White House keeps thirteen nodes numbered 1,2,3,4,5,7,8,9,10,12,14,15,16 -
     * 6, 11 and 13 were deleted in authoring and nothing renumbered. Reading the id
     * as a position would fetch the wrong panorama for everything past the fifth,
     * and would read off the end for the last three.
     */
    @Test
    fun nodeIdsAreNotPositionsAndMayHaveGaps() {
        val nodes = Qtvr.nodes(whiteHouse)
        assertEquals(13, nodes.size)
        assertEquals(
            listOf(1, 2, 3, 4, 5, 7, 8, 9, 10, 12, 14, 15, 16),
            nodes.map { it.id },
        )
        assertEquals((0..12).toList(), nodes.map { it.index })
        assertNotEquals("ids and indices must not be interchangeable",
            nodes.map { it.index + 1 }, nodes.map { it.id })
        assertEquals("WHouse.07", nodes[5].name)
        assertEquals("WHouse.16", nodes.last().name)
    }

    /**
     * The default view, which is the one thing a name cannot tell you apart.
     *
     * A name survives an offset that is wrong by a field; these numbers do not.
     * Lincoln's first node looks at 99.4 degrees through a 27.5 degree window.
     */
    @Test
    fun readsTheDefaultViewDirection() {
        val n = Qtvr.nodes(lincoln).first()
        assertEquals(99.40, n.pan, 0.01)
        assertEquals(-0.40, n.tilt, 0.01)
        assertEquals(27.50, n.fov, 0.01)
        assertTrue("every node aims somewhere sensible",
            Qtvr.nodes(lincoln).all { it.pan in 0.0..360.0 && it.fov > 1.0 })
    }

    /**
     * Links are parsed even though nothing navigates them yet.
     *
     * They refer to nodes by id, which is why they are worth asserting now: the
     * moment something follows one it has to map that id back to an index, and the
     * graph being reciprocal is what proves the field is the destination and not
     * some other number that happens to be small.
     */
    @Test
    fun linksNameTheirDestinationByNodeId() {
        val nodes = Qtvr.nodes(lincoln)
        val byId = nodes.associateBy { it.id }
        assertEquals("first node links on to the second", listOf(2), nodes[0].links)
        assertEquals("and the second links back", listOf(1), nodes[1].links)
        for (n in nodes) {
            for (dest in n.links) {
                assertTrue("link to a node that is not there: $dest", dest in byId)
            }
        }
    }

    /** A single-node panorama has a table of one, not an empty one. */
    @Test
    fun aPlainPanoramaIsASceneOfOneNode() {
        val nodes = Qtvr.nodes(scene("Monument Valley"))
        assertEquals(1, nodes.size)
        assertEquals(0, nodes.first().index)
    }

    /**
     * inspect and extract must agree about every file in the archive.
     *
     * They are separate code on purpose - one is producing an image, the other an
     * explanation - and they have drifted apart twice already, over the cubic
     * rotation check and over object movies. Node selection moved both, so this
     * walks the whole archive and holds them to each other rather than trusting
     * that the two edits matched.
     */
    @Test
    fun inspectPredictsExtractForEveryFileInTheArchive() {
        assumeTrue("Imports missing", imports.isDirectory)
        val files = imports.listFiles()?.filter { it.isFile }.orEmpty()
        assumeTrue("Imports is empty", files.isNotEmpty())

        val disagreed = ArrayList<String>()
        var opened = 0
        for (f in files.sortedBy { it.name }) {
            val bytes = withHeader(f)
            val said = runCatching { Qtvr.inspect(bytes) }.getOrNull()?.opens == true
            val got = runCatching { Qtvr.extract(bytes) }
            // Photo-JPEG tiles cannot decode here - there is no BitmapFactory on
            // this classpath - so those files are not evidence either way.
            if (got.exceptionOrNull() is UnsupportedCodec) continue
            val did = got.isSuccess
            if (said != did) {
                disagreed += "${f.name}: inspect=$said extract=$did " +
                    "(${got.exceptionOrNull()?.message ?: "opened"})"
            }
            if (said) opened++
        }
        assertEquals("inspect and extract disagree: $disagreed", emptyList<String>(), disagreed)
        assertTrue("nothing opened at all - the archive may be wrong", opened > 0)
    }

    /** The file with its resource fork put back, when it has one. */
    private fun withHeader(f: File): ByteArray {
        val bytes = f.readBytes()
        val rsrc = File(imports, "${f.name}/..namedfork/rsrc")
        if (!rsrc.isFile) return bytes
        val moov = AppleZip.findResource(rsrc.readBytes(), "moov") ?: return bytes
        return bytes + moov
    }

    /** An unnamed node still has something to put in a list. */
    @Test
    fun labelFallsBackToThePosition() {
        assertEquals("Node 4", VrNode(3, 99, "", 0.0, 0.0, 0.0, emptyList()).label())
        assertEquals("DCwalk.01", Qtvr.nodes(lincoln).first().label())
    }
}
