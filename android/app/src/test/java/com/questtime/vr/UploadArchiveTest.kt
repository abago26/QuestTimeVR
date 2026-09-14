package com.questtime.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Sending a Mac archive to the upload server, the way a browser does.
 *
 * `AppleZipTest` proves the recovery; this proves the wiring - that a `.zip` arriving
 * on `/upload` is expanded, that every member is put through the same [Qtvr.inspect]
 * gate a loose upload gets, and that what lands in the target directory is what the
 * picker can actually open.
 *
 * The server is driven over a real socket rather than by calling its internals, so
 * the multipart parse and the JSON the page reads are both covered.
 */
class UploadArchiveTest {

    private fun server(target: File, cache: File) = UploadServer(
        targetDir = target,
        musicDir = File(cache, "music").apply { mkdirs() },
        cacheDir = cache,
        onMusicChanged = {},
        hasBundledTrack = { false },
        library = { emptyList() },
    )

    /** A multipart body of exactly the shape a browser's file input sends. */
    private fun multipartBody(filename: String, bytes: ByteArray, boundary: String): ByteArray {
        val head = ("--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n").toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        return ByteArrayOutputStream().apply { write(head); write(bytes); write(tail) }
            .toByteArray()
    }

    private fun post(path: String, filename: String, bytes: ByteArray): String {
        val boundary = "----questtime${System.nanoTime()}"
        val body = multipartBody(filename, bytes, boundary)
        java.net.Socket("127.0.0.1", UploadServer.PORT).use { s ->
            s.getOutputStream().apply {
                write(("POST $path HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Content-Type: multipart/form-data; boundary=$boundary\r\n" +
                    "Content-Length: ${body.size}\r\n\r\n").toByteArray())
                write(body)
                flush()
            }
            val all = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            return all.substringAfter("\r\n\r\n")
        }
    }

    /** Every `"key":value` for one key, in document order. Enough for flat objects. */
    private fun values(json: String, key: String): List<String> =
        Regex("\"$key\":(\"(?:[^\"\\\\]|\\\\.)*\"|true|false|-?\\d+)")
            .findAll(json).map { it.groupValues[1].trim('"') }.toList()

    @Test
    fun expandsAMacArchiveAndKeepsWhatItCanOpen() {
        val archive = File("../../reference/testdata/mac-archive.zip")
        assumeTrue("fixture missing: ${archive.path}", archive.isFile)

        val tmp = File(System.getProperty("java.io.tmpdir"), "qtvr-upload-${System.nanoTime()}")
        val target = File(tmp, "files").apply { mkdirs() }
        val cache = File(tmp, "cache").apply { mkdirs() }
        val s = server(target, cache)
        assumeTrue("port ${UploadServer.PORT} already in use", s.start())
        try {
            val json = post("/upload", "Imports.zip", archive.readBytes())

            val names = values(json, "name")
            assertEquals("one verdict per member: $json", 3, names.size)

            // The two whose moov only exists in a sidecar must come back rescued,
            // and must be openable once it has been put back.
            val rescued = values(json, "rescued")
            val ok = values(json, "ok")
            val saved = values(json, "saved")
            assertEquals("2 rescued, 1 already intact: $json",
                2, rescued.count { it == "true" })
            assertEquals("all three should open: $json", 3, ok.count { it == "true" })
            assertEquals("all three should be kept: $json", 3, saved.count { it == "true" })

            // Every member says which archive it came from, so the page can group them.
            assertTrue("members should carry provenance: $json",
                values(json, "from").all { it == "Imports.zip" })

            // What actually landed is what the picker will offer.
            val onDisk = target.listFiles()!!.map { it.name }.sorted()
            assertEquals(listOf(
                "Green Spiky Land (KPT Bryce™).mov",
                "Monument Valley.mov",
                "Radio City Music Hall.mov",
            ), onDisk)
            assertTrue("kept files must open", onDisk.all {
                Qtvr.inspect(File(target, it).readBytes()).opens
            })

            // The staged archive must not be left behind, and must never have been
            // written anywhere the picker looks.
            assertEquals("cache not cleaned: ${cache.list()?.toList()}",
                0, cache.listFiles()!!.count { it.name.endsWith(".zip") })
            assertFalse("a .zip must never reach the picker's folder",
                target.listFiles()!!.any { it.name.endsWith(".zip") })
        } finally {
            s.stop()
            tmp.deleteRecursively()
        }
    }

    /**
     * The sanitiser must not undo the recovery. It guards against escaping the
     * directory, not against punctuation - a name is the only label a panorama has.
     */
    @Test
    fun keepsTheSendersNameWhileRefusingToEscapeTheDirectory() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "qtvr-safe-${System.nanoTime()}")
        val target = File(tmp, "files").apply { mkdirs() }
        val cache = File(tmp, "cache").apply { mkdirs() }
        val s = server(target, cache)
        assumeTrue("port ${UploadServer.PORT} already in use", s.start())
        try {
            val whole = Qtvr.inspect(ByteArray(0))   // any refusal will do; we want the name
            assertFalse(whole.opens)

            for (sent in listOf(
                "Green Spiky Land (KPT Bryce\u2122)",
                "../../../etc/passwd",
                "..\\..\\windows\\system32",
            )) {
                val json = post("/upload", sent, "not a movie".toByteArray())
                val name = values(json, "name").single()
                assertFalse("must not keep a path separator: $name", name.contains('/'))
                assertFalse("must not keep a path separator: $name", name.contains('\\'))
                assertFalse("must not start with a dot: $name", name.startsWith("."))
            }
            // The one that matters: the trademark sign survives.
            val json = post("/upload", "Green Spiky Land (KPT Bryce\u2122)", "x".toByteArray())
            assertEquals("Green Spiky Land (KPT Bryce\u2122)", values(json, "name").single())
        } finally {
            s.stop()
            tmp.deleteRecursively()
        }
    }

    @Test
    fun saysSoWhenTheArchiveIsNotOne() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "qtvr-upload-${System.nanoTime()}")
        val target = File(tmp, "files").apply { mkdirs() }
        val cache = File(tmp, "cache").apply { mkdirs() }
        val s = server(target, cache)
        assumeTrue("port ${UploadServer.PORT} already in use", s.start())
        try {
            val json = post("/upload", "not-really.zip", "PK not a zip at all".toByteArray())
            assertTrue("should refuse, not crash: $json", json.contains("\"ok\":false"))
            assertEquals(0, target.listFiles()!!.size)
        } finally {
            s.stop()
            tmp.deleteRecursively()
        }
    }
}
