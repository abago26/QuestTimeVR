package com.questtime.vr

import kotlin.math.PI

/**
 * The extraction pipeline: QuickTime file in, panorama out.
 *
 * No Android APIs here on the Cinepak path, so DecoderTest can run the whole thing
 * on the JVM and diff it against ffmpeg. [JpegDecoder] is the one seam where the
 * Android layer injects BitmapFactory for Photo-JPEG tiles.
 */

/** Decoded panorama, upright, ready to hand to the compositor. */
class Panorama(
    val rgb: ByteArray,
    val width: Int,
    val height: Int,
    val info: PanoInfo?,
) {
    /**
     * Horizontal sweep in radians, for the cylinder layer's centralAngle.
     * Falls back to a full turn when the descriptor is missing or nonsensical.
     */
    val centralAngle: Float
        get() {
            val sweep = info?.horizontalSweep ?: 360.0
            val deg = if (sweep > 0.5 && sweep <= 360.0) sweep else 360.0
            return (deg * PI / 180.0).toFloat()
        }

    /**
     * Aspect ratio for the cylinder layer. The compositor derives the vertical
     * angular extent from this and centralAngle, which is why we never reproject:
     * a cylinder layer *is* a cylindrical projection.
     *
     * Note this is deliberately taken from the pixels rather than from the
     * descriptor's vPan fields. For a 2496x768 full-turn panorama the pixel
     * geometry gives +/-44.03 degrees, where vPan claims +/-42.5; the two are not
     * self-consistent and the pixels are what actually has to line up.
     */
    val aspectRatio: Float get() = width.toFloat() / height.toFloat()

    /** Convert to RGBA8888 for texture upload. */
    fun toRgba(): ByteArray {
        val out = ByteArray(width * height * 4)
        var s = 0
        var d = 0
        while (s < rgb.size) {
            out[d] = rgb[s]
            out[d + 1] = rgb[s + 1]
            out[d + 2] = rgb[s + 2]
            out[d + 3] = -1                     // 0xFF
            s += 3
            d += 4
        }
        return out
    }
}

/** Injected by the Android layer so the core stays testable on the JVM. */
fun interface JpegDecoder {
    /** Decode a JPEG frame to RGB24 of exactly width*height*3, or null. */
    fun decode(data: ByteArray, width: Int, height: Int): ByteArray?
}

/**
 * A cubic panorama: six square faces in QuickTime VR's own order -
 * front, right, back, left, top, bottom - each RGB24 and [size] x [size].
 */
class CubeScene(val faces: List<ByteArray>, val size: Int, val info: PanoInfo?)

/**
 * What [Qtvr.inspect] concluded about a file, phrased for someone who is about to
 * decide whether to bother putting it on a headset.
 */
data class Verdict(
    /** Whether the app will actually open it. */
    val opens: Boolean,
    /** One line naming what the file is. */
    val summary: String,
    /** Why it will not open. Empty when it will. */
    val detail: String,
) {
    /** Nothing that cannot be opened earns space on the headset. */
    val worthKeeping: Boolean get() = opens
}

class UnsupportedCodec(val fourCC: String) :
    Exception("This file's frames are '$fourCC', which QuestTime can't decode yet.")

object Qtvr {

    /** Codecs we can actually turn into pixels today. */
    val SUPPORTED = setOf("cvid", "jpeg", "mjpa")

    /**
     * Cut one sample out of the file.
     *
     * Sample offsets and sizes are read straight off disk, so a truncated download
     * or a file that is not what it claims can point past the end. Saying so is
     * worth more to whoever hits it than an array index would be.
     */
    private fun slice(data: ByteArray, off: Long, size: Int): ByteArray {
        if (off < 0 || size <= 0 || off + size > data.size) {
            throw IllegalArgumentException(
                "This file's sample table points outside the file - it looks truncated."
            )
        }
        return data.copyOfRange(off.toInt(), (off + size).toInt())
    }

    /**
     * Decode one panorama out of [data].
     *
     * [node] is an index into [nodes], and matters only for a scene: a file with one
     * node ignores it. It is an index rather than a node id on purpose - the image
     * track is partitioned in storage order, and ids have gaps.
     */
    fun extract(data: ByteArray, jpeg: JpegDecoder? = null, node: Int = 0): Panorama {
        // A file whose header never arrived can sometimes be rebuilt from its media.
        // Tried before parsing, because parsing is what fails on it.
        rebuildHeaderless(data)?.let { return it }

        val tracks = MovParser.parseTracks(data)

        // 1.0 puts the descriptor in the 'pano' sample description (handler 'STpn').
        // 2.x uses a 'qtvr' track for the scene and a track whose *handler* is 'pano'
        // whose single sample carries the descriptor.
        val v1Track = tracks.firstOrNull { it.format == "pano" }
        val v2Track = tracks.firstOrNull { it.handler == "pano" && it !== v1Track }
        val hasQtvrTrack = tracks.any { it.handler == "qtvr" || it.format == "qtvr" }

        // Object tracks announce themselves by *handler*, not by sample format - the
        // 'obji' format check below never matched a real file. Caught when a 25-node
        // scene with objects in it was reported as an ordinary movie.
        val hasObject = tracks.any {
            it.handler == "obje" || it.format == "obji" || it.format == "obje"
        }
        if (hasObject && v1Track == null && v2Track == null) throw IllegalArgumentException(
            "This is a QuickTime VR object movie, not a panorama."
        )

        val info: PanoInfo = when {
            v1Track != null -> PanoInfo.parse(v1Track.sampleDescription)
            v2Track != null -> {
                val r = v2Track.sampleRanges().firstOrNull()
                    ?: throw IllegalArgumentException("Panorama track carries no descriptor.")
                PanoInfo.parseV2(slice(data, r.first, r.second))
            }
            else -> null
        } ?: throw IllegalArgumentException(
            if (hasQtvrTrack)
                "This is a QuickTime VR file, but its panorama descriptor could not be read."
            else
                "No QuickTime VR panorama track here - this looks like an ordinary movie."
        )

        // A 2.x scene gives each node its own image track and names it through a
        // track reference, rather than partitioning one track the way 1.0 does. No
        // multi-node 2.x file was available to check that against, and a scene taken
        // apart the wrong way looks exactly like a panorama, so it is still refused.
        if (v1Track == null) nodeCount(v2Track)?.let { n ->
            if (n > 1) throw IllegalArgumentException(
                "This is a $n-node QuickTime VR 2.x scene. QuestTime can take 1.0 scenes " +
                    "apart, but 2.x stores its nodes differently and none was available " +
                    "to test against."
            )
        }

        if (info.isCubic) throw IllegalArgumentException(
            "This is a cubic QuickTime VR panorama. QuestTime reads cylindrical ones; " +
                "cubic needs six faces mapped to a cubemap rather than a cylinder."
        )
        if (hasObject) throw IllegalArgumentException(
            "This is a QuickTime VR object movie, not a panorama."
        )
        // QuickTime 5 allowed panoramas to be stored upright rather than on their
        // side. Rotating one of those would render it lying down, silently. No
        // sample of that form was available to test against, so say so instead:
        // a wrong panorama that looks deliberate is worse than a refusal.
        if (!info.storedRotated) throw IllegalArgumentException(
            "This panorama is stored upright ('${info.panoType.ifEmpty { "flags" }}'), " +
                "which QuestTime has never been able to test against. It would very " +
                "likely appear on its side, so it is refused rather than guessed at."
        )

        val video = imageTrack(tracks, info)

        val ranges = video.sampleRanges()
        if (ranges.isEmpty()) throw IllegalArgumentException("Video track has no samples.")

        val mine = nodeSamples(ranges, info, nodeCount(v1Track) ?: 1, node)
        val tiles = decodeTiles(data, video, mine, jpeg)

        val cols = if (info.framesX > 0) info.framesX else 1
        val rows = if (info.framesY > 0) info.framesY else tiles.size
        val (stacked, sw, sh) = assemble(tiles, video.width, video.height, cols, rows)
        val (upright, w, h) = rotateCw(stacked, sw, sh)
        return Panorama(upright, w, h, info)
    }

    /**
     * A panorama rebuilt from media alone, or null if this is not that situation.
     *
     * Only when there is an `mdat` and no `moov`, and only when [Headerless] is
     * confident about what it found - see the guards there, particularly the aspect
     * test that keeps multi-node scenes out. Everything after the scan is the
     * ordinary path: the same Cinepak decoder, the same stacking, the same rotation.
     *
     * [PanoInfo] is left at its defaults on purpose. Those defaults are a full turn
     * and a vertical extent derived from pixel aspect, which is what the app uses for
     * every file anyway - the header's own angles are already distrusted.
     */
    /**
     * What [inspect] should say about a headerless file, or null if it is beyond us.
     *
     * "rebuilt" is in the summary deliberately. The geometry was inferred from the
     * pixels rather than read from the file, and someone comparing this against the
     * original deserves to know which one they are looking at.
     */
    private fun headerlessVerdict(data: ByteArray): Verdict? {
        val m = Headerless.scan(data) ?: return null
        return Verdict(
            opens = true,
            summary = "QuickTime VR 1.0 cylindrical, " +
                "${m.panoramaWidth}x${m.panoramaHeight}, cvid (rebuilt)",
            detail = "",
        )
    }

    private fun rebuildHeaderless(data: ByteArray): Panorama? {
        val media = Headerless.scan(data) ?: return null
        val dec = Cinepak(media.tileWidth, media.tileHeight)
        val tiles = media.samples.map { (off, size) ->
            dec.decode(slice(data, off.toLong(), size)).copyOf()
        }
        val (stacked, sw, sh) =
            assemble(tiles, media.tileWidth, media.tileHeight, 1, tiles.size)
        val (upright, w, h) = rotateCw(stacked, sw, sh)
        // No descriptor, and none is invented: a full turn is what every panorama of
        // this era is, and the vertical extent comes from pixel aspect regardless of
        // what a descriptor would have claimed.
        return Panorama(upright, w, h, null)
    }

    /**
     * Say whether a file will open, without decoding a pixel.
     *
     * This deliberately mirrors [extract]'s refusals rather than sharing code with
     * them: extract throws at the first problem because it is trying to produce an
     * image, while this is trying to produce an explanation, and wants the file's
     * identity even when the answer is no.
     */
    fun inspect(data: ByteArray): Verdict {
        if (data.size < 16) return no("Not a QuickTime file", "Too small to hold a movie header.")

        val tracks = runCatching { MovParser.parseTracks(data) }.getOrElse { e ->
            val top = topLevelAtoms(data)
            // The single most likely failure for anything arriving over HTTP: a
            // classic Mac movie whose header is in the resource fork. The media is
            // all here, which is why saying so is worth more than "not a QuickTime
            // file" - the file is fine, the transfer could not carry all of it.
            return if ("mdat" in top && "moov" !in top)
                // The advice has to be reachable from where the person is standing.
                // This used to name reference/flatten.py, which only exists if you
                // cloned the repository - useless to someone who has a browser and a
                // folder of old files, which is everyone this message is for.
                headerlessVerdict(data)
                    // extract rebuilds what it can, so inspect must agree with it -
                    // the two are only useful if they refuse the same things.
                    ?: no("Classic Mac file, header missing",
                    "The media is here but the 'moov' header is not - on a Mac it lives in " +
                        "the resource fork, which a browser cannot upload on its own. " +
                        "Select the originals in Finder, right-click, Compress, and send " +
                        "the .zip: the fork travels inside it and is put back on arrival.")
            else no("Not a QuickTime file", e.message ?: "No 'moov' atom.")
        }

        val hasObject = tracks.any {
            it.handler == "obje" || it.format == "obji" || it.format == "obje"
        }

        val v1 = tracks.firstOrNull { it.format == "pano" }
        val v2 = tracks.firstOrNull { it.handler == "pano" && it !== v1 }
        val isQtvr = tracks.any { it.handler == "qtvr" || it.format == "qtvr" } ||
            v1 != null || v2 != null
        val info = readInfo(tracks, data) ?: return when {
            hasObject -> no("QuickTime VR object movie",
                "An object you spin, not a panorama you stand inside.")
            isQtvr -> no("QuickTime VR, but unreadable",
                "Its panorama descriptor could not be read.")
            else -> no("An ordinary movie", "There is no QuickTime VR panorama track in it.")
        }

        val nodes = nodeCount(v1 ?: v2) ?: 1
        val video = runCatching { imageTrack(tracks, info) }.getOrNull()
        val anyVideo = tracks.firstOrNull { it.handler == "vide" && it.width > 0 }
        val codec = (video ?: anyVideo)?.format?.trim() ?: "?"
        val shape = if (info.isCubic) "cubic" else "cylindrical"
        val size = if (info.isCubic) "${anyVideo?.width ?: 0} px faces"
            else "${info.panoWidth}x${info.panoHeight}"
        val version = if (info.majorVersion >= 2) "2.x" else "1.0"
        val summary = "QuickTime VR $version $shape, $size, $codec" +
            if (nodes > 1) ", $nodes nodes" else ""

        // Scenes open a node at a time now, so the count is description rather than
        // refusal - but only for 1.0, which is the arrangement that was measured.
        // extract() refuses 2.x scenes at exactly this point, and these two must
        // never disagree about a file.
        if (nodes > 1 && v1 == null) return no(summary,
            "A 2.x scene stores its nodes differently from the 1.0 scenes this can " +
                "take apart, and none was available to test against.")
        // And the same arithmetic extract() refuses on: a scene whose images do not
        // divide evenly among its nodes is one where the boundaries are unknown.
        // Predicting that here is the whole job of this function.
        if (nodes > 1 && video != null) {
            val count = video.sampleRanges().size
            if (info.numFrames <= 0 || info.numFrames * nodes != count) return no(summary,
                "Its $count images do not divide evenly among $nodes nodes, so where " +
                    "one node ends and the next begins cannot be worked out.")
        }
        // Same position extract() refuses at: after the node count, before the
        // rotation check. A file can carry a one-node panorama descriptor *and* be an
        // object movie, and without this inspect called Maranello openable while
        // extract threw on it - the two disagreeing is the one thing this must not do.
        if (hasObject) return no(summary,
            "It is a QuickTime VR object movie - something you spin, not a panorama " +
                "you stand inside.")

        // Cylindrical only. A cubic panorama is never rotated before dicing, so it
        // legitimately carries the "not rotated" bit - extract() never reaches this
        // check for one because isCubic diverts first, and applying it here refused
        // a perfectly good cube.
        if (!info.isCubic && !info.storedRotated) return no(summary,
            "Stored upright rather than on its side - a form this has never had a sample " +
                "to test against, so it is refused rather than shown lying down.")
        if (video == null) return no(summary, "'$codec' is not a codec this can decode.")
        return Verdict(true, summary, "")
    }

    private fun no(summary: String, detail: String) = Verdict(false, summary, detail)

    /** Top-level atom types only - enough to tell a headerless file from a broken one. */
    private fun topLevelAtoms(d: ByteArray): List<String> {
        val out = ArrayList<String>()
        var pos = 0
        while (pos + 8 <= d.size && out.size < 32) {
            var size = d.u32(pos)
            out.add(d.fourCC(pos + 4))
            if (size == 1L) {
                if (pos + 16 > d.size) break
                size = 0
                for (k in 0 until 8) size = (size shl 8) or (d[pos + 8 + k].toLong() and 0xFF)
            } else if (size == 0L) break
            if (size < 8 || pos + size > d.size) break
            pos += size.toInt()
        }
        return out
    }

    /**
     * Pick the track holding the panorama image.
     *
     * Cubic files carry a second video track for the hot-spot mask - usually 'smc'
     * (Apple Graphics), the same dimensions as the image. Choosing the first video
     * track would sometimes grab that, so prefer one whose codec we can actually
     * decode and fall back only if none matches.
     *
     * Scenes add a second trap the codec test cannot see. Lincoln Memorial carries
     * two Cinepak tracks - 768x168 and 192x84 - the smaller one a low-resolution
     * copy for scrubbing. Both decode, so "first supported codec" picked the right
     * one only because it happened to be stored first. When [info] is known the
     * descriptor settles it instead: the image track is the one whose tiles are the
     * width of the scene and stack to its height.
     */
    fun imageTrack(tracks: List<Track>, info: PanoInfo? = null): Track {
        val videos = tracks.filter { it.handler == "vide" && it.width > 0 }
        if (videos.isEmpty()) throw IllegalArgumentException("No video track - nothing to decode.")
        val usable = videos.filter { it.format in SUPPORTED }
        if (usable.isEmpty()) throw UnsupportedCodec(videos.first().format)
        return usable.firstOrNull { matchesScene(it, info) } ?: usable.first()
    }

    /** Whether this track's tiles are the ones the descriptor is describing. */
    private fun matchesScene(t: Track, info: PanoInfo?): Boolean {
        if (info == null || info.numFrames <= 0) return false
        return t.width == info.sceneSizeX && t.height * info.numFrames == info.sceneSizeY
    }

    /** How many panorama nodes the file describes - one sample each. */
    private fun nodeCount(panoTrack: Track?): Int? =
        panoTrack?.sampleRanges()?.size

    /**
     * The slice of the image track belonging to one node.
     *
     * Every node in a 1.0 scene has the same tile count - the descriptor's
     * numFrames - and they are stored back to back in node order, so node k owns
     * samples [k*n, (k+1)*n). Measured on all four scenes in a real archive:
     * 9x24=216, 13x24=312, 33x24=792, 35x24=840, each exactly the track's length.
     *
     * That exactness is the check, and it has to refuse rather than fall back. The
     * tempting fallback - hand back the whole track when the arithmetic does not
     * come out - is precisely how all the nodes end up stacked into one column nine
     * times too long, which is the thing this exists to prevent and which looks like
     * a panorama when it happens. There is no aspect guard on this path to catch it
     * afterwards; [Headerless] has one, a file with a header does not.
     */
    private fun nodeSamples(
        ranges: List<Pair<Long, Int>>, info: PanoInfo, nodes: Int, node: Int,
    ): List<Pair<Long, Int>> {
        if (nodes <= 1) return ranges
        if (node !in 0 until nodes) throw IllegalArgumentException(
            "This scene has $nodes nodes; there is no node ${node + 1}."
        )
        val per = if (info.numFrames > 0) info.numFrames else ranges.size / nodes
        if (per <= 0 || per * nodes != ranges.size) throw IllegalArgumentException(
            "This $nodes-node scene stores ${ranges.size} images, which is not " +
                "$nodes lots of $per - QuestTime cannot tell where one node ends " +
                "and the next begins, and will not guess."
        )
        return ranges.subList(node * per, (node + 1) * per)
    }

    /**
     * The scene's nodes, in storage order, or empty if this is not a 1.0 scene.
     *
     * Storage order is the point: [VrNode.index] is what [extract] takes, because
     * the image track is partitioned in that order and the node's own id is not a
     * position - see the note on [VrNode.id].
     */
    fun nodes(data: ByteArray): List<VrNode> = runCatching {
        val tracks = MovParser.parseTracks(data)
        val v1 = tracks.firstOrNull { it.format == "pano" } ?: return emptyList()
        NodeTable.parse(v1.sampleRanges().map { (off, size) -> slice(data, off, size) })
    }.getOrDefault(emptyList())

    /** True if this file is a cubic panorama rather than a cylindrical one. */
    fun isCubic(data: ByteArray): Boolean = runCatching {
        readInfo(MovParser.parseTracks(data), data)?.isCubic == true
    }.getOrDefault(false)

    private fun readInfo(tracks: List<Track>, data: ByteArray): PanoInfo? {
        val v1 = tracks.firstOrNull { it.format == "pano" }
        if (v1 != null) return PanoInfo.parse(v1.sampleDescription)
        val v2 = tracks.firstOrNull { it.handler == "pano" && it !== v1 } ?: return null
        val r = v2.sampleRanges().firstOrNull() ?: return null
        return PanoInfo.parseV2(slice(data, r.first, r.second))
    }

    /** Decode the six faces of a cubic panorama, in QuickTime VR's stored order. */
    fun extractCube(data: ByteArray, jpeg: JpegDecoder? = null): CubeScene {
        val tracks = MovParser.parseTracks(data)
        val info = readInfo(tracks, data)
            ?: throw IllegalArgumentException("No QuickTime VR panorama descriptor here.")
        if (!info.isCubic) throw IllegalArgumentException("This panorama is not cubic.")

        val video = imageTrack(tracks)
        if (video.width != video.height) throw IllegalArgumentException(
            "Cube faces should be square, but this track is ${video.width}x${video.height}."
        )
        val ranges = video.sampleRanges()
        if (ranges.size < 6) throw IllegalArgumentException(
            "A cubic panorama needs six faces; this track has ${ranges.size}."
        )

        val stored = decodeTiles(data, video, ranges.take(6), jpeg)
        val gl = toGlOrder(stored, video.width)
        val filled = CubeCaps.fill(gl, video.width, info.vPanBottom, info.vPanTop)
        return CubeScene(filled, video.width, info)
    }

    /**
     * QuickTime VR stores cube faces front, right, back, left, top, bottom. OpenGL
     * wants +X, -X, +Y, -Y, +Z, -Z, and in OpenXR's axes forward is -Z and right is
     * +X - hence this order. Each face is also mirrored horizontally: GL's cubemap
     * convention is left-handed, deriving s from -x on the -Z face, so an unmirrored
     * face shows every sign back to front.
     *
     * Doing this here rather than at upload time means the gradient fill can reason
     * about real directions, and it is testable without a headset.
     */
    fun toGlOrder(stored: List<ByteArray>, n: Int): List<ByteArray> {
        val perm = intArrayOf(1, 3, 4, 5, 2, 0)
        return perm.map { src ->
            val face = stored[src]
            val out = ByteArray(n * n * 3)
            for (y in 0 until n) {
                val row = y * n * 3
                for (x in 0 until n) {
                    val from = row + (n - 1 - x) * 3
                    val to = row + x * 3
                    out[to] = face[from]
                    out[to + 1] = face[from + 1]
                    out[to + 2] = face[from + 2]
                }
            }
            out
        }
    }

    private fun decodeTiles(
        data: ByteArray, video: Track, ranges: List<Pair<Long, Int>>, jpeg: JpegDecoder?,
    ): List<ByteArray> = when (video.format) {
        "cvid" -> {
            val dec = Cinepak(video.width, video.height)
            ranges.map { (off, size) -> dec.decode(slice(data, off, size)).copyOf() }
        }
        "jpeg", "mjpa" -> {
            val d = jpeg ?: throw UnsupportedCodec(video.format)
            ranges.map { (off, size) ->
                d.decode(slice(data, off, size), video.width, video.height)
                    ?: throw IllegalArgumentException("A JPEG tile failed to decode.")
            }
        }
        else -> throw UnsupportedCodec(video.format)
    }

    /**
     * Lay the tiles out as a [cols] x [rows] grid, row-major. Both sample files are
     * a single column, which is the usual QuickTime VR arrangement; the grid case is
     * handled for completeness but has not been seen in the wild here.
     */
    fun assemble(
        tiles: List<ByteArray>, tileW: Int, tileH: Int, cols: Int, rows: Int,
    ): Triple<ByteArray, Int, Int> {
        if (cols <= 1) return stack(tiles, tileW, tileH)
        val w = tileW * cols
        val h = tileH * rows
        val out = ByteArray(w * h * 3)
        for ((i, t) in tiles.withIndex()) {
            val cx = (i % cols) * tileW
            val cy = (i / cols) * tileH
            if (cy + tileH > h) break
            for (y in 0 until tileH) {
                val src = y * tileW * 3
                val dst = ((cy + y) * w + cx) * 3
                if (src + tileW * 3 > t.size) break
                t.copyInto(out, dst, src, src + tileW * 3)
            }
        }
        return Triple(out, w, h)
    }

    /**
     * Shrink so neither side exceeds [maxDim], scaling both axes equally.
     *
     * Quest 3 reports maxSwapchainImageWidth/Height of 8192 while allowing 16384
     * textures, so a big 2.x panorama (the chapel sample is 8832 wide) has to come
     * down. Scaling uniformly leaves the aspect ratio - and therefore every angle
     * the compositor derives from it - exactly as it was. At 8192 across a full
     * turn the texture is already finer than the headset can resolve, so this
     * costs nothing visible.
     */
    fun downscaleToFit(pano: Panorama, maxDim: Int): Panorama {
        val longest = maxOf(pano.width, pano.height)
        if (longest <= maxDim) return pano
        val scale = maxDim.toDouble() / longest
        val nw = maxOf(1, (pano.width * scale).toInt())
        val nh = maxOf(1, (pano.height * scale).toInt())
        val out = ByteArray(nw * nh * 3)

        // Area average: each destination pixel is the mean of the source box it
        // covers, which is what keeps a downscale from aliasing into shimmer.
        val xr = pano.width.toDouble() / nw
        val yr = pano.height.toDouble() / nh
        for (y in 0 until nh) {
            val y0 = (y * yr).toInt()
            val y1 = minOf(pano.height, maxOf(y0 + 1, ((y + 1) * yr).toInt()))
            for (x in 0 until nw) {
                val x0 = (x * xr).toInt()
                val x1 = minOf(pano.width, maxOf(x0 + 1, ((x + 1) * xr).toInt()))
                var r = 0; var g = 0; var b = 0; var n = 0
                for (sy in y0 until y1) {
                    var o = (sy * pano.width + x0) * 3
                    for (sx in x0 until x1) {
                        r += pano.rgb[o].toInt() and 0xFF
                        g += pano.rgb[o + 1].toInt() and 0xFF
                        b += pano.rgb[o + 2].toInt() and 0xFF
                        o += 3
                        n++
                    }
                }
                val d = (y * nw + x) * 3
                out[d] = (r / n).toByte()
                out[d + 1] = (g / n).toByte()
                out[d + 2] = (b / n).toByte()
            }
        }
        return Panorama(out, nw, nh, pano.info)
    }

    /** Stack tiles vertically - QTVR stores the panorama as a single column. */
    fun stack(tiles: List<ByteArray>, tileW: Int, tileH: Int): Triple<ByteArray, Int, Int> {
        val h = tileH * tiles.size
        val out = ByteArray(tileW * h * 3)
        val stride = tileW * tileH * 3
        for ((i, t) in tiles.withIndex()) {
            t.copyInto(out, i * stride, 0, minOf(stride, t.size))
        }
        return Triple(out, tileW, h)
    }

    /** Rotate 90 degrees clockwise. QTVR stores panoramas on their side. */
    fun rotateCw(src: ByteArray, w: Int, h: Int): Triple<ByteArray, Int, Int> {
        val out = ByteArray(w * h * 3)
        val nw = h
        for (y in 0 until h) {
            val rowBase = y * w * 3
            val dstX = h - 1 - y
            for (x in 0 until w) {
                val s = rowBase + x * 3
                val d = (x * nw + dstX) * 3
                out[d] = src[s]
                out[d + 1] = src[s + 1]
                out[d + 2] = src[s + 2]
            }
        }
        return Triple(out, h, w)
    }
}
