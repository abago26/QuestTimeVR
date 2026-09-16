package com.questtime.vr

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The generated welcome panorama, drawn on the host.
 *
 * NATIVE graphics for the same reason [MenuPreviewTest] needs it: Robolectric's
 * default shadows record draw calls without rasterising, so every assertion about
 * ink would pass against a fully transparent bitmap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WelcomeTest {

    private fun bitmapOf(p: Panorama): Bitmap {
        val px = IntArray(p.width * p.height)
        for (i in px.indices) {
            val r = p.rgb[i * 3].toInt() and 0xFF
            val g = p.rgb[i * 3 + 1].toInt() and 0xFF
            val b = p.rgb[i * 3 + 2].toInt() and 0xFF
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(px, p.width, p.height, Bitmap.Config.ARGB_8888)
    }

    /**
     * A development tool wearing a test's clothes, like PagePreviewTest. Writes the
     * welcome panorama where it can be looked at, and a quarter of it at readable
     * size - the full image is 4096 wide and a glance at it says nothing about
     * whether the type is legible.
     */
    @Test
    fun writesThePreview() {
        val p = Welcome.panorama("http://192.168.1.42:8080")
        val full = bitmapOf(p)
        File("build/preview").mkdirs()
        File("build/preview/welcome.png").outputStream().use {
            full.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val quarter = Bitmap.createBitmap(full, 0, 0, p.width / 4, p.height)
        File("build/preview/welcome-quarter.png").outputStream().use {
            quarter.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /**
     * The shape is the part that has to match real files, because the cylinder's
     * vertical extent comes from the pixel aspect and nothing else. A welcome at a
     * different ratio would put its horizon somewhere the first real file does not.
     */
    @Test
    fun isShapedLikeASingleNodePanorama() {
        val p = Welcome.panorama(null)
        val aspect = p.width.toDouble() / p.height
        assertTrue("aspect $aspect outside the single-node range", aspect in 2.0..8.0)
        assertEquals("a full turn", Math.PI * 2, p.centralAngle.toDouble(), 0.01)
    }

    /**
     * Four blocks of text, one per quarter, and this is what says so.
     *
     * The cylinder is submitted as four 90-degree arcs, so a block centred in its own
     * quarter cannot be split by an arc boundary or by the wrap behind the viewer.
     * Measured as ink per column band rather than by reading pixels at chosen points:
     * the four bands must each carry text, and they must carry roughly the same
     * amount of it, which is what "the same block four times" means.
     */
    @Test
    fun saysItFourTimesRound() {
        val p = Welcome.panorama("http://192.168.1.42:8080")
        val bright = IntArray(4)
        for (q in 0 until 4) {
            for (x in q * p.width / 4 until (q + 1) * p.width / 4) {
                for (y in 0 until p.height) {
                    val i = (y * p.width + x) * 3
                    // White type on a mid-blue wall: nothing in the gradient comes
                    // near 240 in all three channels.
                    if ((p.rgb[i].toInt() and 0xFF) > 240 &&
                        (p.rgb[i + 1].toInt() and 0xFF) > 240 &&
                        (p.rgb[i + 2].toInt() and 0xFF) > 240
                    ) bright[q]++
                }
            }
        }
        for (q in 0 until 4) assertTrue("quarter $q has no text", bright[q] > 500)
        val lo = bright.min()
        val hi = bright.max()
        assertTrue("quarters differ: ${bright.toList()}", hi <= lo * 1.05)
    }

    /**
     * No Wi-Fi is a different sentence, not a missing one.
     *
     * Dropping the line would leave someone waiting for an address that was never
     * going to appear, which is the same failure as the refusal message that named
     * a script only a repo clone has.
     */
    @Test
    fun saysSomethingUsefulWithNoAddress() {
        val withAddress = Welcome.panorama("http://192.168.1.42:8080")
        val without = Welcome.panorama(null)
        assertEquals(withAddress.width, without.width)
        assertEquals(withAddress.height, without.height)
        assertTrue(
            "the no-Wi-Fi wall should still carry text",
            without.rgb.count { (it.toInt() and 0xFF) > 240 } > 2000,
        )
    }
}
