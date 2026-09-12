package com.questtime.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class CapsTest {

    private val movFile = File("../../reference/testdata/eciqtvr_hr1.mov")

    private fun pano(): Panorama {
        assumeTrue("fixture missing", movFile.isFile)
        return Qtvr.extract(movFile.readBytes())
    }

    @Test
    fun bandInteriorIsUntouched() {
        val p = pano()
        val capped = Caps.addGradient(p, 2048)
        assertEquals(2496, capped.width)
        assertEquals(2048, capped.height)

        val pad = (2048 - p.height) / 2
        // Everything but the feathered edge rows must survive byte-identical,
        // just moved down by pad.
        val feather = 56
        var diffs = 0
        for (y in feather until p.height - feather) {
            for (i in 0 until p.width * 3) {
                val a = capped.rgb[((pad + y) * p.width) * 3 + i]
                val b = p.rgb[(y * p.width) * 3 + i]
                if (a != b) diffs++
            }
        }
        assertEquals("band interior altered", 0, diffs)
    }

    @Test
    fun capsAreNotBlackAndMeetTheEdge() {
        val p = pano()
        val capped = Caps.addGradient(p, 2048)
        val w = capped.width
        val pad = (capped.height - p.height) / 2

        fun px(x: Int, y: Int): Triple<Int, Int, Int> {
            val o = (y * w + x) * 3
            return Triple(
                capped.rgb[o].toInt() and 0xFF,
                capped.rgb[o + 1].toInt() and 0xFF,
                capped.rgb[o + 2].toInt() and 0xFF,
            )
        }

        // the row just above the band should be close to the band's own top row
        val justAbove = px(1200, pad - 1)
        val bandTop = px(1200, pad)
        val delta = maxOf(
            Math.abs(justAbove.first - bandTop.first),
            Math.abs(justAbove.second - bandTop.second),
            Math.abs(justAbove.third - bandTop.third),
        )
        assertTrue("cap does not meet the band (delta $delta)", delta < 70)

        // and nothing in the caps should be pure black
        for (y in intArrayOf(0, pad / 2, capped.height - 1)) {
            val c = px(1200, y)
            assertTrue("black cap row at y=$y", c.first + c.second + c.third > 30)
        }
    }

    /** Adding rows must widen the vertical field of view, not move the band. */
    @Test
    fun verticalExtentGrows() {
        val p = pano()
        val capped = Caps.addGradient(p, 2048)
        fun halfFov(x: Panorama) =
            Math.toDegrees(Math.atan(x.centralAngle.toDouble() / (2.0 * x.aspectRatio)))
        val before = halfFov(p)
        val after = halfFov(capped)
        assertEquals(44.03, before, 0.05)
        assertTrue("expected growth, got $before -> $after", after > 65.0 && after < 72.0)
    }

    /** Writes the capped panorama out so it can be eyeballed without a headset. */
    @Test
    fun dumpPreview() {
        val capped = Caps.addGradient(pano(), 2048)
        File("../../reference/out_capped.rgb").writeBytes(capped.rgb)
        println("wrote ${capped.width}x${capped.height}")
    }
}
