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
