package com.questtime.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class CubeTest {

    private val movFile = File("../../reference/testdata/street-1.mov")
    private val tilesFile = File("../../reference/truth/cube_tiles.rgb")

    private fun ffmpegTiles(): JpegDecoder {
        val all = tilesFile.readBytes()
        var next = 0
        return JpegDecoder { _, w, h ->
            val n = w * h * 3
            val from = next * n
            next++
            if (from + n > all.size) null else all.copyOfRange(from, from + n)
        }
    }

    private fun scene(): CubeScene {
        assumeTrue("fixtures missing", movFile.isFile && tilesFile.isFile)
        return Qtvr.extractCube(movFile.readBytes(), ffmpegTiles())
    }

    @Test
    fun detectsCubicAndReadsAngles() {
        assumeTrue(movFile.isFile)
        val bytes = movFile.readBytes()
        assertTrue("should be detected as cubic", Qtvr.isCubic(bytes))
        val s = scene()
        assertEquals(696, s.size)
        assertEquals(6, s.faces.size)
        val info = s.info!!
        assertEquals(40.5, info.vPanTop, 0.01)
        assertEquals(-40.5, info.vPanBottom, 0.01)
        assertEquals("cube", info.panoType)
    }

    /** The whole point: no black voids left anywhere on the sphere. */
    @Test
    fun noBlackVoidsRemain() {
        val s = scene()
        var dark = 0
        var total = 0
        for (face in s.faces) {
            var i = 0
            while (i < face.size) {
                val sum = (face[i].toInt() and 0xFF) + (face[i + 1].toInt() and 0xFF) +
                    (face[i + 2].toInt() and 0xFF)
                if (sum < 24) dark++
                total++
                i += 3
            }
        }
        val pct = dark * 100.0 / total
        println("near-black texels after fill: $pct %")
        assertTrue("still $pct% black", pct < 0.5)
    }

    /** Writes the filled faces so they can be rendered and eyeballed. */
    @Test
    fun dumpFilledFaces() {
        val s = scene()
        for ((i, f) in s.faces.withIndex()) {
            File("../../reference/truth/filled_gl_$i.rgb").writeBytes(f)
        }
        println("wrote 6 filled faces at ${s.size}x${s.size}")
    }
}
