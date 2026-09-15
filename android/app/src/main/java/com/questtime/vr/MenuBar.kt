package com.questtime.vr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.nio.ByteBuffer

/**
 * The menu bar, drawn as a bitmap for the compositor to hang in front of you.
 *
 * Drawn here rather than in C++ because this side has a text stack. Laying out a
 * line of type natively would mean shipping a font and a rasteriser to redo what
 * android.graphics already does well, and the renderer's whole design is to own as
 * little drawing as possible - it submits composition layers and nothing else.
 *
 * What comes back is a quad layer's worth of RGBA with transparent margins, so the
 * bar reads as a panel floating over the panorama rather than a rectangle pasted
 * onto it. The renderer submits it last, because composition layers paint in
 * submission order.
 */
object MenuBar {

    /**
     * Wide enough for a long filename at a readable size, and a power of two in
     * width so no runtime has to pad it. It lands about 34 degrees across.
     */
    internal const val WIDTH = 1024
    private const val HEIGHT = 256

    private const val BG = 0xE0141414.toInt()      // nearly opaque, slightly warm black
    private const val RULE = 0xFF3A3A3A.toInt()
    private const val TEXT = 0xFFF2F2F2.toInt()
    private const val DIM = 0xFF9A9A9A.toInt()

    /**
     * Render the bar for [title], and hand it to the renderer.
     *
     * [hint] is the line underneath - what the controls do, since there is nothing
     * to point at yet.
     */
    fun build(title: String, hint: String): Triple<ByteBuffer, Int, Int> {
        val bmp = draw(title, hint)
        val px = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4)
        bmp.copyPixelsToBuffer(px)
        px.rewind()
        bmp.recycle()
        return Triple(px, WIDTH, HEIGHT)
    }

    /**
     * The bar itself, before it is flattened into a buffer.
     *
     * Split out so `MenuPreviewTest` can render it on the host and write a PNG. The
     * caller owns the bitmap and should recycle it.
     */
    internal fun draw(title: String, hint: String): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT)

        val panel = RectF(16f, 16f, WIDTH - 16f, HEIGHT - 16f)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BG }
        c.drawRoundRect(panel, 22f, 22f, fill)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f; color = RULE
        }
        c.drawRoundRect(panel, 22f, 22f, edge)

        val name = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT
            textSize = 46f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DIM
            textSize = 30f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }

        val inset = panel.left + 34f
        val room = panel.width() - 68f
        // The title is one line and gets cut: a filename is an identifier, and the
        // front of it is what identifies. The hint is a sentence and gets wrapped,
        // because cutting it lost the two facts most worth having - that the
        // thumbstick turns and how to get out.
        c.drawText(ellipsize(title, name, room), inset, panel.top + 72f, name)
        var y = panel.top + 124f
        for (line in wrap(hint, sub, room, maxLines = 2)) {
            c.drawText(line, inset, y, sub)
            y += 40f
        }
        return bmp
    }

    /**
     * Break [s] across at most [maxLines], ellipsizing only if it still will not fit.
     *
     * Wraps on whitespace. The hint is built from "  ·  "-separated clauses, so the
     * break lands between them naturally without needing to know that.
     */
    internal fun wrap(s: String, paint: Paint, width: Float, maxLines: Int): List<String> {
        val words = s.split(" ").filter { it.isNotEmpty() }
        val lines = ArrayList<String>()
        var line = StringBuilder()
        for (w in words) {
            val candidate = if (line.isEmpty()) w else "$line $w"
            if (paint.measureText(candidate) <= width) {
                line = StringBuilder(candidate)
                continue
            }
            if (line.isNotEmpty()) lines.add(line.toString())
            line = StringBuilder(w)
            if (lines.size == maxLines - 1) break
        }
        // Whatever is left goes on the last line - including any words the loop
        // broke out before reaching, which is what makes the ellipsis meaningful.
        val consumed = lines.sumOf { it.split(" ").size }
        val rest = words.drop(consumed).joinToString(" ")
        if (rest.isNotEmpty()) lines.add(ellipsize(rest, paint, width))
        return lines
    }

    /**
     * Trim to fit, with an ellipsis. Panorama names run long - "Green Spiky Land
     * (KPT Bryce™)" - and a name that silently runs off the panel is worse than one
     * that visibly stops.
     */
    internal fun ellipsize(s: String, paint: Paint, width: Float): String {
        if (paint.measureText(s) <= width) return s
        var end = s.length
        while (end > 1 && paint.measureText(s.substring(0, end) + "…") > width) end--
        return s.substring(0, end) + "…"
    }
}
