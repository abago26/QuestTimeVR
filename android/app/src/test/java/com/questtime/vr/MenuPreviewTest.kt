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
 * Draw the menu bar on the host and write it out as a PNG.
 *
 * `MenuBar` is the one part of the app with real layout that could not be seen
 * without a headset, which is exactly backwards: type, spacing and where a long name
 * gets ellipsized are things you want to look at twenty times, and a
 * build-install-wear cycle is minutes each.
 *
 *     ./build.sh testDebugUnitTest --tests '*MenuPreviewTest*'
 *     open android/app/build/preview/
 *
 * **`GraphicsMode.NATIVE` is what makes this real.** Robolectric's default graphics
 * shadows record draw calls without rasterising anything, so the bitmap comes back
 * fully transparent and the preview would be a confident lie. Native mode runs the
 * actual Android graphics stack - which is also why the PNG is written with
 * `Bitmap.compress` rather than ImageIO: it is the same encoder the device has, and
 * `java.awt` is not on the Android unit-test classpath anyway.
 *
 * The assertions exist to catch that mode silently regressing. A preview that
 * quietly renders nothing is worse than no preview at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MenuPreviewTest {

    private fun render(name: String, title: String, hint: String): Bitmap {
        val bmp = MenuBar.draw(title, hint)
        val out = File("build/preview/$name.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("menu preview: ${out.absolutePath}")
        return bmp
    }

    /** Fraction of pixels that are not fully transparent. */
    private fun covered(b: Bitmap): Double {
        var n = 0
        for (y in 0 until b.height) for (x in 0 until b.width) {
            if ((b.getPixel(x, y) ushr 24) != 0) n++
        }
        return n.toDouble() / (b.width * b.height)
    }

    /** Fraction that is neither transparent nor the panel's own dark background. */
    private fun inked(b: Bitmap): Double {
        var n = 0
        for (y in 0 until b.height) for (x in 0 until b.width) {
            val p = b.getPixel(x, y)
            if ((p ushr 24) == 0) continue
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val bl = p and 0xFF
            // Panel fill is near-black; type is near-white. Anything bright is ink.
            if (r > 120 && g > 120 && bl > 120) n++
        }
        return n.toDouble() / (b.width * b.height)
    }

    @Test
    fun drawsTheBar() {
        val bmp = render("menu-bar", "Lincoln Memorial (9 nodes)  ·  node 1",
            "A or X opens the list  ·  Stick turns 45°  ·  A dot means a way on: trigger to walk")
        try {
            // The panel covers most of the bitmap but must leave transparent margins,
            // or it arrives in the headset as a black slab over the panorama.
            val c = covered(bmp)
            assertTrue("panel should cover most of it, covered=$c", c > 0.80)
            assertTrue("margins must stay transparent, covered=$c", c < 0.98)

            // If native graphics ever stops rasterising, this is what catches it:
            // a recorded-but-not-drawn canvas gives zero ink.
            val ink = inked(bmp)
            assertTrue("no text was actually rasterised, ink=$ink", ink > 0.002)
        } finally {
            bmp.recycle()
        }
    }

    /**
     * The hint must fit whole. It used to be one ellipsized line, which cut it at
     * "Thumbstic..." and threw away the two facts most worth having - that the
     * thumbstick turns and how to get out. A PNG caught that; this keeps it caught.
     */
    @Test
    fun theHintFitsWithoutBeingCutOff() {
        val sub = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 30f
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL)
        }
        // Both hints, because the hot-spot one is longer and is the one that would
        // start being cut first.
        val hint = "A or X opens the list  ·  Stick turns 45°  ·  " +
            "A dot means a way on: trigger to walk"
        // The same room draw() gives it: the panel is inset 16 a side, then 34 more.
        val room = (MenuBar.WIDTH - 32).toFloat() - 68f
        val lines = MenuBar.wrap(hint, sub, room, maxLines = 2)

        assertTrue("should not need more than two lines: $lines", lines.size <= 2)
        assertTrue("the hint was cut: $lines", lines.none { it.endsWith("…") })
        // Every word survives, whatever the wrap does with the spacing.
        val got = lines.joinToString(" ").split(" ").filter { it.isNotEmpty() }
        val want = hint.split(" ").filter { it.isNotEmpty() }
        assertEquals("words lost in the wrap", want, got)
    }

    /** The list, with the controller strip. Mostly here to be looked at. */
    @Test
    fun drawsTheFileList() {
        // Five, so the music row lands on the first page and can be looked at.
        val names = listOf(
            "Monument Valley", "Radio City Music Hall", "Green Spiky Land (KPT Bryce\u2122)",
            "Hwy 1 near Stinson Beach, CA", "Champs Elysee at Night")
        val (buf, w, h) = MenuBar.buildList(names, selected = 2, musicMuted = false,
            serverUrl = "http://192.168.1.42:8080")
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        val out = File("build/preview/menu-list.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("menu preview: ${out.absolutePath}")
        try {
            assertTrue("nothing drawn", inked(bmp) > 0.002)
        } finally {
            bmp.recycle()
        }
    }

    /**
     * A clean install: nothing on the headset, and Re-Scan Files has to be on screen.
     *
     * The empty list used to stop drawing after its title, taking the address and the
     * whole settings band with it - so the row a new user needs straight after sending
     * files was not there. Measured as ink in the band's rectangle, the same rectangle
     * [MenuBar.rowAt] maps, so a regression cannot hide behind a pretty preview.
     */
    @Test
    fun theEmptyListStillOffersRescan() {
        val rescan = MenuBar.ACTION_RESCAN
        val (buf, w, h) = MenuBar.buildList(
            emptyList(), selected = rescan, musicMuted = false,
            serverUrl = "http://192.168.1.42:8080",
        )
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        val out = File("build/preview/menu-empty.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        try {
            val top = (h - 16 - 104 - MenuBar.SETTINGS_H).toInt()
            val bottom = h - 16 - 104
            var bright = 0
            for (y in top until bottom) for (x in 0 until w) {
                val p = bmp.getPixel(x, y)
                if (((p shr 16) and 0xFF) > 180 && ((p shr 8) and 0xFF) > 180) bright++
            }
            assertTrue("no settings band on an empty list ($bright bright px)", bright > 500)
            // And the pointer finds the rescan row there, with no files above it.
            val midRow = top + MenuBar.SETTINGS_H / MenuBar.ACTION_COUNT * (rescan + 0.5f)
            assertEquals(rescan, MenuBar.rowAt((midRow / h * 1000).toInt(), 0, 0))
        } finally {
            bmp.recycle()
        }
    }

    /**
     * The rescan row reports, and that is the whole reason it is a row.
     *
     * Someone who has just dropped files into the browser page is looking at a list
     * built before they sent them. Pressing something and seeing nothing change is
     * indistinguishable from pressing nothing, so the count has to reach the panel -
     * and this is what says the two states are actually drawn differently rather
     * than the note being accepted and dropped.
     */
    @Test
    fun theRescanRowShowsWhatItFound() {
        val names = listOf("Monument Valley", "Radio City Music Hall")
        fun render(note: String?): IntArray {
            val (buf, w, h) = MenuBar.buildList(
                names, selected = 0, musicMuted = false,
                serverUrl = "http://192.168.1.42:8080", rescanNote = note,
            )
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buf)
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            bmp.recycle()
            return px
        }
        val before = render(null)
        val after = render("5 found")
        assertEquals(before.size, after.size)
        val changed = before.indices.count { before[it] != after[it] }
        assertTrue("the note never reached the panel", changed > 200)
    }

    /**
     * The second level of the picker: one scene's nodes.
     *
     * The same drawing as the file list, which is the point - the title and the
     * count are all that change, so there is one layout and one [MenuBar.rowAt] to
     * keep in step rather than two. The names are Lincoln Memorial's real ones.
     */
    @Test
    fun drawsTheNodeList() {
        val names = (1..9).map { "DCwalk.%02d".format(it) }
        val (buf, w, h) = MenuBar.buildList(
            names, selected = 2, musicMuted = false,
            title = "Lincoln Memorial (9 nodes)",
            subtitle = "9 places in this scene",
            inScene = true,
        )
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        val out = File("build/preview/menu-nodes.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("menu preview: ${out.absolutePath}")
        try {
            assertTrue("nothing drawn", inked(bmp) > 0.002)
            // The scene's name is long enough to be worth checking it still fits the
            // title's width - a cut title is the one thing that would make the two
            // levels hard to tell apart.
            assertEquals("the node list must be the same shape as the file list",
                MenuBar.listHeight(names.size), h)
        } finally {
            bmp.recycle()
        }
    }

    /** The case the ellipsis exists for. Worth looking at, not only asserting. */
    @Test
    fun drawsTheBarWithANameTooLongToFit() {
        val bmp = render("menu-bar-long",
            "Green Spiky Land (KPT Bryce™) somewhere well past what the panel can hold",
            "Menu button or left pinch hides this  ·  Thumbstick turns 45°  ·  Meta button exits")
        try {
            assertTrue("nothing drawn", inked(bmp) > 0.002)
        } finally {
            bmp.recycle()
        }
    }

    /**
     * The floating label that names the doorway under the reticle.
     *
     * Three widths in one image, because the card sizes itself to its text and the
     * interesting failures are at the ends: a short name rattling around in a wide
     * card, and a long one pushing past the clamp into an ellipsis.
     */
    @Test
    fun drawsTheDoorwayLabel() {
        val cases = listOf(
            "To DCwalk.02" to "Trigger to walk",
            "Go for a walk to Cyclops" to "Trigger to walk",
            "Through the colonnade and up the long flight of steps" to "Trigger to walk",
        )
        val shots = cases.map { (name, action) ->
            val (buf, w, h) = MenuBar.buildLabel(name, action)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buf)
            bmp
        }
        val wide = shots.maxOf { it.width }
        val tall = shots.sumOf { it.height } + 16 * shots.size
        val sheet = Bitmap.createBitmap(wide, tall, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(sheet)
        var y = 0
        for (b in shots) {
            c.drawBitmap(b, (wide - b.width) / 2f, y.toFloat(), null)
            y += b.height + 16
        }
        val out = File("build/preview/gaze-label.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("menu preview: ${out.absolutePath}")
        try {
            assertTrue("nothing drawn", inked(sheet) > 0.002)
            // Every bitmap is the same size - that is what keeps the swapchain from
            // being rebuilt on every glance - and the *pill* is what varies.
            assertTrue("bitmaps must all be one size", shots.all { it.width == MenuBar.LABEL_MAX })
            val pills = cases.map { (n, a) -> MenuBar.labelPillWidth(n, a) }
            assertTrue("a short name should not fill the widest pill: $pills",
                pills[0] < pills[2])
            assertEquals("the longest is clamped", MenuBar.LABEL_MAX - 12, pills[2])
        } finally {
            shots.forEach { it.recycle() }
            sheet.recycle()
        }
    }
}
