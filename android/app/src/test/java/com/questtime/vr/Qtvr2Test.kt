package com.questtime.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * QuickTime VR 2.x, checked against ffmpeg the same way the 1.0 path is.
 *
 * The tiles are Photo-JPEG, and Android's unit-test classpath has no JPEG decoder
 * (on device this is BitmapFactory). So the decoder seam is fed ffmpeg's own decoded
 * tiles instead. That is not a weaker test - it is a sharper one: with the codec
 * factored out, container parsing, descriptor decoding, tile assembly and rotation
 * must reproduce ffmpeg's panorama byte for byte, with nothing to hide behind.
 */
class Qtvr2Test {

    private val movFile = File("../../reference/testdata/chapel_hi.mov")
    private val truthFile = File("../../reference/truth/chapel_flat.rgb")

    private val tilesFile = File("../../reference/truth/chapel_tiles.rgb")

    /** Hands back ffmpeg's decoded tiles in order, standing in for BitmapFactory. */
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

    private fun mov(): ByteArray {
        assumeTrue("fixture missing: ${movFile.path}", movFile.isFile)
        return movFile.readBytes()
    }

    @Test
    fun readsTheVersion2Descriptor() {
        val tracks = MovParser.parseTracks(mov())
        val panoTrack = tracks.first { it.handler == "pano" }
        val r = panoTrack.sampleRanges().first()
        val data = mov()
        val info = PanoInfo.parseV2(
            data.copyOfRange(r.first.toInt(), (r.first + r.second).toInt())
        )!!

        assertEquals(2, info.majorVersion)
        assertEquals(0.0, info.hPanStart, 1e-4)
        assertEquals(360.0, info.hPanEnd, 1e-4)
        assertEquals(28.2093, info.vPanTop, 1e-3)
        assertEquals(-28.2093, info.vPanBottom, 1e-3)
        assertEquals(1508, info.sceneSizeX)
        assertEquals(8832, info.sceneSizeY)
        assertEquals(1, info.framesX)
        assertEquals(96, info.framesY)
        assertTrue("should not be cubic", !info.isCubic)
        assertEquals(8832, info.panoWidth)
        assertEquals(1508, info.panoHeight)
    }

    /**
     * QuickTime 5 stopped requiring panoramas to be stored on their side, so the
     * descriptor now has to be asked which way this one is. Both sample files here
     * are the old rotated form; the upright form is recognised and refused, because
     * rotating it would put the horizon down the side of your view without saying so.
     */
    @Test
    fun readsStoredOrientation() {
        val tracks = MovParser.parseTracks(mov())
        val panoTrack = tracks.first { it.handler == "pano" }
        val r = panoTrack.sampleRanges().first()
        val data = mov()
        val info = PanoInfo.parseV2(
            data.copyOfRange(r.first.toInt(), (r.first + r.second).toInt())
        )!!

        // panoType blank, so the low bit of flags carries it - and it is clear,
        // meaning the image was rotated 90 degrees counter-clockwise before dicing.
        assertEquals("", info.panoType)
        assertEquals(0L, info.flags and 1L)
        assertTrue("chapel is the rotated form", info.storedRotated)
    }

    @Test
    fun orientationFollowsPanoTypeThenFlags() {
        fun info(type: String, flags: Long) = PanoInfo(
            hPanStart = 0.0, hPanEnd = 360.0, vPanTop = 45.0, vPanBottom = -45.0,
            sceneSizeX = 100, sceneSizeY = 100, numFrames = 1, framesX = 1, framesY = 1,
            hotSpotSizeX = 0, hotSpotSizeY = 0, majorVersion = 2,
            panoType = type, flags = flags,
        )
        // panoType wins where it is present, whatever the flags say.
        assertTrue("vcyl is rotated", info("vcyl", 1).storedRotated)
        assertTrue("hcyl is upright", !info("hcyl", 0).storedRotated)
        // Blank panoType falls back to the flags bit: set means upright.
        assertTrue("blank + clear bit is rotated", info("", 0).storedRotated)
        assertTrue("blank + set bit is upright", !info("", 1).storedRotated)
        // 1.0 has neither, and always stored panoramas rotated.
        assertTrue("1.0 default is rotated", info("", 0).copy(majorVersion = 1).storedRotated)
    }

    /**
     * The upright-storage refusal, exercised on a real file.
     *
     * No QuickTime VR panorama stored upright could be found in the wild - every
     * cylindrical file examined, from 1995 to 2007 and across three archives, uses
     * the legacy rotated form. So the case is built from the chapel by flipping the
     * one bit that distinguishes it, which is exactly what the app keys off. This
     * tests the app's reading of the descriptor, not what a real 'hcyl' file's
     * pixels look like - which is precisely why it is refused rather than rotated.
     */
    private fun patchedDescriptor(patch: (ByteArray, Int) -> Unit): ByteArray {
        val data = mov().copyOf()
        var at = -1
        for (i in 0 until data.size - 4) {
            if (data.fourCC(i) == "pdat") { at = i; break }
        }
        assertTrue("chapel should carry a 'pdat' atom", at >= 0)
        patch(data, at + 16)
        return data
    }

    @Test
    fun refusesAPanoramaStoredUpright() {
        // flags is at offset 72 of the pano sample atom; bit 0 set means upright.
        val flagged = patchedDescriptor { d, p -> d[p + 72 + 3] = 1 }
        val byFlags = runCatching { Qtvr.extract(flagged, ffmpegTiles()) }.exceptionOrNull()
        assertTrue("should refuse, got $byFlags", byFlags is IllegalArgumentException)
        assertTrue(
            "message should explain the orientation, was: ${byFlags?.message}",
            byFlags?.message?.contains("upright") == true,
        )

        // panoType is at offset 76, and overrides the flags bit when present.
        val typed = patchedDescriptor { d, p ->
            "hcyl".forEachIndexed { i, c -> d[p + 76 + i] = c.code.toByte() }
        }
        val byType = runCatching { Qtvr.extract(typed, ffmpegTiles()) }.exceptionOrNull()
        assertTrue("should refuse 'hcyl', got $byType", byType is IllegalArgumentException)
        assertTrue(
            "message should name the type, was: ${byType?.message}",
            byType?.message?.contains("hcyl") == true,
        )

        // And the untouched file must still go through, or the guard is too eager.
        assertEquals(8832, Qtvr.extract(mov(), ffmpegTiles()).width)
    }

    /** Here the descriptor and the pixels agree, unlike the 1.0 sample. */
    @Test
    fun descriptorAgreesWithPixelGeometry() {
        val p = Qtvr.extract(mov(), ffmpegTiles())
        val halfFov = Math.toDegrees(
            Math.atan(p.centralAngle.toDouble() / (2.0 * p.aspectRatio))
        )
        assertEquals(28.2093, halfFov, 0.01)
        assertEquals(p.info!!.vPanTop, halfFov, 0.01)
    }

    @Test
    fun matchesFfmpeg() {
        val data = mov()
        assumeTrue("ground truth missing", truthFile.isFile && tilesFile.isFile)
        val truth = truthFile.readBytes()

        val p = Qtvr.extract(data, ffmpegTiles())
        assertEquals(8832, p.width)
        assertEquals(1508, p.height)
        assertEquals("byte count", truth.size, p.rgb.size)

        var mismatches = 0
        var firstAt = -1
        for (i in truth.indices) {
            if (p.rgb[i] != truth[i]) {
                mismatches++
                if (firstAt < 0) firstAt = i
            }
        }
        assertEquals("channels differing from ffmpeg (first at byte $firstAt)", 0, mismatches)
    }
}
