package com.questtime.vr

/**
 * Rebuilding a panorama from media alone, when the `moov` header never arrived.
 *
 * Classic Mac QuickTime files keep their header in the resource fork, and a browser
 * cannot upload one. Sent loose, such a file arrives as an `mdat` and nothing else.
 * The advice is to send a zip, and the zip route is always the better answer - but
 * telling someone their file is fine and refusing it anyway is a poor way to be
 * right, so this recovers what can be recovered.
 *
 * **It works because Cinepak frames describe themselves.** Every frame carries its
 * own width, height and length, so the sample table can be rebuilt by walking the
 * media. Verified against the real thing: the byte ranges this finds in
 * `White House - South Portico` are identical to the ones its recovered `moov`
 * lists - same count, same offsets, same sizes. Same bytes in, same pixels out.
 *
 * Everything else the header would have said is either already assumed or already
 * ignored. `centralAngle` is a full turn for every file of this era; the storage is
 * the legacy rotated form, which a survey of nine files across three archives found
 * universal; and the vertical extent is derived from pixel aspect anyway, because
 * `vPanTop`/`vPanBottom` disagree with the pixels in at least one known file.
 *
 * **The one thing that cannot be recovered is the node count**, and that is what the
 * guards below are for. A multi-node scene has every node's samples in the same
 * media, so a naive walk stacks them into one very tall column that looks like a
 * panorama and is not. That is the exact failure this project refuses to ship, so
 * the shape of the result has to earn its way through [plausible] before anything is
 * decoded.
 */
object Headerless {

    /** A rebuilt sample table: where each frame is, and how big the frames are. */
    data class Media(
        /** Offset and length per frame, in the order they appear. */
        val samples: List<Pair<Int, Int>>,
        val tileWidth: Int,
        val tileHeight: Int,
    ) {
        /** Tiles are stacked into a column, then the column is rotated 90° CW. */
        val panoramaWidth get() = samples.size * tileHeight
        val panoramaHeight get() = tileWidth
    }

    /** Fewer frames than this is not a panorama; it is a thumbnail or a stray. */
    private const val MIN_FRAMES = 4

    /** No tile in the wild is outside this, and it keeps a bad walk from allocating. */
    private const val MIN_TILE = 16
    private const val MAX_TILE = 4096

    /**
     * A full turn over a limited tilt cannot be squarer than 2:1, and single-node
     * panoramas of this era run to about 5:1. Past 8:1 the likeliest explanation is
     * several nodes stacked end to end - a 13-node file lands near 47:1 - and a
     * wrongly accepted multi-node scene is worse than a refused panorama.
     */
    private const val MIN_ASPECT = 2.0
    private const val MAX_ASPECT = 8.0

    /**
     * The frames in an `mdat`, or null if what is there cannot be trusted.
     *
     * Null is the common answer and the safe one. Anything that is not clearly a
     * stack of identical Cinepak tiles is left for the ordinary path to refuse with
     * its own message.
     */
    fun scan(data: ByteArray): Media? {
        val mdat = mdatPayload(data) ?: return null
        val samples = walkCinepak(data, mdat.first, mdat.second) ?: return null
        if (samples.size < MIN_FRAMES) return null

        val w = readU16(data, samples[0].first + 4)
        val h = readU16(data, samples[0].first + 6)
        val media = Media(samples, w, h)
        return if (plausible(media)) media else null
    }

    /**
     * Whether a rebuilt table describes something worth decoding.
     *
     * Separate and internal so the thresholds can be exercised directly. The aspect
     * test is the one doing real work: it cannot prove a file is single-node, only
     * make it very unlikely that a multi-node one is mistaken for a panorama.
     */
    internal fun plausible(m: Media): Boolean {
        if (m.tileWidth !in MIN_TILE..MAX_TILE) return false
        if (m.tileHeight !in MIN_TILE..MAX_TILE) return false
        if (m.samples.size < MIN_FRAMES) return false
        val aspect = m.panoramaWidth.toDouble() / m.panoramaHeight
        return aspect in MIN_ASPECT..MAX_ASPECT
    }

    /**
     * The payload of the first top-level `mdat`, but only if the file has no `moov`.
     *
     * The whole atom list has to be walked before answering. Stopping at the first
     * `mdat` looks equivalent and is not: `flatten.py` appends the recovered header
     * *after* the media, so a perfectly good flattened file has its `mdat` first and
     * would have been rebuilt from scratch - throwing away the real descriptor, the
     * real sample table, and the node count that goes with it. A file that has a
     * header must always use it.
     */
    private fun mdatPayload(data: ByteArray): Pair<Int, Int>? {
        var media: Pair<Int, Int>? = null
        var pos = 0
        while (pos + 8 <= data.size) {
            val size = readU32(data, pos)
            val type = String(data, pos + 4, 4, Charsets.ISO_8859_1)
            if (size < 8) return null
            if (type == "moov") return null          // it has a header; use it
            if (type == "mdat" && media == null) media = (pos + 8) to minOf(pos + size, data.size)
            pos += size
        }
        return media
    }

    /**
     * Walk Cinepak frames from [start] to [end].
     *
     * A Cinepak frame is a flag byte, a 24-bit length covering the whole frame, then
     * width, height and strip count as 16-bit values. Requiring every frame to share
     * dimensions and the walk to land on the end of the media is what separates a
     * real stack of tiles from bytes that happen to start plausibly.
     */
    private fun walkCinepak(data: ByteArray, start: Int, end: Int): List<Pair<Int, Int>>? {
        val out = ArrayList<Pair<Int, Int>>()
        var pos = start
        var w = -1
        var h = -1
        while (pos + 10 <= end) {
            val flag = data[pos].toInt() and 0xFF
            val len = ((data[pos + 1].toInt() and 0xFF) shl 16) or
                ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
            val fw = readU16(data, pos + 4)
            val fh = readU16(data, pos + 6)
            val strips = readU16(data, pos + 8)

            // Flags outside 0/1 are not Cinepak; a length that overruns the media, or
            // one small enough to loop forever, means the walk has lost its place.
            if (flag > 1 || len < 10 || pos + len > end) return null
            if (fw !in MIN_TILE..MAX_TILE || fh !in MIN_TILE..MAX_TILE) return null
            if (strips == 0 || strips > 64) return null
            if (w == -1) { w = fw; h = fh } else if (fw != w || fh != h) return null

            out.add(pos to len)
            pos += len
        }
        // The frames must account for the media. A few bytes of padding at the end is
        // normal; a large remainder means this was never a plain stack of tiles.
        if (end - pos > 16) return null
        return if (out.isEmpty()) null else out
    }

    private fun readU16(d: ByteArray, at: Int): Int =
        ((d[at].toInt() and 0xFF) shl 8) or (d[at + 1].toInt() and 0xFF)

    private fun readU32(d: ByteArray, at: Int): Int =
        ((d[at].toInt() and 0xFF) shl 24) or ((d[at + 1].toInt() and 0xFF) shl 16) or
            ((d[at + 2].toInt() and 0xFF) shl 8) or (d[at + 3].toInt() and 0xFF)
}
