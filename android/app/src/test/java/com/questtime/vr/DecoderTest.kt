package com.questtime.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Checks the decoder against ffmpeg, on the JVM, with no device involved.
 *
 * Ground truth is generated once by reference/make_truth.sh:
 *   ffmpeg -i eciqtvr_hr1.mov -map 0:v -vf tile=1x24 -frames:v 1 stacked.png
 *   ffmpeg -i stacked.png -f rawvideo -pix_fmt rgb24 stacked.rgb
 *
 * The comparison is against the *stacked* image, before rotation, so it isolates
 * container parsing and Cinepak decoding from the geometry work.
 */
class DecoderTest {

    private val root = File("../../reference")
    private val movFile = File(root, "testdata/eciqtvr_hr1.mov")
    private val truthFile = File(root, "truth/stacked.rgb")

    private fun mov(): ByteArray {
        assumeTrue("test fixture missing: ${movFile.path}", movFile.isFile)
        return movFile.readBytes()
    }

    @Test
    fun parsesTracks() {
        val tracks = MovParser.parseTracks(mov())
        val video = tracks.first { it.handler == "vide" }
        assertEquals("cvid", video.format)
        assertEquals(768, video.width)
        assertEquals(104, video.height)
        assertEquals(24, video.sampleRanges().size)

        val pano = tracks.first { it.format == "pano" }
        assertNotNull(pano.sampleDescription)
    }

    @Test
    fun parsesPanoDescriptor() {
        val tracks = MovParser.parseTracks(mov())
        val info = PanoInfo.parse(tracks.first { it.format == "pano" }.sampleDescription)!!
        assertEquals(0.0, info.hPanStart, 1e-6)
        assertEquals(360.0, info.hPanEnd, 1e-6)
        assertEquals(42.5, info.vPanTop, 1e-6)
        assertEquals(-42.5, info.vPanBottom, 1e-6)
        assertEquals(768, info.sceneSizeX)
        assertEquals(2496, info.sceneSizeY)
        assertEquals(24, info.numFrames)
        assertEquals(2496, info.panoWidth)
        assertEquals(768, info.panoHeight)
    }

    /** The important one: every channel must match ffmpeg exactly. */
    @Test
    fun cinepakMatchesFfmpegExactly() {
        val data = mov()
        assumeTrue("ground truth missing: ${truthFile.path}", truthFile.isFile)
        val truth = truthFile.readBytes()

        val video = MovParser.parseTracks(data).first { it.handler == "vide" }
        val dec = Cinepak(video.width, video.height)
        val tiles = video.sampleRanges().map { (off, size) ->
            dec.decode(data.copyOfRange(off.toInt(), (off + size).toInt())).copyOf()
        }
        val (stacked, w, h) = Qtvr.stack(tiles, video.width, video.height)

        assertEquals("dimensions", 768 to 2496, w to h)
        assertEquals("byte count", truth.size, stacked.size)

        var mismatches = 0
        var worst = 0
        var firstAt = -1
        for (i in truth.indices) {
            val d = kotlin.math.abs((stacked[i].toInt() and 0xFF) - (truth[i].toInt() and 0xFF))
            if (d != 0) {
                mismatches++
                if (firstAt < 0) firstAt = i
                if (d > worst) worst = d
            }
        }
        assertEquals(
            "channels differing from ffmpeg (worst delta $worst, first at byte $firstAt)",
            0, mismatches,
        )
    }

    @Test
    fun rotationProducesUprightPanorama() {
        val pano = Qtvr.extract(mov())
        assertEquals(2496, pano.width)
        assertEquals(768, pano.height)
        assertEquals(2496 * 768 * 3, pano.rgb.size)
    }

    /**
     * The cylinder layer derives its vertical extent from centralAngle and
     * aspectRatio, so those two numbers are the whole geometry contract.
     */
    @Test
    fun cylinderGeometryIsAFullTurn() {
        val pano = Qtvr.extract(mov())
        assertEquals(2.0 * Math.PI, pano.centralAngle.toDouble(), 1e-4)
        assertEquals(2496.0 / 768.0, pano.aspectRatio.toDouble(), 1e-4)

        // implied half vertical FOV = atan(centralAngle / (2 * aspectRatio))
        val halfFov = Math.toDegrees(
            Math.atan(pano.centralAngle.toDouble() / (2.0 * pano.aspectRatio))
        )
        assertTrue("half FOV $halfFov out of range", halfFov > 43.0 && halfFov < 45.0)
    }

    @Test
    fun rejectsOrdinaryMovies() {
        // A minimal file with no pano track should be refused with a clear message,
        // not a crash. Reuse the real file but strip the pano sample description.
        val data = mov()
        val idx = indexOf(data, "pano".toByteArray(Charsets.ISO_8859_1))
        assertTrue("expected a pano atom in the fixture", idx > 0)
        val hacked = data.copyOf()
        "xxxx".toByteArray(Charsets.ISO_8859_1).copyInto(hacked, idx)

        val err = runCatching { Qtvr.extract(hacked) }.exceptionOrNull()
        assertNotNull("expected a rejection", err)
        assertTrue(
            "unhelpful message: ${err?.message}",
            err!!.message!!.contains("panorama", ignoreCase = true) ||
                err.message!!.contains("VR", ignoreCase = true),
        )
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
