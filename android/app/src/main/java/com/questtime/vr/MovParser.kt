package com.questtime.vr

/**
 * QuickTime container parsing, plus the QTVR 1.0 'pano' sample description.
 *
 * Deliberately free of Android APIs so it can be unit-tested on the JVM against
 * ffmpeg's output. See app/src/test - DecoderTest checks this end to end.
 */

internal fun ByteArray.u16(o: Int): Int =
    ((this[o].toInt() and 0xFF) shl 8) or (this[o + 1].toInt() and 0xFF)

internal fun ByteArray.u32(o: Int): Long =
    ((this[o].toLong() and 0xFF) shl 24) or ((this[o + 1].toLong() and 0xFF) shl 16) or
        ((this[o + 2].toLong() and 0xFF) shl 8) or (this[o + 3].toLong() and 0xFF)

internal fun ByteArray.i32(o: Int): Int =
    ((this[o].toInt() and 0xFF) shl 24) or ((this[o + 1].toInt() and 0xFF) shl 16) or
        ((this[o + 2].toInt() and 0xFF) shl 8) or (this[o + 3].toInt() and 0xFF)

/** 16.16 fixed point, as QuickTime stores angles. */
internal fun ByteArray.fixed(o: Int): Double = i32(o) / 65536.0

internal fun ByteArray.fourCC(o: Int): String =
    String(this, o, 4, Charsets.ISO_8859_1)

class Track {
    var handler: String = ""
    var format: String = ""
    var width: Int = 0
    var height: Int = 0
    var depth: Int = 0
    var sampleDescription: ByteArray = ByteArray(0)
    var sampleSizes: IntArray = IntArray(0)
    var chunkOffsets: LongArray = LongArray(0)
    /** triples of (firstChunk, samplesPerChunk, descriptionIndex) */
    var sampleToChunk: Array<IntArray> = emptyArray()

    /** Flatten the sample tables into absolute (offset, size) pairs, in order. */
    fun sampleRanges(): List<Pair<Long, Int>> {
        val out = ArrayList<Pair<Long, Int>>(sampleSizes.size)
        var sample = 0
        for (i in sampleToChunk.indices) {
            val first = sampleToChunk[i][0]
            val perChunk = sampleToChunk[i][1]
            val last = if (i + 1 < sampleToChunk.size) sampleToChunk[i + 1][0] - 1 else chunkOffsets.size
            for (chunk in first..last) {
                if (chunk - 1 >= chunkOffsets.size) break
                var pos = chunkOffsets[chunk - 1]
                for (n in 0 until perChunk) {
                    if (sample >= sampleSizes.size) break
                    val size = sampleSizes[sample]
                    out.add(pos to size)
                    pos += size
                    sample++
                }
            }
        }
        return out
    }
}

/**
 * Panorama geometry, normalised across QuickTime VR versions.
 *
 * 1.0 keeps this in the 'pano' sample *description* as 16.16 fixed point. 2.x moved
 * it into the pano track's *sample*, wrapped in a QuickTime atom container ('sean'
 * -> 'pdat'), with angles as 32-bit floats. Same information, different envelope.
 */
data class PanoInfo(
    val hPanStart: Double,
    val hPanEnd: Double,
    val vPanTop: Double,
    val vPanBottom: Double,
    val sceneSizeX: Int,
    val sceneSizeY: Int,
    val numFrames: Int,
    val framesX: Int,
    val framesY: Int,
    val hotSpotSizeX: Int,
    val hotSpotSizeY: Int,
    /** 1 for QTVR 1.0, 2 for 2.x. */
    val majorVersion: Int = 1,
    /**
     * 2.x only. 'hcyl' horizontal cylinder, 'vcyl' vertical cylinder, 'cube' cubic,
     * or blank - in which case [flags] carries the orientation instead.
     */
    val panoType: String = "",
    /** 2.x only. Bit 0 set means the frames were *not* rotated before dicing. */
    val flags: Long = 0,
) {
    val isCubic get() = panoType == "cube"

    /**
     * Whether the image was rotated 90 degrees counter-clockwise before being diced,
     * and so has to be rotated back.
     *
     * Every QuickTime VR movie before QuickTime 5 stored panoramas on their side, so
     * that a 1990s decoder could stream them a column at a time. QuickTime 5 dropped
     * the requirement: an 'hcyl' panorama is stored upright and must NOT be rotated.
     * Where panoType is blank the low bit of flags says which, set meaning upright.
     *
     * Both sample files here are the rotated form, so the unrotated case is
     * recognised but refused rather than guessed at - see Qtvr.extract.
     */
    val storedRotated: Boolean
        get() = when (panoType) {
            "vcyl" -> true
            "hcyl" -> false
            "cube" -> false          // faces are stored face-up; the cube path never rotates
            else -> (flags and 1L) == 0L
        }

    /** Width once the stored image has been rotated upright. */
    val panoWidth get() = sceneSizeY
    val panoHeight get() = sceneSizeX

    /** Horizontal sweep in degrees; 360 for a full turn. */
    val horizontalSweep get() = hPanEnd - hPanStart

    companion object {
        /**
         * QTVR 2.x: the sample is a QuickTime atom container. Rather than walk the
         * whole container we locate the 'pdat' atom directly - its payload begins
         * 16 bytes past the type tag.
         */
        fun parseV2(sample: ByteArray): PanoInfo? {
            var at = -1
            var i = 0
            while (i + 4 <= sample.size) {
                if (sample[i] == 'p'.code.toByte() && sample.fourCC(i) == "pdat") { at = i; break }
                i++
            }
            if (at < 0) return null
            val p = at + 16
            if (p + 80 > sample.size) return null

            fun f(k: Int) = Float.fromBits(sample.i32(p + k)).toDouble()
            fun u(k: Int) = sample.u32(p + k).toInt()
            fun h(k: Int) = sample.u16(p + k)

            val type = sample.fourCC(p + 76).trim { it <= ' ' || it == '\u0000' }
            return PanoInfo(
                hPanStart = f(12),
                hPanEnd = f(16),
                vPanTop = f(24),          // maxTilt
                vPanBottom = f(20),       // minTilt
                sceneSizeX = u(48),
                sceneSizeY = u(52),
                numFrames = h(56) * h(58),
                framesX = h(56),
                framesY = h(58),
                hotSpotSizeX = u(60),
                hotSpotSizeY = u(64),
                majorVersion = h(0),
                panoType = type,
                flags = sample.u32(p + 72),
            )
        }

        fun parse(e: ByteArray): PanoInfo? {
            if (e.size < 152) return null
            return PanoInfo(
                hPanStart = e.fixed(92),
                hPanEnd = e.fixed(96),
                vPanTop = e.fixed(100),
                vPanBottom = e.fixed(104),
                sceneSizeX = e.u32(116).toInt(),
                sceneSizeY = e.u32(120).toInt(),
                numFrames = e.u32(124).toInt(),
                framesX = e.u16(130),
                framesY = e.u16(132),
                hotSpotSizeX = e.u32(136).toInt(),
                hotSpotSizeY = e.u32(140).toInt(),
                // 1.0 predates the choice: panoramas were always stored rotated.
                flags = 0,
            )
        }
    }
}

object MovParser {

    /** A table count off disk, held to what the atom can actually hold. */
    private fun Int.clampTo(room: Int): Int = this.coerceIn(0, maxOf(0, room))

    private class Atom(val type: String, val body: Int, val end: Int)

    private fun atoms(d: ByteArray, start: Int, end: Int): List<Atom> {
        val out = ArrayList<Atom>()
        var pos = start
        while (pos + 8 <= end) {
            var size = d.u32(pos)
            val type = d.fourCC(pos + 4)
            var body = pos + 8
            when {
                size == 1L -> {                       // 64-bit extended size
                    if (pos + 16 > end) break
                    size = 0
                    for (k in 0 until 8) size = (size shl 8) or (d[pos + 8 + k].toLong() and 0xFF)
                    body = pos + 16
                }
                size == 0L -> size = (end - pos).toLong()
            }
            if (size < 8 || pos + size > end) break
            out.add(Atom(type, body, (pos + size).toInt()))
            pos += size.toInt()
        }
        return out
    }

    private fun find(d: ByteArray, start: Int, end: Int, path: List<String>): Atom? {
        for (a in atoms(d, start, end)) {
            if (a.type == path[0]) {
                return if (path.size == 1) a else find(d, a.body, a.end, path.drop(1))
            }
        }
        return null
    }

    fun parseTracks(d: ByteArray): List<Track> {
        val moov = find(d, 0, d.size, listOf("moov"))
            ?: throw IllegalArgumentException("No 'moov' atom - this is not a QuickTime file.")

        val tracks = ArrayList<Track>()
        for (trak in atoms(d, moov.body, moov.end)) {
            if (trak.type != "trak") continue
            val t = Track()

            find(d, trak.body, trak.end, listOf("mdia", "hdlr"))?.let {
                // version/flags (4) + component type (4), then the subtype we want
                if (it.body + 12 <= d.size) t.handler = d.fourCC(it.body + 8)
            }

            val stbl = find(d, trak.body, trak.end, listOf("mdia", "minf", "stbl")) ?: continue

            find(d, stbl.body, stbl.end, listOf("stsd"))?.let {
                val base = it.body + 8                 // version/flags + entry count
                if (base + 16 <= it.end) {
                    val size = d.u32(base).toInt()
                    if (size in 16..(it.end - base)) {
                        t.sampleDescription = d.copyOfRange(base, base + size)
                        t.format = d.fourCC(base + 4)
                        if (t.handler == "vide" && size >= 86) {
                            t.width = d.u16(base + 32)
                            t.height = d.u16(base + 34)
                            t.depth = d.u16(base + 82)
                        }
                    }
                }
            }

            // Every count below comes straight off disk, so it is clamped to what
            // the atom actually has room for. A truncated or simply-not-QuickTime
            // file should reach the user as a sentence, not as an array index.
            find(d, stbl.body, stbl.end, listOf("stsz"))?.let {
                if (it.body + 12 > it.end) return@let
                val uniform = d.u32(it.body + 4).toInt()
                val count = d.u32(it.body + 8).toInt()
                t.sampleSizes = if (uniform > 0) {
                    IntArray(count.clampTo(d.size / uniform)) { uniform }
                } else {
                    IntArray(count.clampTo((it.end - it.body - 12) / 4)) { i ->
                        d.u32(it.body + 12 + 4 * i).toInt()
                    }
                }
            }

            find(d, stbl.body, stbl.end, listOf("stco"))?.let {
                if (it.body + 8 > it.end) return@let
                val n = d.u32(it.body + 4).toInt().clampTo((it.end - it.body - 8) / 4)
                t.chunkOffsets = LongArray(n) { i -> d.u32(it.body + 8 + 4 * i) }
            }
            find(d, stbl.body, stbl.end, listOf("co64"))?.let {
                if (it.body + 8 > it.end) return@let
                val n = d.u32(it.body + 4).toInt().clampTo((it.end - it.body - 8) / 8)
                t.chunkOffsets = LongArray(n) { i ->
                    var v = 0L
                    for (k in 0 until 8) v = (v shl 8) or (d[it.body + 8 + 8 * i + k].toLong() and 0xFF)
                    v
                }
            }

            find(d, stbl.body, stbl.end, listOf("stsc"))?.let {
                if (it.body + 8 > it.end) return@let
                val n = d.u32(it.body + 4).toInt().clampTo((it.end - it.body - 8) / 12)
                t.sampleToChunk = Array(n) { i ->
                    IntArray(3) { k -> d.u32(it.body + 8 + 12 * i + 4 * k).toInt() }
                }
            }

            tracks.add(t)
        }
        return tracks
    }
}
