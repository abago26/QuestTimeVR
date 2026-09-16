package com.questtime.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileListTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun make(dir: File, name: String, size: Int): File {
        dir.mkdirs()
        return File(dir, name).apply { writeBytes(ByteArray(size)) }
    }

    /** The reported bug: one file present in two search directories, listed twice. */
    @Test
    fun sameFileInTwoPlacesAppearsOnce() {
        val appDir = tmp.newFolder("files")
        val downloads = tmp.newFolder("Download")
        val a = make(appDir, "Monument Valley.mov", 1024)
        val b = make(downloads, "Monument Valley.mov", 1024)

        val out = FileList.dedupe(listOf(a, b))
        assertEquals(1, out.size)
        // the first-listed directory wins, so the picker opens the app-private copy
        assertEquals(a.absolutePath, out[0].absolutePath)
    }

    @Test
    fun differentSizesAreDifferentFiles() {
        val dirA = tmp.newFolder("a")
        val dirB = tmp.newFolder("b")
        val out = FileList.dedupe(
            listOf(make(dirA, "pano.mov", 1024), make(dirB, "pano.mov", 2048))
        )
        assertEquals(2, out.size)
    }

    @Test
    fun caseDiffersButFileIsTheSame() {
        val dirA = tmp.newFolder("a")
        val dirB = tmp.newFolder("b")
        val out = FileList.dedupe(
            listOf(make(dirA, "Street.mov", 512), make(dirB, "STREET.MOV", 512))
        )
        assertEquals(1, out.size)
    }

    @Test
    fun sortedByNameIgnoringCase() {
        val d = tmp.newFolder("d")
        val out = FileList.dedupe(
            listOf(
                make(d, "zebra.mov", 1),
                make(d, "Apple.mov", 2),
                make(d, "monument.mov", 3),
            )
        )
        assertEquals(listOf("Apple.mov", "monument.mov", "zebra.mov"), out.map { it.name })
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals(0, FileList.dedupe(emptyList()).size)
    }

    // ---- files with no extension -------------------------------------------
    //
    // Classic Mac files carried a type and creator code rather than a suffix, so
    // most of them have no extension at all. Filtering on ".mov" hid them from the
    // picker entirely, which is the difference between copying a folder onto a
    // headset and seeing it, and seeing three files out of twenty-seven.

    private val imports = java.io.File("../../Imports")

    @Test
    fun aClassicFileWithNoExtensionIsOffered() {
        assumeTrue("Imports missing", imports.isDirectory)
        // Dotfiles are not part of the archive. Finder drops a .DS_Store into any
        // folder it touches, and asserting that *every* file is a panorama made this
        // fail the first time one appeared - which says nothing about the picker,
        // which rejects it correctly.
        val all = imports.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") }
            .orEmpty()
        assumeTrue("Imports is empty", all.isNotEmpty())

        val byName = all.count { FileList.isPanorama(it.name) }
        val byContent = all.count { FileList.isPanorama(it) }
        assertTrue("the name test should still miss most of them, got $byName",
            byName < all.size / 2)
        assertEquals("every file in the archive should be offered", all.size, byContent)
    }

    /** An ordinary MP4 must not be pulled in - it starts with 'ftyp', not 'moov'. */
    @Test
    fun amodernMp4IsNotMistakenForAClassicMovie() {
        val f = java.io.File.createTempFile("not-a-panorama", "")
        try {
            f.writeBytes(
                byteArrayOf(0, 0, 0, 0x18) + "ftypmp42".toByteArray(Charsets.ISO_8859_1) +
                    ByteArray(16)
            )
            assertFalse("an .mp4 in Download is not a panorama", FileList.isPanorama(f))
        } finally {
            f.delete()
        }
    }

    /** And neither is a text file that happens to be sitting there. */
    @Test
    fun aFileThatIsNotAMovieAtAllIsRejected() {
        val f = java.io.File.createTempFile("notes", "")
        try {
            f.writeBytes("Some notes about panoramas, but not a panorama.".toByteArray())
            assertFalse(FileList.isPanorama(f))
        } finally {
            f.delete()
        }
    }

    /** Too short to hold an atom header, and must not throw reading it. */
    @Test
    fun aTinyFileIsRejectedRatherThanCrashing() {
        val f = java.io.File.createTempFile("tiny", "")
        try {
            f.writeBytes(byteArrayOf(1, 2, 3))
            assertFalse(FileList.isPanorama(f))
        } finally {
            f.delete()
        }
    }
}
