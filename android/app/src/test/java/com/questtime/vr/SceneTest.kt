package com.questtime.vr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Taking a multi-node scene apart, checked against ffmpeg.
 *
 * The claim under test is small and entirely about arithmetic: node k owns image
 * samples [k*n, (k+1)*n), where n is the descriptor's numFrames. Getting it wrong
 * by one node produces a perfectly plausible panorama of the wrong place, which no
 * amount of looking at the output would catch - so the comparison is byte-exact
 * against ffmpeg's own decode of the same frame range, assembled and rotated the
 * same way.
 *
 * First, middle and last, because an off-by-one hides in the middle and a stride
 * error shows at the ends.
 */
class SceneTest {

    private val testdata = File("../../reference/testdata")
    private val truth = File("../../reference/truth")

    private fun lincoln(): ByteArray {
        val f = File(testdata, "lincoln9.mov")
        assumeTrue("reference/testdata/lincoln9.mov missing", f.isFile)
        return f.readBytes()
    }

    private fun truth(node: Int): ByteArray {
        val f = File(truth, "lincoln_node$node.rgb")
        assumeTrue("reference/truth/lincoln_node$node.rgb missing - run make_truth.sh", f.isFile)
        return f.readBytes()
    }

    @Test
    fun eachNodeMatchesFfmpegExactly() {
        val data = lincoln()
        for (node in listOf(0, 4, 8)) {
            val pano = Qtvr.extract(data, node = node)
            assertEquals("node $node width", 4032, pano.width)
            assertEquals("node $node height", 768, pano.height)
            assertArrayEquals("node $node pixels differ from ffmpeg", truth(node), pano.rgb)
        }
    }

    /**
     * The failure this whole feature exists to prevent.
     *
     * Decoding every sample under the first node's descriptor stacks all nine
     * panoramas into one column nine times too long. It looks like a panorama -
     * that is the danger - so the assertion is on the shape, which is unambiguous.
     */
    @Test
    fun aSceneDoesNotDecodeIntoOneTallComposite() {
        val pano = Qtvr.extract(lincoln())
        assertEquals("one node's worth, not nine", 4032, pano.width)
        assertTrue("aspect should be a panorama, not a ribbon",
            pano.aspectRatio in 2.0f..8.0f)
    }

    /** Asking for no node in particular means the first one. */
    @Test
    fun theDefaultNodeIsTheFirst() {
        val data = lincoln()
        assertArrayEquals(Qtvr.extract(data, node = 0).rgb, Qtvr.extract(data).rgb)
    }

    /** Different nodes are different places - a partition that always returned the
     * same slice would pass every comparison above that used only node 0. */
    @Test
    fun nodesAreActuallyDifferentPanoramas() {
        val data = lincoln()
        val a = Qtvr.extract(data, node = 0).rgb
        val b = Qtvr.extract(data, node = 4).rgb
        assertEquals(a.size, b.size)
        assertFalse("node 0 and node 4 decoded identically", a.contentEquals(b))
    }

    /** Off the end is a refusal, not a slice of the wrong node or an index error. */
    @Test
    fun askingForANodeThatIsNotThereSaysSo() {
        val e = runCatching { Qtvr.extract(lincoln(), node = 9) }.exceptionOrNull()
        assertTrue("expected a refusal, got $e", e is IllegalArgumentException)
        assertTrue("should name the count: ${e?.message}",
            e!!.message!!.contains("9 nodes"))
    }

    /**
     * The scene opens at all - which is the change, since it used to be refused.
     *
     * inspect exists to predict extract, so the two are checked together here
     * rather than trusting them separately.
     */
    @Test
    fun inspectAgreesTheSceneOpens() {
        val v = Qtvr.inspect(lincoln())
        assertTrue("should open: ${v.summary} / ${v.detail}", v.opens)
        assertTrue("summary should say how many nodes: ${v.summary}",
            v.summary.contains("9 nodes"))
        assertNotEquals(0, Qtvr.extract(lincoln()).rgb.size)
    }

    // ---- QuickTime VR 2.x scenes -------------------------------------------
    //
    // A different arrangement entirely: each node names its own image track through
    // the pano track's tref/imgt list, rather than taking a share of one track. The
    // twist is that nodes may *share* a named track, and then it is partitioned
    // among them exactly as 1.0 partitions its single one - so both versions are one
    // rule, and the cases below are chosen to cover each way a node can sit in it.

    private fun joshua(): ByteArray {
        val f = File(testdata, "joshua25.mov")
        assumeTrue("reference/testdata/joshua25.mov missing", f.isFile)
        return f.readBytes()
    }

    private fun joshuaTruth(node: Int): ByteArray {
        val f = File(truth, "joshua_node$node.rgb")
        assumeTrue("reference/truth/joshua_node$node.rgb missing - run make_truth.sh", f.isFile)
        return f.readBytes()
    }

    /**
     * The three ways a 2.x node can sit in its image track, against ffmpeg.
     *
     * Node 0 is the first of two sharing a 48-frame track; node 9 is the *second* of
     * four sharing a 96-frame track, which is the case that catches a partition that
     * always starts at zero; node 24 is the last node of the scene and the second of
     * two, which catches one that runs off the end.
     */
    @Test
    fun each2xNodeMatchesFfmpegExactly() {
        val data = joshua()
        for ((node, size) in listOf(0 to (3936 to 748), 9 to (3552 to 752), 24 to (3936 to 760))) {
            val pano = Qtvr.extract(data, node = node)
            assertEquals("node $node width", size.first, pano.width)
            assertEquals("node $node height", size.second, pano.height)
            assertArrayEquals("node $node differs from ffmpeg", joshuaTruth(node), pano.rgb)
        }
    }

    /**
     * Twenty-five nodes, and every one of them decodes to a panorama shape.
     *
     * The band is wider than the 2:1 the headerless guard uses, because a node is
     * free to be shorter than its neighbours: node 18 carries twelve tiles where the
     * rest carry twenty-four, and comes out 1416x720 - a squat but perfectly real
     * panorama. The guard in [Headerless] is answering a different question, about a
     * file with no descriptor at all, and its threshold does not transfer here.
     */
    @Test
    fun everyNodeOfA2xSceneDecodes() {
        val data = joshua()
        val nodes = Qtvr.nodes(data)
        assertEquals(25, nodes.size)
        val shapes = nodes.map { n ->
            val pano = Qtvr.extract(data, node = n.index)
            assertTrue("node ${n.index} is not a panorama shape: ${pano.width}x${pano.height}",
                pano.aspectRatio in 1.5f..8.0f)
            pano.width to pano.height
        }
        // And they are not all the same picture with the same measurements.
        assertTrue("every node came out identical in size - suspicious",
            shapes.distinct().size > 1)
    }

    /**
     * Nodes that share a track must not decode identically, and nodes that have
     * their own must not be confused with them.
     *
     * Nodes 2, 9, 14 and 17 all name the same 96-frame track. If the partition were
     * ignored they would be four copies of one image - which is exactly what a
     * plausible-looking wrong answer looks like here.
     */
    @Test
    fun nodesSharingATrackAreStillDifferentPlaces() {
        val data = joshua()
        val shared = listOf(2, 9, 14, 17).map { Qtvr.extract(data, node = it).rgb }
        for (i in shared.indices) {
            for (j in i + 1 until shared.size) {
                assertFalse("two nodes sharing a track decoded identically ($i, $j)",
                    shared[i].contentEquals(shared[j]))
            }
        }
    }

    /**
     * A scene that also carries object movies keeps its panoramas.
     *
     * Joshua Tree is 25 panorama nodes and 2 object nodes; Maranello is 1 and 4.
     * Both used to be refused outright as object movies, which threw away every
     * panorama in them. The two kinds live in separate tracks, so the panorama
     * track already holds exactly what can be shown.
     */
    @Test
    fun aSceneWithObjectMoviesStillOpensItsPanoramas() {
        val v = Qtvr.inspect(joshua())
        assertTrue("should open: ${v.summary} / ${v.detail}", v.opens)
        assertTrue("the objects should be mentioned, not fatal: ${v.summary}",
            v.summary.contains("object movies"))
        assertEquals(25, Qtvr.nodes(joshua()).size)
    }

    /** Off the end of a 2.x scene refuses the same way a 1.0 one does. */
    @Test
    fun askingPastTheEndOfA2xSceneSaysSo() {
        val e = runCatching { Qtvr.extract(joshua(), node = 25) }.exceptionOrNull()
        assertTrue("expected a refusal, got $e", e is IllegalArgumentException)
        assertTrue("should name the count: ${e?.message}", e!!.message!!.contains("25 node"))
    }

    /**
     * Lincoln carries a second Cinepak track, 192x84, a low-resolution copy for
     * scrubbing. It decodes perfectly well, so nothing but the descriptor
     * distinguishes it from the real image track - and it was only ever avoided
     * because it happens to be stored second.
     */
    @Test
    fun theLowResolutionCopyIsNotMistakenForTheImage() {
        val tracks = MovParser.parseTracks(lincoln())
        val info = PanoInfo.parse(tracks.first { it.format == "pano" }.sampleDescription)!!
        assertEquals("picked the scrubbing copy", 768, Qtvr.imageTrack(tracks, info).width)
    }
}
