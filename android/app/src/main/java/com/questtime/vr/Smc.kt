package com.questtime.vr

/**
 * Apple Graphics ('smc') decoder, producing palette **indices** rather than colour.
 *
 * This codec is here for one reason: it is what QuickTime VR stores its hot-spot
 * mask in. That mask is an image the same size as the panorama whose pixel *values*
 * are hot-spot ids - 0 where there is nothing, and the id of the hot spot everywhere
 * it covers. So the indices are the payload and the palette is irrelevant, which is
 * why this hands back indices and never looks a colour up.
 *
 * Verified byte-exact against ffmpeg: a whole 216-frame mask track, plus three
 * synthetic clips ffmpeg's own smc *encoder* produced. The synthetic ones are not
 * decoration - real masks are nearly flat and use only five of the sixteen opcodes,
 * so without them two thirds of this file would be written from a description and
 * never once executed. Every opcode is now covered except 0x40/0x50 (repeat the
 * previous two blocks), which nothing available emits; it shares [copyBlock] with
 * 0x20/0x30, which is covered.
 *
 * The opcode table came from https://wiki.multimedia.cx/index.php/Apple_SMC, and
 * three of its entries are the opposite of the Cinepak-shaped guess: 0x90, 0xB0 and
 * 0xD0 are the *cached* variants of 0x80/0xA0/0xC0, not long-count forms. Only the
 * first six opcodes take a count from the low nibble or a following byte.
 *
 * Three things the description did not settle, all found by testing:
 *
 *  - The 8-colour block's bit layout is not a 48-bit run. See the comment there.
 *  - The colour caches keep their contents across frames but reset their write
 *    cursors. See the comment in [decode].
 *  - Repeats copy from the frame buffer, not from the last block written, which is
 *    what makes them correct after a skip. See [copyBlock].
 *
 * An opcode this does not know throws. A mask decoded wrongly puts a doorway where
 * there is no doorway, which is worse than saying so.
 *
 * One instance decodes a whole track, in order: skip and repeat opcodes refer to
 * what came before, including across frames.
 */
class Smc(private val width: Int, private val height: Int) {

    /** Palette indices, one byte per pixel. Persists between frames for skips. */
    val indices = ByteArray(width * height)

    // Circular caches of colour groups, 256 entries each, reset per frame.
    private val pairs = ByteArray(256 * 2)
    private val quads = ByteArray(256 * 4)
    private val octets = ByteArray(256 * 8)
    private var pairAt = 0
    private var quadAt = 0
    private var octetAt = 0

    private var pos = 0


    /**
     * Decode one frame into [indices], which is returned.
     *
     * The buffer is reused, exactly as [Cinepak] reuses its own - copy it if it has
     * to outlive the next call.
     */
    fun decode(frame: ByteArray): ByteArray {
        pos = 0
        // Contents persist across frames; the write cursors do not.
        //
        // An odd-looking pair, and both halves were forced by the files. Persisting
        // the contents is required: mandelbrot's second frame opens with a cached
        // 4-colour block naming index 12, which only means anything if the previous
        // frame's entries are still there. Resetting the cursors is equally required:
        // let them run on and a real hot-spot mask starts painting hot spot over
        // background thirty frames in. So an encoder may write from zero each frame
        // while still referring back to what earlier frames left further up.
        pairAt = 0; quadAt = 0; octetAt = 0

        // A 4-byte header: flags byte then a 24-bit size. Some encoders write a
        // 0x01 flag and others 0x00; the size is not needed, only skipped.
        if (frame.size < 4) throw IllegalArgumentException("An smc frame is too short to read.")
        pos = 4

        val blocksAcross = (width + 3) / 4
        val blocksDown = (height + 3) / 4
        val total = blocksAcross * blocksDown
        var block = 0

        val colors = ByteArray(16)
        while (block < total) {
            if (pos >= frame.size) break              // a frame may simply end early
            val opcode = frame[pos++].toInt() and 0xFF
            val high = opcode and 0xF0
            val low = opcode and 0x0F

            /** Count for the six opcodes that carry one: low nibble, or a byte. */
            fun count(longForm: Boolean): Int =
                if (longForm) 1 + (frame[pos++].toInt() and 0xFF) else 1 + low

            when (high) {
                0x00, 0x10 -> {                      // skip: leave what is already there
                    block += count(high == 0x10)
                }
                0x20, 0x30 -> {                      // repeat the block before this
                    val n = count(high == 0x30)
                    repeat(n) { if (block < total) { copyBlock(block - 1, block); block++ } }
                }
                0x40, 0x50 -> {                      // repeat the previous two, n times
                    val n = count(high == 0x50)
                    repeat(2 * n) { if (block < total) { copyBlock(block - 2, block); block++ } }
                }
                0x60, 0x70 -> {                      // one colour, for n blocks
                    val n = count(high == 0x70)
                    val c = frame[pos++]
                    java.util.Arrays.fill(colors, 0, 16, c)
                    repeat(n) { if (block < total) putBlock(block++, colors) }
                }
                0x80, 0x90 -> {                      // two colours, 1 bit per pixel
                    val n = 1 + low
                    val base = if (high == 0x80) {
                        val at = pairAt
                        pairs[at * 2] = frame[pos++]
                        pairs[at * 2 + 1] = frame[pos++]
                        pairAt = (pairAt + 1) and 0xFF
                        at
                    } else frame[pos++].toInt() and 0xFF
                    repeat(n) {
                        val bits = ((frame[pos].toInt() and 0xFF) shl 8) or
                            (frame[pos + 1].toInt() and 0xFF)
                        pos += 2
                        for (i in 0 until 16) {
                            colors[i] = pairs[base * 2 + ((bits ushr (15 - i)) and 1)]
                        }
                        if (block < total) putBlock(block++, colors)
                    }
                }
                0xA0, 0xB0 -> {                      // four colours, 2 bits per pixel
                    val n = 1 + low
                    val base = if (high == 0xA0) {
                        val at = quadAt
                        for (k in 0 until 4) quads[at * 4 + k] = frame[pos++]
                        quadAt = (quadAt + 1) and 0xFF
                        at
                    } else frame[pos++].toInt() and 0xFF
                    repeat(n) {
                        var bits = 0L
                        for (k in 0 until 4) {
                            bits = (bits shl 8) or (frame[pos++].toLong() and 0xFF)
                        }
                        for (i in 0 until 16) {
                            val sel = ((bits ushr (30 - 2 * i)) and 3L).toInt()
                            colors[i] = quads[base * 4 + sel]
                        }
                        if (block < total) putBlock(block++, colors)
                    }
                }
                0xC0, 0xD0 -> {                      // eight colours, 3 bits per pixel
                    val n = 1 + low
                    val base = if (high == 0xC0) {
                        val at = octetAt
                        for (k in 0 until 8) octets[at * 8 + k] = frame[pos++]
                        octetAt = (octetAt + 1) and 0xFF
                        at
                    } else frame[pos++].toInt() and 0xFF
                    repeat(n) {
                        // Three 16-bit words, and the arrangement is the one odd
                        // corner of this codec. Sixteen pixels at 3 bits is 48 bits,
                        // which fits the six bytes exactly - but they are not packed
                        // as one run. Each word carries ONE ROW in its top 12 bits,
                        // and the three leftover low nibbles are concatenated to
                        // make the fourth row. Solved against ffmpeg over 592 blocks
                        // rather than guessed: a straight 48-bit run decodes the
                        // first four pixels correctly and then quietly diverges,
                        // which is the worst way for this to be wrong.
                        val w0 = ((frame[pos].toInt() and 0xFF) shl 8) or
                            (frame[pos + 1].toInt() and 0xFF)
                        val w1 = ((frame[pos + 2].toInt() and 0xFF) shl 8) or
                            (frame[pos + 3].toInt() and 0xFF)
                        val w2 = ((frame[pos + 4].toInt() and 0xFF) shl 8) or
                            (frame[pos + 5].toInt() and 0xFF)
                        pos += 6
                        val rows = intArrayOf(w0, w1, w2)
                        for (r in 0 until 3) {
                            for (j in 0 until 4) {
                                colors[r * 4 + j] =
                                    octets[base * 8 + ((rows[r] ushr (13 - 3 * j)) and 7)]
                            }
                        }
                        val tail = ((w0 and 0xF) shl 8) or ((w1 and 0xF) shl 4) or (w2 and 0xF)
                        for (j in 0 until 4) {
                            colors[12 + j] = octets[base * 8 + ((tail ushr (9 - 3 * j)) and 7)]
                        }
                        if (block < total) putBlock(block++, colors)
                    }
                }
                0xE0 -> {                            // sixteen colours, straight through
                    val n = 1 + low
                    repeat(n) {
                        for (i in 0 until 16) colors[i] = frame[pos++]
                        if (block < total) putBlock(block++, colors)
                    }
                }
                else -> throw IllegalArgumentException(
                    "This hot-spot mask uses an smc opcode QuestTime does not know " +
                        "(0x${opcode.toString(16)}), so where its hot spots are cannot be read."
                )
            }
        }
        return indices
    }

    /** Write one 4x4 block, clipped at the right and bottom edges. */
    private fun putBlock(block: Int, px: ByteArray) {
        val blocksAcross = (width + 3) / 4
        val x0 = (block % blocksAcross) * 4
        val y0 = (block / blocksAcross) * 4
        for (y in 0 until 4) {
            val yy = y0 + y
            if (yy >= height) break
            var o = yy * width + x0
            for (x in 0 until 4) {
                if (x0 + x >= width) break
                indices[o++] = px[y * 4 + x]
            }
        }
    }

    /**
     * Copy one whole block over another, straight out of the frame buffer.
     *
     * Reading the buffer rather than remembering the last block written is what
     * makes repeat work after a skip. A skipped block is never written - it keeps
     * whatever the previous frame left there - so a decoder that repeats "the last
     * block I painted" repeats something from before the skip, and the picture
     * quietly drifts. Only a file that mixes skips and repeats shows it, which is
     * why real hot-spot masks never did and testsrc2 did immediately.
     */
    private fun copyBlock(from: Int, to: Int) {
        if (from < 0) return
        val across = (width + 3) / 4
        val sx = (from % across) * 4
        val sy = (from / across) * 4
        val dx = (to % across) * 4
        val dy = (to / across) * 4
        for (y in 0 until 4) {
            if (sy + y >= height || dy + y >= height) break
            var so = (sy + y) * width + sx
            var dO = (dy + y) * width + dx
            for (x in 0 until 4) {
                if (sx + x >= width || dx + x >= width) break
                indices[dO++] = indices[so++]
            }
        }
    }
}
