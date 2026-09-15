package com.questtime.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.atan
import kotlin.math.tan

/**
 * Looking at a doorway, and knowing where it goes.
 *
 * Two halves that have to agree. The mask says which hot spot is under a direction;
 * the node's atoms say where that hot spot leads. Either alone is easy to get
 * plausibly wrong - a mask assembled differently from its panorama puts every hot
 * spot *near* where it belongs, and an id read as an index walks you into the wrong
 * room - so the tests below check the chain end to end against a real file.
 */
class HotspotTest {

    private val testdata = File("../../reference/testdata")

    private fun lincoln(): ByteArray {
        val f = File(testdata, "lincoln9.mov")
        assumeTrue("reference/testdata/lincoln9.mov missing", f.isFile)
        return f.readBytes()
    }

    @Test
    fun theMaskIsShapedLikeItsPanorama() {
        val data = lincoln()
        val mask = Qtvr.hotspotMask(data, 0)
        assumeTrue("no mask for node 0", mask != null)
        val pano = Qtvr.extract(data, node = 0)
        assertEquals("mask width must match the panorama", pano.width, mask!!.width)
        assertEquals("mask height must match the panorama", pano.height, mask.height)
    }

    /** Node 0 carries one hot spot, id 248, and the mask holds only it and nothing. */
    @Test
    fun theMaskHoldsTheNodesOwnHotSpotIds() {
        val mask = Qtvr.hotspotMask(lincoln(), 0)
        assumeTrue("no mask for node 0", mask != null)
        val seen = mask!!.ids.map { it.toInt() and 0xFF }.toSortedSet()
        assertEquals(setOf(0, 248), seen)
        assertEquals("the node should describe that id", 1, mask.spots.size)
        assertEquals(248, mask.spots.first().id)
        assertEquals("link", mask.spots.first().type)
    }

    /**
     * The whole chain: a direction, an id, a link, a node.
     *
     * The direction is taken from the mask itself rather than invented - find a
     * pixel the hot spot covers, turn it back into a gaze, and check the lookup
     * finds its way home. That is what proves [Hotspots.texel] and [Hotspots.idAt]
     * are inverses of each other rather than merely both plausible.
     */
    @Test
    fun lookingAtTheHotSpotLeadsToTheLinkedNode() {
        val data = lincoln()
        val mask = Qtvr.hotspotMask(data, 0)
        assumeTrue("no mask for node 0", mask != null)
        val nodes = Qtvr.nodes(data)
        val pano = Qtvr.extract(data, node = 0)

        // A pixel in the middle of the hot spot's own rows.
        val hit = mask!!.ids.indices.first { (mask.ids[it].toInt() and 0xFF) == 248 }
        val u = ((hit % mask.width) + 0.5f) / mask.width
        val v = ((hit / mask.width) + 0.5f) / mask.height

        assertEquals("the round trip should find the same id",
            248, Hotspots.idAt(mask, u, v))

        // And that direction, turned into a gaze and back, still lands on it.
        val central = pano.centralAngle
        val aspect = pano.aspectRatio
        val yaw = (u - 0.5f) * central
        val halfV = atan(central / (2.0 * aspect))
        val pitch = atan((0.5 - v) * 2.0 * tan(halfV)).toFloat()
        val (u2, v2) = Hotspots.texel(yaw, pitch, central, aspect)
        assertEquals("yaw round trip", u, u2, 1e-4f)
        assertEquals("pitch round trip", v, v2, 1e-4f)
        assertEquals(248, Hotspots.idAt(mask, u2, v2))

        val from = nodes.first()
        val to = Hotspots.destination(nodes, from, 248)
        assertEquals("node 0's only hot spot should lead to the second node", 1, to)
        assertEquals("and that node is the one with id 2", 2, nodes[to!!].id)
    }

    /** Nothing under the gaze is 0, and 0 leads nowhere rather than to node zero. */
    @Test
    fun emptyMaskMeansNoDestination() {
        val data = lincoln()
        val nodes = Qtvr.nodes(data)
        assertNull("id 0 must not resolve", Hotspots.destination(nodes, nodes.first(), 0))
        assertNull("an id the node does not describe must not resolve",
            Hotspots.destination(nodes, nodes.first(), 99))
    }

    /**
     * The label is the author's own words, and the link's words beat the hot spot's.
     *
     * This node's hot spot is named "Link 248" - the authoring tool's own numbering -
     * while its link is "To DCwalk.02". Preferring the hot spot would put a serial
     * number in front of someone deciding whether to walk through a door.
     */
    @Test
    fun theLabelPrefersTheLinksOwnDescription() {
        val nodes = Qtvr.nodes(lincoln())
        val from = nodes.first()
        assertEquals("Link 248", from.hotspots.first().name)
        assertEquals("To DCwalk.02", from.links.first().name)
        assertEquals("To DCwalk.02", Hotspots.label(nodes, from, 248))
    }

    // ---- the geometry on its own -------------------------------------------

    /** Straight ahead is the middle of the image, in both axes. */
    @Test
    fun theCentreOfTheViewIsTheCentreOfTheImage() {
        val (u, v) = Hotspots.texel(0f, 0f, (2 * Math.PI).toFloat(), 3.25f)
        assertEquals(0.5f, u, 1e-6f)
        assertEquals(0.5f, v, 1e-6f)
    }

    /** The top of the image is exactly the extent the runtime derives. */
    @Test
    fun theTopOfTheImageIsTheLayersOwnVerticalExtent() {
        val central = (2 * Math.PI).toFloat()
        val aspect = 3.25f
        val halfV = atan(central / (2.0 * aspect)).toFloat()
        assertEquals("looking at the top edge", 0f, Hotspots.texel(0f, halfV, central, aspect).second, 1e-5f)
        assertEquals("and the bottom", 1f, Hotspots.texel(0f, -halfV, central, aspect).second, 1e-5f)
    }

    /** Turning right walks the column right, and the seam behind you is not special. */
    @Test
    fun theWrapBehindYouIsNotAnEdge() {
        val central = (2 * Math.PI).toFloat()
        val a = Hotspots.texel((Math.PI - 0.01).toFloat(), 0f, central, 3.25f).first
        val b = Hotspots.texel((-Math.PI + 0.01).toFloat(), 0f, central, 3.25f).first
        assertTrue("just before the seam should be near 1: $a", a > 0.99f)
        assertTrue("just after it should be near 0: $b", b < 0.01f)
    }

    /** Above the sky is nothing, not the topmost row stretched to the zenith. */
    @Test
    fun lookingStraightUpFindsNothing() {
        val mask = Qtvr.hotspotMask(lincoln(), 0)
        assumeTrue("no mask", mask != null)
        val central = (2 * Math.PI).toFloat()
        val (u, v) = Hotspots.texel(0f, (Math.PI / 2 - 0.01).toFloat(), central, 3.25f)
        assertEquals("straight up is off the top", 0, Hotspots.idAt(mask!!, u, v))
    }

    /**
     * The gradient caps move every row, and the mask does not know about them.
     *
     * A panorama 768 rows tall shown in a 1200-row image sits with 216 rows of sky
     * above it. Reading the mask with the displayed fraction would put the horizon a
     * third of the way off and squeeze every doorway towards the middle.
     */
    @Test
    fun theGradientCapsAreTakenOutBeforeTheMaskIsRead() {
        val total = 1200f
        val band = 768f
        val top = ((total - band) / 2f) / total          // 0.18
        val span = band / total                          // 0.64

        assertEquals("the middle is still the middle", 0.5f,
            Hotspots.intoBand(0.5f, top, span), 1e-5f)
        assertEquals("the band's first row is the mask's first row", 0f,
            Hotspots.intoBand(top, top, span), 1e-5f)
        assertEquals("and its last is the mask's last", 1f,
            Hotspots.intoBand(top + span, top, span), 1e-5f)
        assertTrue("sky is off the top of the mask",
            Hotspots.intoBand(0.05f, top, span) < 0f)
        assertTrue("floor is off the bottom",
            Hotspots.intoBand(0.95f, top, span) > 1f)
    }

    /** And a gaze into the gradient finds nothing rather than the nearest row. */
    @Test
    fun aGazeIntoTheCapFindsNothing() {
        val mask = Qtvr.hotspotMask(lincoln(), 0)
        assumeTrue("no mask", mask != null)
        assertEquals(0, Hotspots.idAt(mask!!, 0.5f, Hotspots.intoBand(0.02f, 0.18f, 0.64f)))
        assertEquals(0, Hotspots.idAt(mask, 0.5f, Hotspots.intoBand(0.98f, 0.18f, 0.64f)))
    }

    /** No caps at all is the identity, not a divide by zero. */
    @Test
    fun anUncappedPanoramaIsUnchanged() {
        assertEquals(0.37f, Hotspots.intoBand(0.37f, 0f, 1f), 1e-6f)
        assertEquals(0.37f, Hotspots.intoBand(0.37f, 0f, 0f), 1e-6f)
    }
}
