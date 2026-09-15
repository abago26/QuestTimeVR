package com.questtime.vr

import kotlin.math.atan
import kotlin.math.tan

/**
 * One node's hot-spot mask: an id per pixel, laid out exactly like its panorama.
 *
 * Kept at the source resolution even when the panorama was downscaled for the
 * swapchain, because nothing samples this by pixel - [Hotspots.idAt] works in
 * fractions of the image, so the two need not be the same size.
 */
class HotspotMask(
    val ids: ByteArray,
    val width: Int,
    val height: Int,
    val spots: List<VrHotspot>,
) {
    /** The hot spot with [id], or null for 0 and for ids the node does not describe. */
    fun spot(id: Int): VrHotspot? = if (id == 0) null else spots.firstOrNull { it.id == id }
}

/**
 * Looking at something, and going there.
 *
 * The geometry here is deliberately the compositor's own, not an approximation of
 * it. A cylinder layer *is* a cylindrical projection - that is the idea the whole
 * app rests on - so the mapping from a direction to a texel is exact arithmetic
 * rather than a raycast against a mesh, and it can be checked on the JVM.
 */
object Hotspots {

    /**
     * Where a gaze direction lands in the image, as fractions from the top left.
     *
     * [yaw] is measured from the middle of the panorama, positive to the right, and
     * already has the viewer's accumulated snap turn taken out of it. [pitch] is
     * positive upwards. Both in radians.
     *
     * Horizontally the cylinder is linear in angle, so the column is just the
     * fraction of [centralAngle], wrapped - a full turn has no edges.
     *
     * Vertically it is *not* linear in angle, and that is the part worth stating.
     * The texture is linear in height up the cylinder wall, and height is
     * `radius * tan(pitch)`, so the row goes with the tangent. The top edge sits at
     * `atan(centralAngle / (2 * aspectRatio))` - the same expression the runtime
     * uses to derive the layer's vertical extent, quoted here so the two cannot
     * disagree about where the horizon is.
     */
    fun texel(yaw: Float, pitch: Float, centralAngle: Float, aspectRatio: Float): Pair<Float, Float> {
        val u = wrap(0.5f + yaw / centralAngle)
        val halfV = atan(centralAngle / (2.0 * aspectRatio))
        val v = 0.5 - tan(pitch.toDouble()) / (2.0 * tan(halfV))
        return u to v.toFloat()
    }

    /** Into [0, 1), which is what makes the seam behind you not a special case. */
    private fun wrap(u: Float): Float {
        var x = u % 1f
        if (x < 0f) x += 1f
        return x
    }

    /**
     * Turn a row of the *displayed* image into a row of the source band.
     *
     * What reaches the compositor is not what came out of the file. [Caps.addGradient]
     * centres the decoded band in a taller image and fills above and below with a
     * gradient, so the panorama on screen has sky and floor the mask knows nothing
     * about. Sampling the mask with the displayed fraction would squeeze every hot
     * spot towards the horizon - by a third on a typical file - and the error is
     * largest exactly where doorways are.
     *
     * [bandTop] and [bandSpan] are the fractions of the displayed image the decoded
     * band starts at and covers. The result deliberately runs outside [0, 1) when
     * the gaze is in the gradient: [idAt] reads that as nothing there, which it is.
     *
     * A downscale needs no correction of its own - it is uniform, so fractions
     * survive it unchanged.
     */
    fun intoBand(v: Float, bandTop: Float, bandSpan: Float): Float =
        if (bandSpan <= 0f) v else (v - bandTop) / bandSpan

    /**
     * The hot-spot id under a point, or 0 for none.
     *
     * Out of range vertically is 0 rather than clamped: above the top of the
     * panorama is sky, and reporting the topmost row's hot spot for it would make
     * a doorway extend to the zenith.
     */
    fun idAt(mask: HotspotMask, u: Float, v: Float): Int {
        if (v < 0f || v >= 1f) return 0
        val x = (wrap(u) * mask.width).toInt().coerceIn(0, mask.width - 1)
        val y = (v * mask.height).toInt().coerceIn(0, mask.height - 1)
        return mask.ids[y * mask.width + x].toInt() and 0xFF
    }

    /**
     * Where a hot spot leads, as an index into [nodes], or null if nowhere.
     *
     * Two steps, and the second is the one that has to be careful. A hot spot names
     * a *link* by id; the link names a *node* by id; and a node's id is not its
     * position - White House's run 1,2,3,4,5,7,8,9,10,12,14,15,16. So the node is
     * found by searching for the id, never by indexing with it.
     *
     * Null for a hot spot that is not a link at all. QuickTime VR had hot spots that
     * opened a URL or started a movie, and those are not somewhere to walk to.
     */
    fun destination(nodes: List<VrNode>, from: VrNode, hotspotId: Int): Int? {
        val spot = from.hotspots.firstOrNull { it.id == hotspotId } ?: return null
        if (spot.type != "link") return null
        val link = from.links.firstOrNull { it.id == spot.linkId } ?: return null
        val to = nodes.indexOfFirst { it.id == link.toNodeId }
        return if (to >= 0) to else null
    }

    /**
     * What to call the thing under the gaze.
     *
     * The **link's** name first, then the hot spot's, then the destination node's.
     * That order is from the files rather than from taste: Lincoln's hot spot is
     * called "Link 248" and its link "To DCwalk.02", and the second is what someone
     * deciding whether to walk through a door wants to read. Authoring tools named
     * hot spots after their own numbering and saved the description for the link.
     *
     * The hot spot's own name is still worth having when a link has none, and the
     * destination's name - even a bare "Node 4" - beats nothing at all.
     */
    fun label(nodes: List<VrNode>, from: VrNode, hotspotId: Int): String? {
        val spot = from.hotspots.firstOrNull { it.id == hotspotId } ?: return null
        val link = from.links.firstOrNull { it.id == spot.linkId }
        if (link != null && link.name.isNotEmpty()) return link.name
        if (spot.name.isNotEmpty()) return spot.name
        val to = link?.let { l -> nodes.firstOrNull { it.id == l.toNodeId } }
        return to?.label() ?: spot.type.ifEmpty { null }
    }
}
