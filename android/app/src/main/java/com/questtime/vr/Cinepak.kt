package com.questtime.vr

/**
 * Cinepak ('cvid') decoder.
 *
 * Verified byte-exact against ffmpeg's own decoder - see DecoderTest. Two details
 * are easy to get wrong and both are load-bearing:
 *
 *  - Codebooks persist across strips and frames. A strip after the first usually
 *    sends only a selective update (0x2100/0x2300), which applies on top of the
 *    previous strip's books, so those must be inherited before decoding.
 *  - The green channel uses truncation toward zero, not a floored shift. Kotlin's
 *    Int division already truncates, which is what matches the reference decoder.
 *
 * One instance decodes a whole track, in order.
 */
class Cinepak(private val width: Int, private val height: Int) {

    /** RGB24, width * height * 3. Persists between frames for inter-coded blocks. */
    val rgb = ByteArray(width * height * 3)

    // Codebooks are flat: 6 ints per entry (y0, y1, y2, y3, u, v), 256 entries.
    private val v1 = Array(MAX_STRIPS) { IntArray(256 * 6) }
    private val v4 = Array(MAX_STRIPS) { IntArray(256 * 6) }

    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

    private fun put(x: Int, y: Int, luma: Int, u: Int, v: Int) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        val o = (y * width + x) * 3
        rgb[o] = clamp(luma + 2 * v).toByte()
        rgb[o + 1] = clamp(luma - u / 2 - v).toByte()   // Int division truncates toward zero
        rgb[o + 2] = clamp(luma + 2 * u).toByte()
    }

    private fun readEntry(book: IntArray, idx: Int, data: ByteArray, pos: Int, gray: Boolean) {
        val b = idx * 6
        book[b] = data[pos].toInt() and 0xFF
        book[b + 1] = data[pos + 1].toInt() and 0xFF
        book[b + 2] = data[pos + 2].toInt() and 0xFF
        book[b + 3] = data[pos + 3].toInt() and 0xFF
        if (gray) {
            book[b + 4] = 0
            book[b + 5] = 0
        } else {
            book[b + 4] = data[pos + 4].toInt()          // already sign-extended
            book[b + 5] = data[pos + 5].toInt()
        }
    }

    private fun codebook(book: IntArray, chunkId: Int, data: ByteArray) {
        val gray = (chunkId and 0x0400) != 0
        val selective = (chunkId and 0x0100) != 0
        val n = if (gray) 4 else 6
        var pos = 0

        if (selective) {
            var idx = 0
            while (pos + 4 <= data.size && idx < 256) {
                val mask = data.i32(pos)
                pos += 4
                var bit = 0
                while (bit < 32 && idx < 256) {
                    if ((mask ushr (31 - bit)) and 1 == 1) {
                        if (pos + n > data.size) return
                        readEntry(book, idx, data, pos, gray)
                        pos += n
                    }
                    bit++
                    idx++
                }
            }
        } else {
            var idx = 0
            while (pos + n <= data.size && idx < 256) {
                readEntry(book, idx, data, pos, gray)
                pos += n
                idx++
            }
        }
    }

    private fun blockV1(x: Int, y: Int, book: IntArray, idx: Int) {
        val b = idx * 6
        val y0 = book[b]; val y1 = book[b + 1]; val y2 = book[b + 2]; val y3 = book[b + 3]
        val u = book[b + 4]; val v = book[b + 5]
        for (dy in 0 until 2) for (dx in 0 until 2) {
            put(x + dx, y + dy, y0, u, v)
            put(x + 2 + dx, y + dy, y1, u, v)
            put(x + dx, y + 2 + dy, y2, u, v)
            put(x + 2 + dx, y + 2 + dy, y3, u, v)
        }
    }

    private fun blockV4(x: Int, y: Int, book: IntArray, i0: Int, i1: Int, i2: Int, i3: Int) {
        quad(x, y, book, i0)
        quad(x + 2, y, book, i1)
        quad(x, y + 2, book, i2)
        quad(x + 2, y + 2, book, i3)
    }

    private fun quad(x: Int, y: Int, book: IntArray, idx: Int) {
        val b = idx * 6
        val u = book[b + 4]; val v = book[b + 5]
        put(x, y, book[b], u, v)
        put(x + 1, y, book[b + 1], u, v)
        put(x, y + 1, book[b + 2], u, v)
        put(x + 1, y + 1, book[b + 3], u, v)
    }

    private fun vectors(
        chunkId: Int, data: ByteArray, bookV1: IntArray, bookV4: IntArray, yTop: Int, yBot: Int,
    ) {
        var pos = 0
        var flag = 0
        var mask = 0

        // returns -1 when the chunk is exhausted
        fun nextBit(): Int {
            if (mask == 0) {
                if (pos + 4 > data.size) return -1
                flag = data.i32(pos)
                pos += 4
                mask = 1 shl 31
            }
            val bit = if ((flag and mask) != 0) 1 else 0
            mask = mask ushr 1
            return bit
        }

        var y = yTop
        while (y < yBot) {
            var x = 0
            while (x < width) {
                val useV4: Boolean
                when (chunkId) {
                    0x3200 -> useV4 = false                       // intra, V1 only
                    0x3000 -> {                                   // intra, mixed
                        val b = nextBit(); if (b < 0) return
                        useV4 = b == 1
                    }
                    else -> {                                     // 0x3100 inter
                        val coded = nextBit(); if (coded < 0) return
                        if (coded == 0) { x += 4; continue }       // block unchanged
                        val b = nextBit(); if (b < 0) return
                        useV4 = b == 1
                    }
                }
                if (useV4) {
                    if (pos + 4 > data.size) return
                    blockV4(
                        x, y, bookV4,
                        data[pos].toInt() and 0xFF, data[pos + 1].toInt() and 0xFF,
                        data[pos + 2].toInt() and 0xFF, data[pos + 3].toInt() and 0xFF,
                    )
                    pos += 4
                } else {
                    if (pos + 1 > data.size) return
                    blockV1(x, y, bookV1, data[pos].toInt() and 0xFF)
                    pos += 1
                }
                x += 4
            }
            y += 4
        }
    }

    /** Decode one frame into [rgb]; returns it for convenience. */
    fun decode(frame: ByteArray): ByteArray {
        if (frame.size < 10) return rgb
        val declared = ((frame[1].toInt() and 0xFF) shl 16) or
            ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
        val limit = if (declared in 1..frame.size) declared else frame.size
        val numStrips = frame.u16(8)
        val interFrame = (frame[0].toInt() and 0x01) != 0

        var pos = 10
        var yTop = 0
        for (s in 0 until numStrips) {
            if (pos + 12 > limit) break
            val stripSize = frame.u16(pos + 2)
            if (stripSize < 12) break

            // Header y0/y1 are commonly per-strip rather than absolute, so track a
            // running offset and take only the height from the header.
            val y0f = frame.u16(pos + 4)
            val y1f = frame.u16(pos + 8)
            val rows = if (y1f > y0f) y1f - y0f else height
            val yBot = minOf(yTop + rows, height)

            val si = minOf(s, MAX_STRIPS - 1)
            if (s > 0 && !interFrame) {
                v1[si - 1].copyInto(v1[si])
                v4[si - 1].copyInto(v4[si])
            }
            val bookV1 = v1[si]
            val bookV4 = v4[si]

            val bodyEnd = minOf(pos + stripSize, limit)
            var cpos = pos + 12
            while (cpos + 4 <= bodyEnd) {
                val cid = frame.u16(cpos)
                val csize = frame.u16(cpos + 2)
                if (csize < 4) break
                val cend = minOf(cpos + csize, bodyEnd)
                val cdata = frame.copyOfRange(cpos + 4, cend)
                when {
                    // 0x0200 selects V1 over V4; 0x0100 selective update; 0x0400 greyscale
                    (cid and 0xF000) == 0x2000 ->
                        codebook(if ((cid and 0x0200) != 0) bookV1 else bookV4, cid, cdata)
                    cid == 0x3000 || cid == 0x3100 || cid == 0x3200 ->
                        vectors(cid, cdata, bookV1, bookV4, yTop, yBot)
                }
                cpos += csize
            }

            yTop = yBot
            pos += stripSize
        }
        return rgb
    }

    companion object {
        private const val MAX_STRIPS = 32
    }
}
