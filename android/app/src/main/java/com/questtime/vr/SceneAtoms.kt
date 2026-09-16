package com.questtime.vr

/**
 * The hot spots of a QuickTime VR **2.x** scene.
 *
 * 1.0 keeps a node's hot spots in its own pano sample, as flat `pHot` atoms beside a
 * string table - that is what [NodeTable] walks. 2.x keeps them somewhere else
 * entirely, in the `qtvr` track, and this walks that instead. Both produce the same
 * [VrNode] shape, so everything downstream - the mask, the gaze, the reticle, the
 * label - is shared rather than written twice.
 *
 * The container is a **QuickTime atom container**, which is not the atom layout the
 * rest of the file uses. Each atom is twenty bytes of header - size, type, an id, a
 * child count - then either children or data. Read off the archive:
 *
 * ```
 * sean                     the root
 *   ndhd    node header; its payload names the node type, 'pano' or 'obje'
 *   hspa    the hot spots
 *     hots  id = THE HOT SPOT ID, the number the smc mask stores per pixel
 *       vrsg   the author's words: "Go for a walk to Cyclops"
 *       hsin   what kind it is; 'link' at payload offset 4
 *       link   destination node **id** at payload offset 4
 * ```
 *
 * **The `hots` atom's id is the hot-spot id**, carried in the atom header rather than
 * the payload. That is the join to the mask, it is easy to miss, and without it there
 * is nothing to match a pixel against.
 *
 * A twelve-byte sample header sits before the root. Ten is the figure usually quoted
 * for an atom container; these files use twelve, and starting at ten reads a size of
 * zero and finds nothing at all.
 */
object SceneAtoms {

    private const val HEADER = 20
    private const val SAMPLE_PREFIX = 12

    private class Atom(
        val type: String, val id: Int, val from: Int, val to: Int,
    )

    /**
     * Hot spots and their links for one node, from that node's `qtvr` sample.
     *
     * Empty rather than thrown when nothing can be read: a scene whose hot spots are
     * unreadable is still a scene worth walking by list, and this is additive.
     */
    fun hotspots(sample: ByteArray): Pair<List<VrHotspot>, List<VrLink>> {
        val root = children(sample, SAMPLE_PREFIX, sample.size).firstOrNull()
            ?: return emptyList<VrHotspot>() to emptyList()
        val spots = ArrayList<VrHotspot>()
        val links = ArrayList<VrLink>()
        for (top in children(sample, root.from, root.to)) {
            if (top.type != "hspa") continue
            for (hot in children(sample, top.from, top.to)) {
                if (hot.type != "hots") continue
                var kind = ""
                var dest = -1
                var name = ""
                for (part in children(sample, hot.from, hot.to)) {
                    when (part.type) {
                        "hsin" -> kind = fourCC(sample, part.from + 4)
                        "link" -> dest = int32(sample, part.from + 4)
                        "vrsg" -> name = text(sample, part.from, part.to)
                    }
                }
                if (dest <= 0) continue
                // One link per hot spot here, rather than 1.0's separate table, so
                // the two share an id and the existing hot spot -> link -> node
                // chain resolves unchanged.
                spots.add(VrHotspot(hot.id, kind.ifEmpty { "link" }, hot.id, name))
                links.add(VrLink(hot.id, dest, 0.0, 0.0, 0.0, name))
            }
        }
        return spots to links
    }

    /**
     * The `ndhd` payload: two version shorts, the node type, then the node's id.
     *
     * ```
     * +0  majorVersion, minorVersion
     * +4  'pano' or 'obje'
     * +8  nodeID
     * ```
     */
    private fun ndhd(sample: ByteArray): Atom? {
        val root = children(sample, SAMPLE_PREFIX, sample.size).firstOrNull() ?: return null
        return children(sample, root.from, root.to).firstOrNull { it.type == "ndhd" }
    }

    /** "pano" for somewhere to stand, "obje" for a thing you spin. */
    fun nodeType(sample: ByteArray): String {
        val a = ndhd(sample) ?: return ""
        return fourCC(sample, a.from + 4)
    }

    /**
     * The node's own id - what links point at, and **not** its position.
     *
     * Inventing these as position+1 is wrong and quietly so: the qtvr track counts
     * object nodes too, so Joshua Tree's panoramas do not run 1..25, and a link to
     * node 27 resolved to nothing while a link to 20 resolved to the wrong place.
     * The same trap the 1.0 table has, in a different file format.
     */
    fun nodeId(sample: ByteArray): Int {
        val a = ndhd(sample) ?: return -1
        return int32(sample, a.from + 8)
    }

    private fun children(b: ByteArray, start: Int, end: Int): List<Atom> {
        val out = ArrayList<Atom>()
        var p = start
        while (p + HEADER <= end) {
            val size = int32(b, p)
            if (size < HEADER || p + size > end) break
            out.add(Atom(fourCC(b, p + 4), int32(b, p + 8), p + HEADER, p + size))
            p += size
        }
        return out
    }

    /**
     * A `vrsg` string: two bytes of version, two of length, then the text.
     *
     * Read as Latin-1 rather than decoded: these are Mac Roman, and the bytes above
     * 0x7F differ between the two. Nothing in the archive needs them, and mapping a
     * byte to the wrong character is worse than leaving it alone.
     */
    private fun text(b: ByteArray, from: Int, to: Int): String {
        if (from + 4 > to) return ""
        val len = u16(b, from + 2)
        val start = from + 4
        val stop = minOf(start + len, to)
        if (start >= stop) return ""
        return String(b, start, stop - start, Charsets.ISO_8859_1)
            .filter { it.code >= 0x20 }
            .trim()
    }

    private fun fourCC(b: ByteArray, at: Int): String =
        if (at + 4 <= b.size) String(b, at, 4, Charsets.ISO_8859_1) else ""

    private fun int32(b: ByteArray, at: Int): Int =
        if (at + 4 > b.size) -1 else
            ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
                ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun u16(b: ByteArray, at: Int): Int =
        if (at + 2 > b.size) 0 else
            ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
}
