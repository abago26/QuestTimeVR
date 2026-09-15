package com.questtime.vr

/**
 * The scene graph of a multi-node QuickTime VR 1.0 movie.
 *
 * A scene is several panoramas in one file - stand here, walk through the door,
 * stand there. Every node's tiles live in the same image track, one after another,
 * which is why decoding the whole track stacks nine panoramas into one very tall
 * column that looks like a panorama and is not. Splitting them needs this table.
 *
 * The layout was read off the files rather than taken from a specification, and
 * every offset below is checked against real samples in NodeTableTest. A node's
 * sample is a flat sequence of atoms - an 8-byte size and type, then payload, with
 * no container header - which tile the sample exactly:
 *
 *     pHdr  64   the node: id, default view direction, and where its name is
 *     pLnk  68   one per link to another node
 *     pHot  68   one per hot spot
 *     strT   n   every string in the node, as Pascal strings
 *
 * String references are byte offsets measured from the *start of the strT atom*,
 * so they include its own 8-byte header - offset 8 is the first string. That is
 * worth stating because it is the one thing here that reads as an off-by-eight bug
 * and is not.
 *
 * QuickTime VR 2.x scenes are a different arrangement - each node names its own
 * image track through a track reference, rather than taking a share of one track -
 * and no 2.x multi-node file was available to check against, so [Qtvr] refuses
 * those rather than guessing. This is 1.0 only, deliberately.
 */

/**
 * A way out of a node: which node it leads to, and where to look on arrival.
 *
 * [id] is what a hot spot names when its type is 'link'. [toNodeId] is a node's own
 * id, not its index - see [VrNode.id] for why that distinction is not cosmetic.
 */
data class VrLink(
    val id: Int,
    val toNodeId: Int,
    val pan: Double,
    val tilt: Double,
    val fov: Double,
    val name: String,
)

/**
 * A region of the panorama you can look at and act on.
 *
 * [id] is the number that appears in the hot-spot mask - the mask is an image the
 * size of the panorama whose pixel values are these ids, 0 meaning nothing here. So
 * "what am I looking at" is: find the mask pixel under the gaze, read the id, find
 * the hot spot with it.
 *
 * Only [type] `"link"` goes anywhere. QuickTime VR also had `'url '`, `'navg'` and
 * others; they are parsed and named so they can be shown as "not somewhere this can
 * take you" rather than silently doing nothing.
 */
data class VrHotspot(
    val id: Int,
    val type: String,
    /** For a link hot spot, the [VrLink.id] it triggers. */
    val linkId: Int,
    val name: String,
)

/** One node: one panorama you can stand in. */
data class VrNode(
    /**
     * Position in the pano track, from zero.
     *
     * This - not [id] - is what selects the node's tiles out of the image track,
     * because the track is partitioned in storage order.
     */
    val index: Int,
    /**
     * The node's own identifier, which is what [links] refer to.
     *
     * Not the same as [index], and not safe to use as one: White House runs
     * 1,2,3,4,5,7,8,9,10,12,14,15,16 across thirteen nodes. Somewhere in its
     * authoring, nodes 6, 11 and 13 were deleted and the rest kept their numbers.
     */
    val id: Int,
    /** What the author called it - "DCwalk.03". Empty when the file carries no name. */
    val name: String,
    /** Where the author meant you to be looking, in degrees. */
    val pan: Double,
    val tilt: Double,
    val fov: Double,
    /** The ways out of this node, by link id. */
    val links: List<VrLink>,
    /** The regions of this node's panorama that do something. */
    val hotspots: List<VrHotspot> = emptyList(),
) {
    /** What to call this node in a list, with a fallback for unnamed ones. */
    fun label(): String = name.ifEmpty { "Node ${index + 1}" }
}

object NodeTable {

    /** Atom types, and the offsets within their payloads that were verified. */
    private const val HDR_ID = 0
    private const val HDR_PAN = 4
    private const val HDR_TILT = 8
    private const val HDR_FOV = 12
    private const val HDR_NAME = 48
    private const val LNK_DEST = 16
    private const val LNK_PAN = 32
    private const val LNK_TILT = 36
    private const val LNK_FOV = 40
    private const val NAME_AT = 52
    private const val HOT_ID = 4
    private const val HOT_TYPE = 8
    private const val HOT_DATA = 12

    /**
     * Read the node table from the pano track's samples, one sample per node.
     *
     * Pure, so it can be tested against the real archive on the JVM without a
     * headset or a decoder. A sample that cannot be understood still yields a node -
     * the count has to stay right, because it is what partitions the image track,
     * and a node with no name is far better than a scene that is off by one.
     */
    fun parse(samples: List<ByteArray>): List<VrNode> =
        samples.mapIndexed { i, sample -> parseOne(i, sample) }

    private fun parseOne(index: Int, sample: ByteArray): VrNode {
        var header: ByteArray? = null
        val links = ArrayList<ByteArray>()
        val hots = ArrayList<ByteArray>()
        var strings: ByteArray? = null

        var pos = 0
        while (pos + 8 <= sample.size) {
            val size = sample.u32(pos).toInt()
            val type = sample.fourCC(pos + 4)
            if (size < 8 || pos + size > sample.size) break
            val body = sample.copyOfRange(pos + 8, pos + size)
            when (type) {
                "pHdr" -> if (header == null) header = body
                "pLnk" -> links.add(body)
                "pHot" -> hots.add(body)
                "strT" -> if (strings == null) strings = body
            }
            pos += size
        }

        val h = header
            ?: return VrNode(index, index + 1, "", 0.0, 0.0, 0.0, emptyList(), emptyList())

        fun fixed(o: Int) = if (o + 4 <= h.size) h.fixed(o) else 0.0
        return VrNode(
            index = index,
            id = if (h.size >= 4) h.u32(HDR_ID).toInt() else index + 1,
            name = string(strings, if (h.size >= HDR_NAME + 4) h.u32(HDR_NAME).toInt() else 0),
            pan = fixed(HDR_PAN),
            tilt = fixed(HDR_TILT),
            fov = fixed(HDR_FOV),
            links = links.mapNotNull { l ->
                if (l.size < LNK_DEST + 4) null else VrLink(
                    id = l.u32(0).toInt(),
                    toNodeId = l.u32(LNK_DEST).toInt(),
                    pan = if (l.size >= LNK_PAN + 4) l.fixed(LNK_PAN) else 0.0,
                    tilt = if (l.size >= LNK_TILT + 4) l.fixed(LNK_TILT) else 0.0,
                    fov = if (l.size >= LNK_FOV + 4) l.fixed(LNK_FOV) else 0.0,
                    name = string(strings, if (l.size >= NAME_AT + 4) l.u32(NAME_AT).toInt() else 0),
                )
            },
            hotspots = hots.mapNotNull { t ->
                if (t.size < HOT_DATA + 4) null else VrHotspot(
                    id = t.u16(HOT_ID),
                    type = t.fourCC(HOT_TYPE).trim { it <= ' ' || it == '\u0000' },
                    linkId = t.u32(HOT_DATA).toInt(),
                    name = string(strings, if (t.size >= NAME_AT + 4) t.u32(NAME_AT).toInt() else 0),
                )
            },
        )
    }

    /**
     * One Pascal string out of the table, by the offset a header gave.
     *
     * [offset] counts from the strT atom's own start, so the first string sits at 8
     * rather than 0 - see the note at the top. Zero means "no string", which is how
     * an absent name and a name at the very start are told apart.
     *
     * Decoded byte-for-char. These are MacRoman, and Android carries no MacRoman
     * charset (the same wall AppleZip.entryName hit with cp437); every name seen in
     * practice is ASCII, where the two agree exactly.
     */
    private fun string(table: ByteArray?, offset: Int): String {
        if (table == null || offset <= 0) return ""
        val at = offset - 8
        if (at < 0 || at >= table.size) return ""
        val len = table[at].toInt() and 0xFF
        if (at + 1 + len > table.size) return ""
        return String(table, at + 1, len, Charsets.ISO_8859_1)
    }
}
