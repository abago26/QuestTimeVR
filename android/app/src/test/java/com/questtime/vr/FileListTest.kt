package com.questtime.vr

import org.junit.Assert.assertEquals
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
}
