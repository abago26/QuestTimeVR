package com.questtime.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The 2.x hot-spot container, read off the real archive.
 *
 * Joshua Tree listed 25 nodes and offered no doorways at all, because 2.x keeps its
 * hot spots in the `qtvr` track rather than beside the node the way 1.0 does. These
 * pin the layout that was read out of the file, since none of it came from a
 * specification.
 */
class SceneAtomsTest {

    private fun scene(name: String): ByteArray {
        val f = File("../../Imports/$name")
        assumeTrue("Imports/$name missing", f.isFile)
        return AppleZip.readPaired(f)
    }

    @Test
    fun joshuaTreeNowHasDoorways() {
        val nodes = Qtvr.nodes(scene("Joshua Tree"))
        assertEquals("25 panorama nodes", 25, nodes.size)
        val total = nodes.sumOf { it.hotspots.size }
        assertTrue("2.x scenes used to report 0 hot spots; got $total", total > 0)

        // Node 0 has four hot spots in the file - 59, 188, 86, 95 - going to nodes
        // 24, 20, 19 and 13. Two of those are object nodes (Joshua Tree's ids 19 and
        // 20 are 'obje'), which are things to spin rather than places to stand, so
        // they are dropped and the reticle stays dark over those doorways. Offering
        // a way on that cannot be walked is worse than not lighting it.
        val first = nodes[0]
        assertEquals("the two that lead somewhere standable",
            listOf(59, 95), first.hotspots.map { it.id })
        assertEquals(listOf(24, 13), first.links.map { it.toNodeId })
        assertTrue("the author's own words should survive",
            first.links.any { it.name.contains("Cyclops") })
    }

    /**
     * Every destination has to name a node that exists.
     *
     * An id is not an index here either, so a link that resolves to nothing is the
     * shape of a bug that would otherwise only show as walking into a wall.
     */
    @Test
    fun everyLinkPointsAtARealNode() {
        for (name in listOf("Joshua Tree", "Point Lobos")) {
            val nodes = Qtvr.nodes(scene(name))
            assumeTrue("$name has no nodes", nodes.isNotEmpty())
            val ids = nodes.map { it.id }.toSet()
            for (n in nodes) {
                for (l in n.links) {
                    assertTrue("$name node ${n.index}: link to ${l.toNodeId}, ids are $ids",
                        l.toNodeId in ids)
                }
            }
        }
    }

    /**
     * The qtvr track has a sample per node including object nodes, the pano track
     * only the panoramas - 27 against 25 for Joshua Tree. Pairing them by raw
     * position would hand a node someone else's doorways.
     */
    @Test
    fun objectNodesDoNotShiftThePanoramas() {
        val data = scene("Joshua Tree")
        val tracks = MovParser.parseTracks(data)
        val qtvr = tracks.first { it.handler == "qtvr" }
        val samples = qtvr.sampleRanges().map { (off, size) ->
            data.copyOfRange(off.toInt(), (off + size).toInt())
        }
        val kinds = samples.map { SceneAtoms.nodeType(it) }
        assertEquals("27 qtvr samples", 27, kinds.size)
        assertEquals("25 of them panoramas", 25, kinds.count { it == "pano" })
        assertEquals("2 of them objects", 2, kinds.count { it == "obje" })
    }

    /**
     * Storage order is not id order, so an index can never stand in for an id.
     *
     * Joshua Tree stores pano#15, #16, #17 and then #14. The 1.0 table has the same
     * hazard with deleted ids; this is the 2.x shape of it, and it is why the node's
     * own id is read out of ndhd rather than counted.
     */
    @Test
    fun idsDoNotFollowStorageOrder() {
        val nodes = Qtvr.nodes(scene("Joshua Tree"))
        assumeTrue("needs the archive", nodes.isNotEmpty())
        val ids = nodes.map { it.id }
        assertEquals("ids should be the file's, not positions", 25, ids.size)
        assertTrue("object nodes' ids must not appear among the panoramas",
            19 !in ids && 20 !in ids)
        assertTrue("ids are not sorted in storage order: $ids", ids != ids.sorted())
    }

    @Test
    fun nonsenseIsEmptyRatherThanThrown() {
        assertTrue(SceneAtoms.hotspots(ByteArray(0)).first.isEmpty())
        assertTrue(SceneAtoms.hotspots(ByteArray(64)).first.isEmpty())
        assertEquals("", SceneAtoms.nodeType(ByteArray(8)))
    }
}
