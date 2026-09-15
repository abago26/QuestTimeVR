package com.questtime.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The hot-spot mask codec, against ffmpeg.
 *
 * Every one of Lincoln Memorial's 216 mask tiles, byte for byte. A mask is not
 * something you can eyeball - it is a field of small integers - so there is no
 * substitute for comparing the whole track against a decoder that is already
 * trusted, and no reason to settle for less when one is available.
 *
 * The truth file is ffmpeg's `pal8` raw output, which writes each frame as
 * width*height index bytes followed by a 1024-byte palette. The palette is stepped
 * over here: the indices are the whole point, because in a hot-spot mask the index
 * *is* the hot-spot id.
 */
class SmcTest {

    private val testdata = File("../../reference/testdata")
    private val truth = File("../../reference/truth")

    private val w = 768
    private val h = 168
    private val frames = 216

    @Test
    fun everyMaskTileMatchesFfmpegExactly() {
        val mov = File(testdata, "lincoln9.mov")
        val ref = File(truth, "lincoln_mask_pal8.raw")
        assumeTrue("reference/testdata/lincoln9.mov missing", mov.isFile)
        assumeTrue("reference/truth/lincoln_mask_pal8.raw missing - run make_truth.sh", ref.isFile)

        val data = mov.readBytes()
        val expected = ref.readBytes()
        val plane = w * h
        assertEquals("truth file is not the expected shape",
            frames.toLong() * (plane + 1024), expected.size.toLong())

        val tracks = MovParser.parseTracks(data)
        val mask = tracks.first { it.format.trim() == "smc" }
        assertEquals(w, mask.width)
        assertEquals(h, mask.height)
        val ranges = mask.sampleRanges()
        assertEquals(frames, ranges.size)

        val dec = Smc(w, h)
        for ((i, r) in ranges.withIndex()) {
            val sample = data.copyOfRange(r.first.toInt(), r.first.toInt() + r.second)
            val got = dec.decode(sample)
            val at = i * (plane + 1024)
            for (p in 0 until plane) {
                if (got[p] != expected[at + p]) {
                    throw AssertionError(
                        "frame $i pixel $p (x=${p % w}, y=${p / w}): " +
                            "expected ${expected[at + p].toInt() and 0xFF}, " +
                            "got ${got[p].toInt() and 0xFF}"
                    )
                }
            }
        }
    }

    /**
     * And the values are hot-spot ids, not colours.
     *
     * Lincoln's first node carries one hot spot, id 248, and the mask for that node
     * should be background and 248 and nothing else. This is the assertion that says
     * the decode means what the navigation is about to assume it means.
     */
    @Test
    fun theMaskHoldsHotSpotIds() {
        val mov = File(testdata, "lincoln9.mov")
        assumeTrue("reference/testdata/lincoln9.mov missing", mov.isFile)
        val data = mov.readBytes()
        val mask = MovParser.parseTracks(data).first { it.format.trim() == "smc" }
        val dec = Smc(w, h)
        val seen = HashSet<Int>()
        for (r in mask.sampleRanges().take(24)) {          // node 0's 24 tiles
            val sample = data.copyOfRange(r.first.toInt(), r.first.toInt() + r.second)
            for (b in dec.decode(sample)) seen.add(b.toInt() and 0xFF)
        }
        assertEquals("node 0's mask should be background and one hot spot",
            setOf(0, 248), seen)
    }

    /** An opcode this does not know must refuse, not invent a doorway. */
    @Test
    fun anUnknownOpcodeIsRefused() {
        val frame = byteArrayOf(0, 0, 0, 8, 0xF0.toByte(), 0, 0, 0)
        val e = runCatching { Smc(8, 8).decode(frame) }.exceptionOrNull()
        assertTrue("expected a refusal, got $e", e is IllegalArgumentException)
        assertTrue("should name the opcode: ${e?.message}", e!!.message!!.contains("0xf0"))
    }

    /**
     * The opcodes a real mask never reaches.
     *
     * Lincoln's mask is nearly flat and uses five of the sixteen opcodes; Joshua's
     * add two more. The other nine would be implemented from a format description
     * and never once executed - exactly the shape of a decoder that is silently
     * wrong the first time a file needs it.
     *
     * ffmpeg can encode smc as well as decode it, so the rest are exercised against
     * synthetic frames it produced: between the three, every opcode is covered
     * except 0x40/0x50 (repeat the previous two blocks), which nothing in reach
     * emits. That one path remains unproven and is marked as such in [Smc].
     */
    @Test
    fun theOpcodesRealMasksNeverReachAlsoMatchFfmpeg() {
        for (name in listOf("mandelbrot", "testsrc2", "gradients")) {
            val mov = File(testdata, "smc_$name.mov")
            val ref = File(truth, "smc_$name.raw")
            assumeTrue("reference/testdata/smc_$name.mov missing - run make_truth.sh", mov.isFile)
            assumeTrue("reference/truth/smc_$name.raw missing - run make_truth.sh", ref.isFile)

            val data = mov.readBytes()
            val expected = ref.readBytes()
            val track = MovParser.parseTracks(data).first { it.format.trim() == "smc" }
            val plane = track.width * track.height
            val dec = Smc(track.width, track.height)
            for ((i, r) in track.sampleRanges().withIndex()) {
                val sample = data.copyOfRange(r.first.toInt(), r.first.toInt() + r.second)
                val got = dec.decode(sample)
                val at = i * (plane + 1024)
                for (p in 0 until plane) {
                    if (got[p] != expected[at + p]) {
                        throw AssertionError(
                            "$name frame $i pixel (x=${p % track.width}, y=${p / track.width}): " +
                                "expected ${expected[at + p].toInt() and 0xFF}, " +
                                "got ${got[p].toInt() and 0xFF}"
                        )
                    }
                }
            }
        }
    }
}
