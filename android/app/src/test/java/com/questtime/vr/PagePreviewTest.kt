package com.questtime.vr

import org.junit.Test
import java.io.File

/**
 * Write the upload page to a file so it can be opened in a desktop browser.
 *
 * Not an assertion - a development tool that happens to live in the test source set,
 * because that is the only place with the app's classes on a JVM classpath. The page
 * is the one part of this project with real layout in it, and iterating on it by
 * building an APK, installing, and putting a headset on is absurd when the markup
 * touches no Android API at all.
 *
 *     ./build.sh testDebugUnitTest --tests '*PagePreviewTest*'
 *     open android/app/build/preview/upload-page.html
 */
class PagePreviewTest {

    @Test
    fun writeThePageForABrowser() {
        val server = UploadServer(
            targetDir = File("/tmp"),
            musicDir = File("/tmp"),
            cacheDir = File("/tmp"),
            onMusicChanged = {},
            hasBundledTrack = { false },
            library = { emptyList() },
        )
        val out = File("build/preview/upload-page.html")
        out.parentFile?.mkdirs()
        out.writeText(server.page())
        println("page preview: ${out.absolutePath}")
    }
}
