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

    /** Sample answers for /files and /music, so the preview has something to lay out. */
    private val STUB = """
<script>
const FILES = {count:9, files:[
  {name:"Radio City Music Hall.mov", size:819008, folder:""},
  {name:"Green Spiky Land (KPT Bryce\u2122).mov", size:537905, folder:""},
  {name:"Hwy 1 near Stinson Beach, CA.mov", size:511220, folder:""},
  {name:"Monument Valley.mov", size:640165, folder:""},
  {name:"Champs Elysee at Night.mov", size:628400, folder:""},
  {name:"Eiffel Tower at Night.mov", size:631002, folder:""},
  {name:"Salk Institute - San Diego.mov", size:104880, folder:""},
  {name:"White House - South Portico.mov", size:498112, folder:"Imports"},
  {name:"NASA Ames Wind Tunnel.mov", size:463220, folder:"Imports"}]};
const MUSIC = {state:"uploaded", name:"long ambient mix.mp3", minutes:42, note:""};
window.fetch = (u) => Promise.resolve({
  json: () => Promise.resolve(String(u).indexOf('/files') >= 0 ? FILES : MUSIC)});
// The page calls these on load, before this stub exists, so they have already run
// against a fetch that fails on file://. Re-run them now that fetch is stubbed.
showLibrary(); showTrack();
document.querySelectorAll('details').forEach(d => d.open = true);
</script>
"""


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

        // A second copy with the two endpoints stubbed. The page fetches /files and
        // /music on load, so opened as a bare file it shows empty lists and both
        // collapsed sections read the same - which is exactly the state in which a
        // condensed list cannot be judged. Production code stays untouched; the stub
        // is appended here.
        val withData = File("build/preview/upload-page-with-data.html")
        withData.writeText(server.page() + STUB)
        println("page preview (populated): ${withData.absolutePath}")
    }
}
