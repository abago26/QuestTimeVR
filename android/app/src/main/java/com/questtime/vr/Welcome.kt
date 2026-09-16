package com.questtime.vr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface

/**
 * Somewhere to stand on the very first launch, when the headset holds no files yet.
 *
 * Until this existed a new user got the flat panel and nothing else: the app opens a
 * panorama chosen at random, and with nothing to choose from it stayed on the 2D
 * screen. That is the one moment when the person needs the web address most, and it
 * was also the moment the app looked least like what it is.
 *
 * **It is generated, not bundled, and that is the point.** The obvious answer is to
 * ship a stock panorama, and every candidate is a photograph somebody owns - the same
 * question that keeps `res/raw/ambience.mp3` out of the repository. Drawing one costs
 * a few hundred kilobytes of pixels at runtime and nothing at all in the APK, and it
 * can say the address that this particular headset is serving on, which no bundled
 * image could.
 *
 * It is a [Panorama] like any other, so it goes through `Caps.addGradient` and the
 * cylinder geometry unchanged. Nothing downstream knows it did not come out of a
 * file, which is why there is no second rendering path to keep in step with the
 * first.
 */
object Welcome {

    /**
     * A full turn, and a band shaped like the files this app opens.
     *
     * 3.6:1 is White House's ratio and sits in the middle of what real single-node
     * panoramas measure, so the welcome lands at about the same horizon as the first
     * actual file will. A squarer band would leave the caps doing most of the work
     * and the app would look different the moment something real was opened.
     */
    private const val WIDTH = 4096
    private const val ASPECT = 3.6

    /**
     * Type is laid out in pixels and read in degrees: at a full turn across [WIDTH],
     * one degree is WIDTH/360 px. Sizes are written that way round so they can be
     * checked against something - a cap height of 5 degrees for the title is roughly
     * a cinema title seen from the middle of the room.
     */
    private const val PX_PER_DEGREE = WIDTH / 360f

    /**
     * Sky, horizon and ground. Chosen to be unmistakably *not* a photograph: the
     * point of this image is that the app is working and empty, and a synthetic
     * gradient says that where a convincing landscape would just look like a file
     * the user does not remember copying.
     */
    private const val SKY = 0xFF10243Fu
    private const val HORIZON = 0xFF3C6E8Fu
    private const val GROUND = 0xFF121A22u

    /**
     * [address] is the upload server's URL, or null when there is no Wi-Fi to serve
     * on. Both cases are drawn - a wall that simply omits the line when the network
     * is down leaves someone looking for an address that was never going to appear.
     */
    fun panorama(address: String?): Panorama {
        val h = (WIDTH / ASPECT).toInt()
        val bmp = Bitmap.createBitmap(WIDTH, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // The horizon sits at the middle row, because that is where the cylinder's
        // own horizon is: Caps centres the band and grows it symmetrically.
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(SKY.toInt(), HORIZON.toInt(), GROUND.toInt()),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, WIDTH.toFloat(), h.toFloat(), paint)
        paint.shader = null

        /*
         * A line on the horizon, so turning your head reads as turning rather than
         * as staring at a flat wash - but only in the stretches between the text.
         *
         * Drawn across the whole turn it passes straight through the address, which
         * is the one line on this wall somebody has to read character by character.
         * The blank stretches are also exactly where the turning cue was wanted, so
         * skipping the middle of each quarter costs nothing.
         */
        paint.color = 0xFF6D9EBFu.toInt()
        paint.alpha = 90
        val quarterPx = WIDTH / 4f
        for (k in 0 until 4) {
            val start = k * quarterPx
            c.drawRect(start, h / 2f - 1f, start + quarterPx * 0.28f, h / 2f + 1f, paint)
            c.drawRect(
                start + quarterPx * 0.72f, h / 2f - 1f,
                start + quarterPx, h / 2f + 1f, paint,
            )
        }
        paint.alpha = 255

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFFu.toInt()
            textSize = 5f * PX_PER_DEGREE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFD8E6F2u.toInt()
            textSize = 2.4f * PX_PER_DEGREE
            textAlign = Paint.Align.CENTER
            typeface = UI
        }
        val faint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9FB6C9u.toInt()
            textSize = 2f * PX_PER_DEGREE
            textAlign = Paint.Align.CENTER
            typeface = UI
        }

        val lines = listOf(
            address?.let { "Open  $it  in any browser on this Wi-Fi" }
                ?: "Connect the headset to Wi-Fi, then reopen this app",
            "and drop your QuickTime VR files in.",
        )

        /*
         * Four times round, centred in each quarter.
         *
         * Not once: a single block means three quarters of the turn are blank, and
         * the one moment this image exists for is the moment nobody knows which way
         * to look. Centred in each quarter rather than spaced from zero because the
         * cylinder is submitted as four 90-degree arcs - a block centred in its own
         * arc cannot be split by a boundary, and the wrap behind the viewer is a
         * boundary like any other.
         */
        for (k in 0 until 4) {
            val x = (k + 0.5f) * quarterPx
            var y = h / 2f - 3.2f * PX_PER_DEGREE
            c.drawText("QuestTime VR", x, y, title)
            y += 3.4f * PX_PER_DEGREE
            for (line in lines) {
                c.drawText(line, x, y, body)
                y += 2.9f * PX_PER_DEGREE
            }
            y += 1.2f * PX_PER_DEGREE
            c.drawText("A or X opens the list", x, y, faint)
        }

        val px = IntArray(WIDTH * h)
        bmp.getPixels(px, 0, WIDTH, 0, 0, WIDTH, h)
        bmp.recycle()

        // Panorama carries RGB, not ARGB - the compositor gets no alpha and this
        // image has nothing behind it to show through anyway.
        val rgb = ByteArray(WIDTH * h * 3)
        for (i in px.indices) {
            val p = px[i]
            rgb[i * 3] = ((p shr 16) and 0xFF).toByte()
            rgb[i * 3 + 1] = ((p shr 8) and 0xFF).toByte()
            rgb[i * 3 + 2] = (p and 0xFF).toByte()
        }
        // info stays null, exactly as it does for a rebuilt headerless file: a full
        // turn, with the vertical extent taken from the pixel aspect.
        return Panorama(rgb, WIDTH, h, null)
    }

    /**
     * Lazy for the same reason [MenuBar]'s is: a plain initialiser runs at class-load
     * time, which on a bare JVM throws before any test can report a useful failure.
     */
    private val UI: Typeface by lazy { Typeface.SANS_SERIF }
}
