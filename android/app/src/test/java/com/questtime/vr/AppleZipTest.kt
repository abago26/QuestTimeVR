package com.questtime.vr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Recovering resource forks from a Mac-made zip.
 *
 * Most of this builds its own archives rather than leaning on a fixture, because the
 * interesting cases - a name whose UTF-8 flag was never set, a sidecar that is not
 * AppleDouble, a member with no sidecar at all - are easier to construct than to find.
 * Those tests run on a bare clone.
 *
 * The last test is the one that matters most and is the one that skips: it checks the
 * reconstruction against `reference/applezip.py` over a real Mac archive, byte for
 * byte. See `reference/README.md` for how to make the fixture.
 */
class AppleZipTest {

    // ---- building archives ------------------------------------------------

    /** An AppleDouble sidecar carrying [rsrc] as entry id 2, plus a Finder-info entry. */
    private fun appleDouble(rsrc: ByteArray): ByteArray {
        val finder = ByteArray(32)
        val headerLen = 26 + 2 * 12
        val b = ByteBuffer.allocate(headerLen + finder.size + rsrc.size)
            .order(ByteOrder.BIG_ENDIAN)
        b.putInt(0x00051607)              // magic
        b.putInt(0x00020000)              // version
        b.put(ByteArray(16))              // filler
        b.putShort(2)                     // entry count
        b.putInt(9); b.putInt(headerLen); b.putInt(finder.size)
        b.putInt(2); b.putInt(headerLen + finder.size); b.putInt(rsrc.size)
        b.put(finder)
        b.put(rsrc)
        return b.array()
    }

    /**
     * A classic resource map holding one resource of [type].
     *
     * Only the fields the reader walks are filled in; the rest is the zero padding a
     * real map would carry anyway.
     */
    private fun resourceFork(type: String, payload: ByteArray): ByteArray {
        val dataArea = ByteBuffer.allocate(4 + payload.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(payload.size).put(payload).array()
        val dataOff = 256
        val mapOff = dataOff + dataArea.size
        val typeListOff = 28                       // from the start of the map
        val mapLen = typeListOff + 2 + 8 + 12
        val out = ByteArray(mapOff + mapLen)
        val b = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        b.putInt(0, dataOff)
        b.putInt(4, mapOff)
        System.arraycopy(dataArea, 0, out, dataOff, dataArea.size)
        b.putShort(mapOff + 24, typeListOff.toShort())
        val tl = mapOff + typeListOff
        b.putShort(tl, 0)                          // one type, stored as count - 1
        System.arraycopy(type.toByteArray(Charsets.ISO_8859_1), 0, out, tl + 2, 4)
        // Type entry is 8 bytes from tl+2: 4 of type, then count-1, then the ref
        // list offset. Writing the last two a field early lands inside the type
        // string and silently stops 'moov' matching.
        b.putShort(tl + 6, 0)                      // one resource, count - 1
        b.putShort(tl + 8, (2 + 8).toShort())      // ref list, from the type list start
        val ref = tl + 2 + 8
        out[ref + 5] = 0; out[ref + 6] = 0; out[ref + 7] = 0   // 3-byte data offset
        return out
    }

    /** A minimal but well-formed top-level `moov` atom. */
    private fun moovAtom(body: ByteArray = ByteArray(8)): ByteArray =
        ByteBuffer.allocate(8 + body.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(8 + body.size).put("moov".toByteArray(Charsets.ISO_8859_1))
            .put(body).array()

    private fun mdatAtom(size: Int): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
            .putInt(size).put("mdat".toByteArray(Charsets.ISO_8859_1))
            .put(ByteArray(size - 8)).array()

    /**
     * Write a zip the way `ditto` does: names as raw UTF-8 bytes with the UTF-8 flag
     * bit left clear, which is the whole reason [AppleZip.entryName] exists.
     *
     * Passing ISO_8859_1 to ZipOutputStream makes it write each char as one byte, so
     * feeding it a string that was itself decoded byte-for-char reproduces exactly the
     * bytes Finder would have written.
     */
    private fun macZip(entries: Map<String, ByteArray>): File {
        val f = File.createTempFile("mac-archive", ".zip")
        f.deleteOnExit()
        ZipOutputStream(f.outputStream(), Charsets.ISO_8859_1).use { z ->
            for ((name, bytes) in entries) {
                val raw = name.toByteArray(Charsets.UTF_8).toString(Charsets.ISO_8859_1)
                z.putNextEntry(ZipEntry(raw))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return f
    }

    private fun collect(archive: File): Pair<List<AppleZip.Member>, List<String>> {
        val got = ArrayList<AppleZip.Member>()
        val lost = ArrayList<String>()
        AppleZip.extract(archive, { got.add(it) }, { lost.add(it) })
        return got to lost
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b)
            .joinToString("") { "%02x".format(it) }

    // ---- the pieces -------------------------------------------------------

    @Test
    fun readsTheResourceForkOutOfAnAppleDoubleSidecar() {
        val rsrc = resourceFork("moov", moovAtom())
        val recovered = AppleZip.resourceForkFromAppleDouble(appleDouble(rsrc))
        assertArrayEquals(rsrc, recovered)
    }

    @Test
    fun refusesSomethingThatIsNotAppleDouble() {
        assertNull(AppleZip.resourceForkFromAppleDouble(ByteArray(64)))
        assertNull(AppleZip.resourceForkFromAppleDouble("not a sidecar".toByteArray()))
        // Truncated before the entry table: must not read off the end.
        assertNull(AppleZip.resourceForkFromAppleDouble(byteArrayOf(0, 5, 22, 7)))
    }

    @Test
    fun findsAMoovResourceInAResourceMap() {
        val payload = moovAtom()
        val found = AppleZip.findResource(resourceFork("moov", payload), "moov")
        assertArrayEquals(payload, found)
    }

    @Test
    fun doesNotInventAResourceThatIsNotThere() {
        assertNull(AppleZip.findResource(resourceFork("pnot", moovAtom()), "moov"))
        assertNull(AppleZip.findResource(ByteArray(8), "moov"))
    }

    @Test
    fun spotsAMoovInTheDataFork() {
        assertTrue(AppleZip.hasMoov(mdatAtom(64) + moovAtom()))
        assertTrue(AppleZip.hasMoov(moovAtom()))
        assertFalse(AppleZip.hasMoov(mdatAtom(64)))
        assertFalse(AppleZip.hasMoov(ByteArray(0)))
    }

    /** A zero or nonsense atom size must end the walk, not spin in it. */
    @Test
    fun doesNotHangOnAMalformedAtomChain() {
        val zeroSize = ByteArray(16)
        "mdat".toByteArray(Charsets.ISO_8859_1).copyInto(zeroSize, 4)
        assertFalse(AppleZip.hasMoov(zeroSize))

        val tooSmall = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putInt(2).put("mdat".toByteArray(Charsets.ISO_8859_1)).array()
        assertFalse(AppleZip.hasMoov(tooSmall))
    }

    // ---- whole archives ---------------------------------------------------

    @Test
    fun rescuesAMemberWhoseMoovIsOnlyInItsSidecar() {
        val data = mdatAtom(128)
        val moov = moovAtom("payload!".toByteArray())
        val zip = macZip(mapOf(
            "stuff/Nugget Inn" to data,
            "__MACOSX/stuff/._Nugget Inn" to appleDouble(resourceFork("moov", moov)),
        ))
        val (got, lost) = collect(zip)
        assertTrue(lost.toString(), lost.isEmpty())
        assertEquals(1, got.size)
        assertTrue(got[0].rescued)
        assertEquals("Nugget Inn.mov", got[0].name)
        assertArrayEquals(data + moov, got[0].bytes)
    }

    @Test
    fun leavesAMemberThatAlreadyCarriesItsMoovAlone() {
        val whole = mdatAtom(64) + moovAtom()
        val zip = macZip(mapOf("stuff/Monument Valley.mov" to whole))
        val (got, _) = collect(zip)
        assertEquals(1, got.size)
        assertFalse(got[0].rescued)
        assertEquals("Monument Valley.mov", got[0].name)
        assertArrayEquals(whole, got[0].bytes)
    }

    @Test
    fun reportsAMemberThatCannotBeRecoveredRatherThanDroppingIt() {
        val zip = macZip(mapOf("stuff/Headerless" to mdatAtom(64)))
        val (got, lost) = collect(zip)
        assertTrue(got.isEmpty())
        assertEquals(listOf("Headerless"), lost)
    }

    /**
     * The bug this whole encoding dance exists for: `ditto` writes UTF-8 names without
     * setting the flag that says so, and a reader that trusts the flag hands the picker
     * `BryceÔÇó`. Java's ZipInputStream defaults exactly that way.
     */
    @Test
    fun recoversAUtf8NameWhoseFlagBitWasNeverSet() {
        val name = "Green Spiky Land (KPT Bryce™)"
        val data = mdatAtom(64)
        val zip = macZip(mapOf(
            "stuff/$name" to data,
            "__MACOSX/stuff/._$name" to appleDouble(resourceFork("moov", moovAtom())),
        ))
        val (got, lost) = collect(zip)
        assertTrue(lost.toString(), lost.isEmpty())
        assertEquals("$name.mov", got.single().name)
    }

    /** Sidecars pair by name, so a member must not pick up its neighbour's fork. */
    @Test
    fun pairsEachSidecarWithItsOwnMember() {
        val aMoov = moovAtom("aaaaaaaa".toByteArray())
        val bMoov = moovAtom("bbbbbbbb".toByteArray())
        val zip = macZip(mapOf(
            "s/Alpha" to mdatAtom(32),
            "s/Beta" to mdatAtom(48),
            "__MACOSX/s/._Alpha" to appleDouble(resourceFork("moov", aMoov)),
            "__MACOSX/s/._Beta" to appleDouble(resourceFork("moov", bMoov)),
        ))
        val (got, _) = collect(zip)
        val byName = got.associateBy { it.name }
        assertArrayEquals(mdatAtom(32) + aMoov, byName.getValue("Alpha.mov").bytes)
        assertArrayEquals(mdatAtom(48) + bMoov, byName.getValue("Beta.mov").bytes)
    }

    @Test
    fun ignoresDotfilesAndDirectories() {
        val zip = macZip(mapOf(
            "s/.DS_Store" to ByteArray(16),
            "s/Real.mov" to (mdatAtom(32) + moovAtom()),
        ))
        val (got, lost) = collect(zip)
        assertTrue(lost.isEmpty())
        assertEquals(listOf("Real.mov"), got.map { it.name })
    }

    // ---- against the real thing -------------------------------------------

    /**
     * The claim the feature rests on: what this produces is byte-identical to what
     * `reference/flatten.py` produces from the same file's real resource fork.
     *
     * Skips without the fixture, like every other test here that needs real media.
     */
    @Test
    fun matchesTheReferenceImplementationOnARealMacArchive() {
        val archive = File("../../reference/testdata/mac-archive.zip")
        assumeTrue("fixture missing: ${archive.path}", archive.isFile)

        val (got, lost) = collect(archive)
        assertTrue("nothing should be unrecoverable: $lost", lost.isEmpty())

        val byName = got.associateBy { it.name }
        assertEquals(3, byName.size)

        // Produced by: reference/applezip.py -o out reference/testdata/mac-archive.zip
        val expected = mapOf(
            "Green Spiky Land (KPT Bryce™).mov" to
                "13af03ded4bea8e72e74bdb4ca1947763e597ee793475edb413ab134e48e00a7",
            "Radio City Music Hall.mov" to
                "65249f830776b8851bad4e862f4d12d3b72a9e1ecd3b2fe15cafc15dbe283b31",
        )
        for ((name, want) in expected) {
            val m = byName[name] ?: error("missing $name, have ${byName.keys}")
            assertTrue("$name should have been rescued", m.rescued)
            assertEquals("$name reconstructed differently", want, sha256(m.bytes))
        }
        // The third carries its own moov and must come through untouched.
        assertFalse(byName.getValue("Monument Valley.mov").rescued)
    }
}
