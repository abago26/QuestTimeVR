package com.questtime.vr

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fills the parts of a cubic panorama that were never captured.
 *
 * A cubic QuickTime VR is usually converted from a cylindrical original, so
 * everything beyond the source's tilt range is baked into the faces as black. On the
 * street sample that is everything past +/-40.5 degrees: most of the top and bottom
 * faces, plus curved bites out of the four sides. Left alone you get a black dome
 * above and below, and ragged triangles where a face corner happens to fall inside
 * the captured band while its neighbours do not.
 *
 * Rather than patch pixels per face, this works in angles: any direction outside the
 * captured tilt range gets the gradient, whichever face it lands on. That makes the
 * fill continuous across face boundaries by construction, which is what removes the
 * triangles - they were never a seam problem, just the capture edge cutting across
 * the cube's geometry.
 *
 * Faces are in OpenGL order (+X, -X, +Y, -Y, +Z, -Z) with OpenXR's axes: -Z forward,
 * +X right, +Y up.
 */
object CubeCaps {

    private const val AZIMUTH_SAMPLES = 256
    private const val CONTROL_POINTS = 12

    /** Degrees inside the capture edge that get blended out, to avoid a hard rim. */
    private const val FEATHER_DEG = 4.0

    /** Direction for a texel, matching the inverse of GL's cubemap face selection. */
    private fun direction(face: Int, s: Double, t: Double): DoubleArray {
        val sc = 2.0 * s - 1.0
        val tc = 2.0 * t - 1.0
        return when (face) {
            0 -> doubleArrayOf(1.0, -tc, -sc)     // +X
            1 -> doubleArrayOf(-1.0, -tc, sc)     // -X
            2 -> doubleArrayOf(sc, 1.0, tc)       // +Y
            3 -> doubleArrayOf(sc, -1.0, -tc)     // -Y
            4 -> doubleArrayOf(sc, -tc, 1.0)      // +Z
            else -> doubleArrayOf(-sc, -tc, -1.0) // -Z
        }
    }

    /** GL's cubemap lookup: direction in, colour out. */
    private fun sample(faces: List<ByteArray>, n: Int, x: Double, y: Double, z: Double): IntArray {
        val ax = abs(x); val ay = abs(y); val az = abs(z)
        val face: Int; val sc: Double; val tc: Double; val ma: Double
        if (ax >= ay && ax >= az) {
            if (x > 0) { face = 0; sc = -z; tc = -y; ma = ax } else { face = 1; sc = z; tc = -y; ma = ax }
        } else if (ay >= az) {
            if (y > 0) { face = 2; sc = x; tc = z; ma = ay } else { face = 3; sc = x; tc = -z; ma = ay }
        } else {
            if (z > 0) { face = 4; sc = x; tc = -y; ma = az } else { face = 5; sc = -x; tc = -y; ma = az }
        }
        val u = ((sc / ma + 1.0) / 2.0 * n).toInt().coerceIn(0, n - 1)
        val v = ((tc / ma + 1.0) / 2.0 * n).toInt().coerceIn(0, n - 1)
        val o = (v * n + u) * 3
        val src = faces[face]
        return intArrayOf(src[o].toInt() and 0xFF, src[o + 1].toInt() and 0xFF,
            src[o + 2].toInt() and 0xFF)
    }

    /** Ring of colours around the horizon at a given elevation, low-passed. */
    private fun edgeRing(faces: List<ByteArray>, n: Int, elevDeg: Double): DoubleArray {
        val raw = DoubleArray(AZIMUTH_SAMPLES * 3)
        val lat = Math.toRadians(elevDeg)
        for (a in 0 until AZIMUTH_SAMPLES) {
            val lon = a.toDouble() / AZIMUTH_SAMPLES * 2.0 * Math.PI
            val c = sample(faces, n, cos(lat) * sin(lon), sin(lat), -cos(lat) * cos(lon))
            raw[a * 3] = c[0].toDouble(); raw[a * 3 + 1] = c[1].toDouble(); raw[a * 3 + 2] = c[2].toDouble()
        }
        // Collapse to a few control points then interpolate back, wrapping. Taken raw
        // this reads as vertical streaks; smoothed it reads as light carrying on.
        val cp = DoubleArray(CONTROL_POINTS * 3)
        for (c in 0 until CONTROL_POINTS) {
            val a0 = c * AZIMUTH_SAMPLES / CONTROL_POINTS
            val a1 = (c + 1) * AZIMUTH_SAMPLES / CONTROL_POINTS
            for (a in a0 until a1) for (ch in 0 until 3) cp[c * 3 + ch] += raw[a * 3 + ch]
            for (ch in 0 until 3) cp[c * 3 + ch] /= (a1 - a0).coerceAtLeast(1).toDouble()
        }
        val out = DoubleArray(AZIMUTH_SAMPLES * 3)
        for (a in 0 until AZIMUTH_SAMPLES) {
            val f = a.toDouble() * CONTROL_POINTS / AZIMUTH_SAMPLES - 0.5
            var i0 = Math.floor(f).toInt()
            val k = smoothstep(f - i0)
            i0 = ((i0 % CONTROL_POINTS) + CONTROL_POINTS) % CONTROL_POINTS
            val i1 = (i0 + 1) % CONTROL_POINTS
            for (ch in 0 until 3) out[a * 3 + ch] = cp[i0 * 3 + ch] * (1 - k) + cp[i1 * 3 + ch] * k
        }
        return out
    }

    private fun ringMean(ring: DoubleArray): DoubleArray {
        val m = DoubleArray(3)
        val n = ring.size / 3
        for (a in 0 until n) for (ch in 0 until 3) m[ch] += ring[a * 3 + ch]
        for (ch in 0 until 3) m[ch] = m[ch] / n
        return m
    }

    private fun smoothstep(t: Double): Double {
        val x = t.coerceIn(0.0, 1.0)
        return x * x * (3.0 - 2.0 * x)
    }

    /**
     * Replace everything outside [minElevDeg]..[maxElevDeg] with a gradient drawn
     * from the capture edge. Returns new faces; the originals are untouched.
     */
    fun fill(
        faces: List<ByteArray>, n: Int, minElevDeg: Double, maxElevDeg: Double,
    ): List<ByteArray> {
        val topRing = edgeRing(faces, n, maxElevDeg - 1.0)
        val botRing = edgeRing(faces, n, minElevDeg + 1.0)
        // One shared colour at both poles: the two ends of one room should not
        // disagree, and it keeps the top and bottom faces consistent where they meet
        // the sides.
        val tm = ringMean(topRing)
        val bm = ringMean(botRing)
        val poleMean = DoubleArray(3) { (tm[it] + bm[it]) / 2.0 }

        val out = faces.map { it.copyOf() }
        for (face in 0 until 6) {
            val dst = out[face]
            for (py in 0 until n) {
                val t = (py + 0.5) / n
                for (px in 0 until n) {
                    val s = (px + 0.5) / n
                    val d = direction(face, s, t)
                    val len = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
                    val elev = Math.toDegrees(asin(d[1] / len))
                    val above = elev > maxElevDeg - FEATHER_DEG
                    val below = elev < minElevDeg + FEATHER_DEG
                    if (!above && !below) continue

                    val lon = atan2(d[0], -d[2])          // 0 = forward, +ve to the right
                    var a = ((lon / (2.0 * Math.PI)) * AZIMUTH_SAMPLES).toInt()
                    a = ((a % AZIMUTH_SAMPLES) + AZIMUTH_SAMPLES) % AZIMUTH_SAMPLES

                    val ring = if (above) topRing else botRing
                    val edgeDeg = if (above) maxElevDeg else minElevDeg
                    // 0 at the feather's inner edge, 1 at the pole
                    val span = (90.0 - abs(edgeDeg)) + FEATHER_DEG
                    val k = smoothstep((abs(elev) - (abs(edgeDeg) - FEATHER_DEG)) / span)
                    // how much of the gradient to mix in at all
                    val mix = smoothstep((abs(elev) - (abs(edgeDeg) - FEATHER_DEG)) / FEATHER_DEG)

                    val o = (py * n + px) * 3
                    for (ch in 0 until 3) {
                        val grad = ring[a * 3 + ch] * (1 - k) + poleMean[ch] * k
                        val cur = (dst[o + ch].toInt() and 0xFF).toDouble()
                        val v = cur * (1 - mix) + grad * mix
                        dst[o + ch] = v.toInt().coerceIn(0, 255).toByte()
                    }
                }
            }
        }
        return out
    }
}
