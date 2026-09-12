package com.questtime.vr

/**
 * Extends a panorama vertically with a soft gradient drawn from its own top and
 * bottom edges, so the band does not end in a hard black horizon.
 *
 * This works precisely because a cylinder layer maps texture rows onto the cylinder
 * by angle = atan(y / radius): adding rows extends the covered angle without moving
 * a single pixel of the original image. The added rows do get stretched hard as they
 * approach the pole, which would ruin detail - but there is no detail in a gradient,
 * so the distortion is invisible.
 *
 * The edge colour is heavily low-passed around the full 360 degrees before use.
 * Per-column colour taken raw reads as vertical streaks; smoothed, it reads as the
 * room's own light continuing past the edge of what was captured.
 */
object Caps {

    /** Rows averaged at each edge to sample its colour. */
    private const val EDGE_ROWS = 12

    /** Control points around the full turn. Few enough to be a wash, not streaks. */
    private const val CONTROL_POINTS = 12

    /**
     * Rows at each edge of the photographic band that are faded into the gradient.
     * Without this the detail stops dead at the band boundary and reads as a hard
     * horizon line; feathered, the room dissolves into its own light.
     */
    private const val FEATHER = 22

    /** Angular coverage we would like the gradient to reach, past the photograph. */
    private const val TARGET_HALF_ANGLE_DEG = 60.0

    /**
     * Ceiling on the RGBA texture this produces. Three swapchain images of it, so
     * 128 MB here is ~380 MB of GPU memory - comfortable on a Quest 3, and needed
     * since the upscaled library is ~2.7x wider. At 64 MB a 6756-wide panorama had
     * room for only about 5 degrees of gradient past the photograph.
     */
    private const val MAX_RGBA_BYTES = 128L * 1024 * 1024

    /**
     * How tall to make the texture for this panorama.
     *
     * A fixed row count gives wildly different angular coverage depending on the
     * panorama's width, because the cylinder's radius in pixels is width / 2*pi.
     * So aim for an angle instead, then clamp to a memory budget - the polar cap
     * quads cover whatever angle is left over.
     */
    fun targetHeight(pano: Panorama): Int {
        val radiusPx = pano.width / (2.0 * Math.PI)
        val ideal = (2.0 * radiusPx * Math.tan(Math.toRadians(TARGET_HALF_ANGLE_DEG))).toInt()
        val budget = (MAX_RGBA_BYTES / (4L * pano.width)).toInt()
        return maxOf(pano.height, minOf(ideal, budget))
    }

    /**
     * Grow [pano] to [targetHeight] total rows, centring the original band and
     * filling above and below with the gradient. Returns [pano] unchanged if it is
     * already at least that tall.
     */
    fun addGradient(pano: Panorama, targetHeight: Int): Panorama {
        val w = pano.width
        val h = pano.height
        if (targetHeight <= h) return pano

        val pad = (targetHeight - h) / 2
        val newH = h + 2 * pad
        val src = pano.rgb
        val out = ByteArray(w * newH * 3)

        // original band, centred
        System.arraycopy(src, 0, out, pad * w * 3, w * h * 3)

        val top = smoothedEdge(src, w, h, fromTop = true)
        val bottom = smoothedEdge(src, w, h, fromTop = false)
        // Both ends converge on the SAME colour. That is partly taste - the two
        // poles of one room should not disagree - but mainly robustness: the polar
        // cap quads sample a single row of this texture, and if the compositor
        // measures subImage offsets top-down rather than bottom-up they would pick
        // up each other's colour. Converging on one mean makes that unknowable
        // convention stop mattering.
        val topEdgeMean = mean(top)
        val bottomEdgeMean = mean(bottom)
        val poleMean = FloatArray(3) { (topEdgeMean[it] + bottomEdgeMean[it]) / 2f }
        val topMean = poleMean
        val bottomMean = poleMean

        // Feather the outermost rows of the band into the edge colour, so the
        // photograph fades out rather than ending on a line.
        for (i in 0 until minOf(FEATHER, h / 2)) {
            val k = smoothstep(1f - i.toFloat() / FEATHER)   // 1 outermost, 0 inward
            blendToward(out, w, pad + i, top, k)
            blendToward(out, w, pad + h - 1 - i, bottom, k)
        }

        for (r in 0 until pad) {
            // r = 0 is the pole; r = pad-1 sits against the band.
            val t = (pad - r).toFloat() / pad          // 0 at the band, 1 at the pole
            val k = smoothstep(t)
            writeRow(out, w, r, top, topMean, k)
            writeRow(out, w, newH - 1 - r, bottom, bottomMean, k)
        }

        return Panorama(out, w, newH, pano.info)
    }

    /** Average the outermost rows per column, then low-pass around the full turn. */
    private fun smoothedEdge(src: ByteArray, w: Int, h: Int, fromTop: Boolean): FloatArray {
        val raw = FloatArray(w * 3)
        for (x in 0 until w) {
            var r = 0f; var g = 0f; var b = 0f
            for (i in 0 until EDGE_ROWS) {
                val y = if (fromTop) i else h - 1 - i
                val o = (y * w + x) * 3
                r += (src[o].toInt() and 0xFF).toFloat()
                g += (src[o + 1].toInt() and 0xFF).toFloat()
                b += (src[o + 2].toInt() and 0xFF).toFloat()
            }
            raw[x * 3] = r / EDGE_ROWS
            raw[x * 3 + 1] = g / EDGE_ROWS
            raw[x * 3 + 2] = b / EDGE_ROWS
        }

        // Collapse to a handful of control points (averaging each wedge), then
        // interpolate back out. Wraps, because the panorama is a full turn.
        val cp = FloatArray(CONTROL_POINTS * 3)
        for (c in 0 until CONTROL_POINTS) {
            val x0 = c * w / CONTROL_POINTS
            val x1 = (c + 1) * w / CONTROL_POINTS
            var r = 0f; var g = 0f; var b = 0f
            for (x in x0 until x1) {
                r += raw[x * 3]; g += raw[x * 3 + 1]; b += raw[x * 3 + 2]
            }
            val n = (x1 - x0).coerceAtLeast(1).toFloat()
            cp[c * 3] = r / n; cp[c * 3 + 1] = g / n; cp[c * 3 + 2] = b / n
        }

        val outEdge = FloatArray(w * 3)
        for (x in 0 until w) {
            // position between control-point centres, wrapping
            val f = x.toFloat() * CONTROL_POINTS / w - 0.5f
            var i0 = Math.floor(f.toDouble()).toInt()
            val frac = f - i0
            i0 = ((i0 % CONTROL_POINTS) + CONTROL_POINTS) % CONTROL_POINTS
            val i1 = (i0 + 1) % CONTROL_POINTS
            val k = smoothstep(frac)
            for (ch in 0 until 3) {
                outEdge[x * 3 + ch] = cp[i0 * 3 + ch] * (1 - k) + cp[i1 * 3 + ch] * k
            }
        }
        return outEdge
    }

    private fun mean(edge: FloatArray): FloatArray {
        val m = FloatArray(3)
        val n = edge.size / 3
        for (x in 0 until n) for (ch in 0 until 3) m[ch] += edge[x * 3 + ch]
        for (ch in 0 until 3) m[ch] = m[ch] / n.toFloat()
        return m
    }

    /** Blend the edge colour toward the flat mean as the pole is approached. */
    private fun writeRow(
        out: ByteArray, w: Int, row: Int, edge: FloatArray, m: FloatArray, k: Float,
    ) {
        var o = row * w * 3
        for (x in 0 until w) {
            for (ch in 0 until 3) {
                val v = edge[x * 3 + ch] * (1 - k) + m[ch] * k
                out[o + ch] = v.toInt().coerceIn(0, 255).toByte()
            }
            o += 3
        }
    }

    /** Pull an existing row [k] of the way toward the smoothed edge colour. */
    private fun blendToward(out: ByteArray, w: Int, row: Int, edge: FloatArray, k: Float) {
        var o = row * w * 3
        for (x in 0 until w) {
            for (ch in 0 until 3) {
                val cur = (out[o + ch].toInt() and 0xFF).toFloat()
                val v = cur * (1 - k) + edge[x * 3 + ch] * k
                out[o + ch] = v.toInt().coerceIn(0, 255).toByte()
            }
            o += 3
        }
    }

    private fun smoothstep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
}
