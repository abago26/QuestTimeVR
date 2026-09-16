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
     * The same face the upload page uses, as far as this device can.
     *
     * The page asks for Charcoal, then Geneva - the Mac OS 9 system fonts these
     * panoramas were authored under - and falls through to Tahoma or Verdana
     * anywhere else. Neither Mac font exists on Android; the Quest ships DroidSans,
     * DroidSansMono, CutiveMono and CarroisGothic. So this matches the page the way
     * the page already matches itself off a Mac: a humanist sans in the same role,
     * rather than the monospace the panel used to be set in.
     *
     * Filenames lose their column alignment by moving off monospace, which does not
     * matter here - they are a list to read, not a table to scan - and the controls
     * strip measures its columns rather than padding them, so it follows along.
     *
     * Lazy, and it has to be. As a plain initialiser this touches android.graphics
     * the moment anything in the object is used, so `rowAt` - pure arithmetic with a
     * JVM test of its own - started throwing ExceptionInInitializerError on a
     * classpath with no Android. Resolving it on first draw keeps the layout maths
     * testable without a device.
     */
    private val UI: Typeface by lazy { Typeface.SANS_SERIF }

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
            typeface = Typeface.create(UI, Typeface.BOLD)
        }
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DIM
            textSize = 30f
            typeface = Typeface.create(UI, Typeface.NORMAL)
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
     * The small card B and Y show: what this file is, and nothing else.
     *
     * Half the width of the list and two lines tall. The bar it replaces was the
     * full panel width for two short strings, which read as a banner rather than an
     * answer to a question you asked.
     */
    fun buildInfo(name: String, detail: String): Triple<ByteBuffer, Int, Int> {
        val w = 640
        val h = 200
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT)

        val panel = RectF(12f, 12f, w - 12f, h - 12f)
        c.drawRoundRect(panel, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BG })
        c.drawRoundRect(panel, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f; color = RULE
        })

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT; textSize = 30f
            typeface = Typeface.create(UI, Typeface.BOLD)
        }
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DIM; textSize = 21f
            typeface = Typeface.create(UI, Typeface.NORMAL)
        }
        val inset = panel.left + 22f
        val room = panel.width() - 44f
        c.drawText(ellipsize(name, title, room), inset, panel.top + 50f, title)
        var y = panel.top + 92f
        for (line in wrap(detail, sub, room, maxLines = 3)) {
            c.drawText(line, inset, y, sub)
            y += 28f
        }
        return finish(bmp)
    }

    /**
     * The floating label under the reticle: what the doorway you are looking at is.
     *
     * Deliberately small, centred and short. It hangs below the dot rather than on
     * it, because the whole point of looking at a doorway is to see the doorway -
     * a card over the middle of the view would cover the thing it is naming. Two
     * lines: the author's own words for the way on, and what to press.
     *
     * The bitmap is always the same size; the *pill* inside it is sized to the text
     * and the rest is left transparent. That is not a detail of drawing but the
     * reason this is cheap: a swapchain's dimensions are fixed at creation, so a
     * bitmap that grew with the name would destroy and rebuild one every time the
     * gaze crossed to a doorway with a longer label - several times a second while
     * sweeping a room. A constant bitmap is created once and refilled.
     */
    fun buildLabel(name: String, action: String): Triple<ByteBuffer, Int, Int> {
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT; textSize = 34f
            typeface = Typeface.create(UI, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DIM; textSize = 24f
            typeface = Typeface.create(UI, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }

        val w = LABEL_MAX
        val h = LABEL_HEIGHT
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT)

        // How wide the pill wants to be, clamped to what the bitmap can hold.
        val pad = 28f
        val wanted = maxOf(title.measureText(name), sub.measureText(action)) + 2 * pad + 24f
        val pill = wanted.coerceIn(LABEL_MIN.toFloat(), (LABEL_MAX - 12).toFloat())
        val mid = w / 2f
        val panel = RectF(mid - pill / 2f, 6f, mid + pill / 2f, h - 6f)
        val radius = panel.height() / 2f          // a pill, not a card
        c.drawRoundRect(panel, radius, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BG })
        c.drawRoundRect(panel, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f; color = RULE
        })

        val room = panel.width() - 2 * pad
        c.drawText(ellipsize(name, title, room), mid, panel.top + 44f, title)
        c.drawText(ellipsize(action, sub, room), mid, panel.top + 80f, sub)
        return finish(bmp)
    }

    /**
     * How wide the pill drawn by [buildLabel] would be, for tests and for anything
     * that needs to know without rasterising. Pure measurement, no Canvas.
     */
    internal fun labelPillWidth(name: String, action: String): Int {
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 34f; typeface = Typeface.create(UI, Typeface.BOLD)
        }
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 24f; typeface = Typeface.create(UI, Typeface.NORMAL)
        }
        val wanted = maxOf(title.measureText(name), sub.measureText(action)) + 2 * 28f + 24f
        return wanted.coerceIn(LABEL_MIN.toFloat(), (LABEL_MAX - 12).toFloat()).toInt()
    }

    /**
     * The label's bitmap, fixed, and the bounds the pill inside it may take.
     *
     * LABEL_MAX is what a quad about 26 degrees across can carry at a readable size
     * - the menu bar's own pixels-per-metre, so the type matches a panel already
     * read in a headset. LABEL_MIN stops a two-word name looking like a tooltip.
     */
    internal const val LABEL_MAX = 760
    internal const val LABEL_HEIGHT = 116
    internal const val LABEL_MIN = 300

    /** Rows the list shows at once. More than this and the highlight scrolls. */
    const val PAGE = 7

    /**
     * Which row a point [vThousandths] down the panel falls on, or -1 for none.
     *
     * Native hands over a fraction of the panel's height rather than a row, because
     * where the rows are is a fact about this drawing and nothing else should have to
     * know it. Kept next to the code that lays them out so the two cannot drift.
     */
    fun rowAt(vThousandths: Int, fileCount: Int, firstVisible: Int): Int {
        if (vThousandths < 0) return -1
        val h = listHeight(fileCount)
        val y = vThousandths / 1000f * h

        // The band first: it sits below the list and does not scroll.
        val settingsTop = h - 16f - 104f - SETTINGS_H
        if (y >= settingsTop && y < h - 16f - 104f) {
            val i = ((y - settingsTop) / ACTION_H).toInt().coerceIn(0, ACTION_COUNT - 1)
            return fileCount + i
        }

        val rowsTop = 16f + BRAND_H + 152f + HEADER_H - 40f
        if (y < rowsTop) return -1
        val index = ((y - rowsTop) / 62f).toInt()
        val row = firstVisible + index
        return if (index in 0 until PAGE && row < fileCount) row else -1
    }

    /** The exact height buildList produces, so rowAt measures the same rectangle. */
    fun listHeight(fileCount: Int): Int =
        128 + BRAND_H.toInt() + HEADER_H.toInt() + PAGE * 62 + SETTINGS_H.toInt() + 130

    /**
     * The address band above the list.
     *
     * Always the same height, whether or not there is an address to show. A band that
     * appeared and vanished would move every row under it, and [rowAt] would have to
     * be told which case it was looking at - a second thing to keep in step for no
     * gain.
     */
    internal const val HEADER_H = 46f

    /**
     * The app's own name, above the panel.
     *
     * The 2D panel used to carry it and is gone, so this is the only place the app
     * says what it is. Drawn in the transparent margin above the rounded rectangle
     * rather than inside it, so it reads as a title on the panel instead of a first
     * row in the list.
     */
    internal const val BRAND_H = 62f

    /** Rows in the band under the list, in order. Index 0 is the first after the files. */
    internal const val ACTION_MUSIC = 0
    internal const val ACTION_RESCAN = 1
    internal const val ACTION_DETAILS = 2
    internal const val ACTION_COUNT = 3

    /** One band row. */
    private const val ACTION_H = 52f

    /** Height of the settings band under the list. */
    internal const val SETTINGS_H = ACTION_H * ACTION_COUNT

    /**
     * The file list, with [selected] highlighted.
     *
     * Taller than the info bar because it has to hold a page of names, and drawn the
     * same way for the same reason: this side has the text stack.
     *
     * The window scrolls only when the highlight would leave it, so the list stays
     * still while you move within a page - a list that re-centres on every press is
     * much harder to track than one that holds position.
     */
    fun buildList(
        names: List<String>,
        selected: Int,
        musicMuted: Boolean = false,
        /**
         * What this list is. The same drawing serves the panoramas and the nodes of
         * a scene, because they are the same thing to look at - a page of names with
         * one highlighted - and giving the second its own builder would be two
         * layouts to keep in step with one [rowAt].
         */
        title: String = "Panoramas",
        subtitle: String = "${names.size} on the headset",
        empty: String = "Nothing on the headset",
        emptyHint: String = "Send files from the browser page shown in the app panel.",
        /**
         * True when this is a scene's nodes rather than the files.
         *
         * Only the controls strip cares: in a scene the button that opens and closes
         * the list steps back to the files instead, and a strip that still said
         * "open/close" would be describing something the button no longer does.
         */
        inScene: Boolean = false,
        /**
         * Where a browser should point to send files, or null if the server is down.
         *
         * It lives up here because the app opens straight into a panorama now, so the
         * 2D panel that used to carry this address may never be looked at. An address
         * nobody can find is the same as no server.
         */
        serverUrl: String? = null,
        /**
         * What the last rescan found, or null if none has been asked for.
         *
         * Shown on the rescan row itself. Sending files from a browser changes what
         * is on the headset while somebody is standing in a panorama looking at a
         * list that was built before they sent them, so the one thing that row has
         * to answer is "did that do anything" - and a count that goes 0 to 5 answers
         * it where a row that just flickers does not.
         */
        rescanNote: String? = null,
    ): Triple<ByteBuffer, Int, Int> {
        val h = listHeight(names.size)
        val bmp = Bitmap.createBitmap(WIDTH, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT)

        val panel = RectF(16f, 16f + BRAND_H, WIDTH - 16f, h - 16f)
        // A soft drop shadow under the panel, so it lifts off the panorama instead
        // of lying flat on it. Drawn as the same rounded rectangle offset down and
        // blurred; the panel itself is painted over the top of it.
        c.drawRoundRect(
            RectF(panel.left + 3f, panel.top + 6f, panel.right + 3f, panel.bottom + 8f),
            22f, 22f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x99000000.toInt()
                maskFilter = android.graphics.BlurMaskFilter(14f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            },
        )
        c.drawRoundRect(panel, 22f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BG })
        c.drawRoundRect(panel, 22f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f; color = RULE
        })
        brand(c, panel)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT; textSize = 40f
            typeface = Typeface.create(UI, Typeface.BOLD)
        }
        val row = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT; textSize = 34f
            typeface = Typeface.create(UI, Typeface.NORMAL)
        }
        val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DIM; textSize = 26f
            typeface = Typeface.create(UI, Typeface.NORMAL)
        }
        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E4F6B.toInt() }

        val inset = panel.left + 34f
        val room = panel.width() - 68f
        if (names.isEmpty()) {
            c.drawText(empty, inset, panel.top + 62f, titlePaint)
            c.drawText(ellipsize(emptyHint, dim, room), inset, panel.top + 108f, dim)
            return finish(bmp)
        }

        c.drawText(title, inset, panel.top + 56f, titlePaint)
        c.drawText(subtitle, inset, panel.top + 96f, dim)

        // Files page; the music switch does not. It lives in its own band below the
        // list and is always on screen, because a setting that scrolls off is one
        // you have to go looking for - and paging past the end of the files to reach
        // it read as the list having one strange extra entry.
        address(c, panel, inset, serverUrl)

        val first = (selected / PAGE) * PAGE
        var y = panel.top + 152f + HEADER_H
        for (i in first until minOf(first + PAGE, names.size)) {
            if (i == selected) {
                c.drawRoundRect(RectF(panel.left + 12f, y - 40f, panel.right - 12f, y + 14f),
                    8f, 8f, mark)
            }
            c.drawText(ellipsize(names[i], row, room - 20f), inset, y, row)
            y += 62f
        }

        settings(c, panel, inset, selected - names.size, musicMuted, rescanNote)
        controls(c, panel, inScene)
        return finish(bmp)
    }

    /**
     * The band under the list: things that are not a file.
     *
     * Separated by a rule rather than just a gap, so it reads as a different kind of
     * thing rather than the last item of the list.
     */
    /**
     * "Send files from ..." and the address, under a rule.
     *
     * Dimmed and small: it is a reference, not an instruction, and it should not
     * compete with the list of places you came here to stand in.
     */
    /**
     * "QuestTime VR" above the panel, with its own shadow so it stays legible over a
     * bright sky as well as a dark one.
     */
    private fun brand(c: Canvas, panel: RectF) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT
            textSize = 42f
            typeface = Typeface.create(UI, Typeface.BOLD)
            setShadowLayer(10f, 0f, 3f, 0xCC000000.toInt())
        }
        c.drawText("QuestTime VR", panel.left + 6f, panel.top - 16f, p)
    }

    private fun address(c: Canvas, panel: RectF, inset: Float, url: String?) {
        val top = panel.top + 118f
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DIM; textSize = 24f
            typeface = Typeface.create(UI, Typeface.NORMAL)
        }
        val addr = Paint(label).apply {
            color = 0xFF7FB2E0.toInt()
            typeface = Typeface.create(UI, Typeface.BOLD)
        }
        if (url == null) {
            c.drawText("Upload server not running", inset, top + 22f, label)
        } else {
            c.drawText("Send files from", inset, top + 22f, label)
            c.drawText(url, inset + label.measureText("Send files from "), top + 22f, addr)
        }
        c.drawLine(panel.left + 12f, top + HEADER_H - 8f, panel.right - 12f, top + HEADER_H - 8f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f; color = RULE })
    }

    private fun settings(
        c: Canvas, panel: RectF, inset: Float, selectedAction: Int, musicMuted: Boolean,
        rescanNote: String?,
    ) {
        val top = panel.bottom - 104f - SETTINGS_H
        c.drawLine(panel.left + 12f, top, panel.right - 12f, top,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f; color = RULE })

        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT; textSize = 28f
            typeface = Typeface.create(UI, Typeface.NORMAL)
        }
        val state = Paint(label).apply {
            typeface = Typeface.create(UI, Typeface.BOLD)
        }

        for (i in 0 until ACTION_COUNT) {
            val rowTop = top + i * ACTION_H
            val y = rowTop + 36f
            if (selectedAction == i) {
                c.drawRoundRect(
                    RectF(panel.left + 12f, rowTop + 4f, panel.right - 12f, rowTop + ACTION_H - 4f),
                    8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E4F6B.toInt() })
            }
            when (i) {
                ACTION_MUSIC -> {
                    c.drawText("Background music", inset, y, label)
                    state.color = if (musicMuted) DIM else 0xFF7FB2E0.toInt()
                    val t = if (musicMuted) "off" else "on"
                    c.drawText(t, panel.right - 34f - state.measureText(t), y, state)
                }
                ACTION_RESCAN -> {
                    c.drawText("Look for new files", inset, y, label)
                    // Dim until it has been used, then the count in the same blue
                    // the music row uses for "on" - the two rows report in the same
                    // voice, so neither needs explaining twice.
                    state.color = if (rescanNote == null) DIM else 0xFF7FB2E0.toInt()
                    val t = rescanNote ?: "trigger"
                    c.drawText(t, panel.right - 34f - state.measureText(t), y, state)
                }
                ACTION_DETAILS -> {
                    c.drawText("What is this panorama", inset, y, label)
                    state.color = DIM
                    c.drawText("B or Y", panel.right - 34f - state.measureText("B or Y"), y, state)
                }
            }
        }
    }

    /**
     * A drawn controller along the bottom of the list, with each control labelled.
     *
     * A line of text saying "A or X opens" is read once and forgotten; a picture of
     * the thing in your hand is read every time you look down. There is nowhere else
     * in the headset that says what the buttons do - no manual, no tooltip - so it
     * has to be on the panel you are already looking at.
     *
     * Schematic rather than a model of a Touch controller: what matters is which of
     * the two round buttons is which, and that the stick does two things.
     */
    private fun controls(c: Canvas, panel: RectF, inScene: Boolean = false) {
        val top = panel.bottom - 104f
        c.drawLine(panel.left + 12f, top, panel.right - 12f, top,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f; color = RULE })

        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A2A2A.toInt() }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xFF5A5A5A.toInt()
        }
        val lit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3E6E9C.toInt() }
        val lbl = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DIM; textSize = 21f
            typeface = Typeface.create(UI, Typeface.NORMAL)
        }
        val cap = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT; textSize = 19f
            typeface = Typeface.create(UI, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        // The controller: a rounded body with a stick and two buttons.
        val cx = panel.left + 104f
        val cy = top + 50f
        val face = RectF(cx - 64f, cy - 38f, cx + 64f, cy + 38f)
        c.drawRoundRect(face, 20f, 20f, body)
        c.drawRoundRect(face, 20f, 20f, edge)

        // Laid out as the real thing is: stick up and left of the two buttons, which
        // sit diagonally with the lower one nearer the thumb.
        c.drawCircle(cx - 34f, cy - 8f, 16f, lit)           // thumbstick
        c.drawCircle(cx - 34f, cy - 8f, 16f, edge)
        c.drawCircle(cx + 14f, cy + 12f, 13f, lit)          // lower button: A / X
        c.drawCircle(cx + 14f, cy + 12f, 13f, edge)
        c.drawCircle(cx + 40f, cy - 14f, 13f, body)         // upper button: B / Y
        c.drawCircle(cx + 40f, cy - 14f, 13f, edge)
        c.drawText("A", cx + 14f, cy + 19f, cap)
        c.drawText("B", cx + 40f, cy - 7f, cap)

        // Two columns of "control -> what it does", aligned on their own x rather
        // than padded with spaces: monospace makes space-padding look aligned until
        // one label grows, and then it silently is not.
        val key = Paint(lbl).apply { color = TEXT }
        data class Row(val press: String, val does: String)
        val leftCol = listOf(Row("Stick up/down", "move"), Row("Trigger", "choose"))
        val rightCol = listOf(
            Row("A or X", if (inScene) "back" else "open/close"),
            Row("B or Y", "details"))
        // Measured, not eyeballed: the longest key is "Stick up/down" and at this
        // size it is wider than the gap the first guess left, so it ran into its own
        // value column.
        val gap = key.measureText("Stick up/down ")
        val colX = floatArrayOf(cx + 96f, cx + 96f + gap, cx + 96f + gap + 150f,
                                cx + 96f + 2 * gap + 150f)
        var ly = top + 40f
        for (i in 0 until 2) {
            c.drawText(leftCol[i].press, colX[0], ly, key)
            c.drawText(leftCol[i].does, colX[1], ly, lbl)
            c.drawText(rightCol[i].press, colX[2], ly, key)
            c.drawText(rightCol[i].does, colX[3], ly, lbl)
            ly += 30f
        }
        c.drawText("Stick left/right turns you.  X and Y = left controller.",
            colX[0], ly, lbl)
    }

    private fun finish(bmp: Bitmap): Triple<ByteBuffer, Int, Int> {
        val px = ByteBuffer.allocateDirect(bmp.width * bmp.height * 4)
        bmp.copyPixelsToBuffer(px)
        px.rewind()
        val t = Triple(px, bmp.width, bmp.height)
        bmp.recycle()
        return t
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
