package com.questtime.vr

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.BitmapFactory
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * Turning what the search directories hold into what the picker shows.
 *
 * Kept out of the Activity, and free of Android APIs, so it can be tested on the JVM
 * like the rest of the decode path.
 */
internal object FileList {

    /**
     * One entry per distinct file, sorted by name.
     *
     * The search directories overlap in practice - a file pushed to the app's own
     * folder is often also sitting in Download - and the same file then appeared
     * twice in the picker. Path is the wrong key for that, since two paths are what
     * "the same file in two places" means. Name and size together identify it.
     *
     * Order is preserved for the survivor, so callers should pass directories with
     * the most reliable location first; that is the copy the picker will open.
     */
    /**
     * QuickTime VR files, plus plain images holding an already-assembled panorama.
     * The upscaled library is the latter: reference/upscale.py cannot write a .mov,
     * and does not need to - a uniform scale preserves the aspect ratio, which is
     * the only thing the vertical geometry depends on.
     */
    fun isPanorama(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".mov") || n.endsWith(".qtvr") || n.endsWith(".qt") || isImage(name)
    }

    /**
     * The same question, for a file that is actually on disk - which is the one worth
     * asking, because most of these files have no extension to read.
     *
     * Classic Mac files carried a type and creator code instead of a suffix, and the
     * habit stuck: of 27 files in a real user's archive, **24 have no extension at
     * all**. Filtering on ".mov" showed three of them and silently hid the rest,
     * which is a poor welcome for someone who has just copied a folder onto a
     * headset and is looking at an almost-empty list.
     *
     * So when the name does not say, the file is asked. Eight bytes is enough.
     */
    fun isPanorama(f: File): Boolean =
        // A '._Name' file is one half of a classic Mac file, not a file to open. It
        // is invisible in Finder and nobody knowingly copied it; it gets paired with
        // its data fork on open (AppleZip.readPaired) rather than listed beside it.
        !f.name.startsWith("._") && (isPanorama(f.name) || looksLikeQuickTime(f))

    /**
     * Whether the first atom is one a QuickTime movie starts with.
     *
     * Classic files begin with 'mdat' (the data fork of a dual-fork movie, its header
     * left behind on the Mac) or 'moov'. Both appear in the archive: 22 and 2.
     *
     * Deliberately *not* 'ftyp'. That is the modern ISO/MP4 signature, which no
     * QuickTime VR file of this era carries, and accepting it would pull every stray
     * .mp4 sitting in Download into a list of panoramas. A modern .mov that does
     * start with 'ftyp' is matched by its extension anyway, so nothing is lost.
     *
     * Reading rather than trusting the name is also why this takes a File: the cost
     * is one open and eight bytes, paid once per candidate while the list is built.
     */
    fun looksLikeQuickTime(f: File): Boolean = runCatching {
        if (f.length() < 16) return false
        val head = ByteArray(8)
        f.inputStream().use { if (it.read(head) != 8) return false }
        val size = ((head[0].toLong() and 0xFF) shl 24) or ((head[1].toLong() and 0xFF) shl 16) or
            ((head[2].toLong() and 0xFF) shl 8) or (head[3].toLong() and 0xFF)
        // A 1 means a 64-bit size follows; 0 means "to end of file". Both are legal.
        if (size in 2..7) return false
        String(head, 4, 4, Charsets.ISO_8859_1) in CLASSIC_ATOMS
    }.getOrDefault(false)

    /** Top-level atoms a classic QuickTime file may open with. */
    private val CLASSIC_ATOMS = setOf("moov", "mdat", "pnot", "wide", "free", "skip")

    fun isImage(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
    }

    /**
     * A cylindrical panorama is at minimum twice as wide as it is tall - a full turn
     * over a limited tilt range cannot be squarer than that. Ordinary photographs
     * sitting in Download are not panoramas and should not be offered as though they
     * were; this is what keeps them out.
     */
    const val MIN_PANORAMA_ASPECT = 2.0

    fun dedupe(files: List<File>): List<File> =
        files
            .distinctBy { it.name.lowercase() to it.length() }
            .sortedBy { it.name.lowercase() }

    /**
     * Sub-folders worth offering: ones that actually contain panoramas, somewhere.
     * An empty folder in Download is noise, not a destination.
     */
    fun panoramaFolders(dirs: List<File>, accept: (File) -> Boolean): List<File> =
        dirs.flatMap { d -> d.listFiles { f: File -> f.isDirectory }?.toList() ?: emptyList() }
            .filter { !it.name.startsWith(".") && countPanoramas(it, accept) > 0 }
            .distinctBy { it.name.lowercase() }
            .sortedBy { it.name.lowercase() }

    fun countPanoramas(dir: File, accept: (File) -> Boolean): Int =
        dir.listFiles { f: File -> accept(f) }?.size ?: 0
}

/**
 * The 2D panel: find QuickTime VR files on the headset and open one.
 *
 * Selection happens flat rather than in VR on purpose - the panel gets a real
 * keyboard, scrolling and system file access, and the immersive activity can then
 * do one thing well.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var note: TextView
    private lateinit var serverNote: TextView

    /**
     * Uploads land in the app's own folder - no storage permission, and the first
     * place the picker looks, so a file appears the moment it finishes sending.
     */
    private val server by lazy {
        UploadServer(
            targetDir = getExternalFilesDir(null) ?: filesDir,
            // Private storage: the music must not turn up in the panorama picker.
            musicDir = filesDir,
            cacheDir = cacheDir,
            onMusicChanged = { Ambience.of(this@MainActivity).reloadTrack() },
            hasBundledTrack = { Ambience.hasBundledTrack(this@MainActivity) },
            library = { libraryForWeb() },
        )
    }

    /**
     * Everything the picker can reach, flattened for the upload page - loose files
     * first, then each folder's contents. Built from the same FileList helpers the
     * picker uses, so the page and the headset cannot drift apart.
     */
    private fun libraryForWeb(): List<LibraryEntry> {
        val loose = FileList.dedupe(searchDirs.flatMap { dir ->
            runCatching { dir.listFiles { f -> accept(f) }?.toList() }.getOrNull() ?: emptyList()
        }).map { LibraryEntry(it.name, it.length(), "") }
        val foldered = FileList.panoramaFolders(searchDirs, ::accept).flatMap { d ->
            FileList.dedupe(
                runCatching { d.listFiles { f -> accept(f) }?.toList() }.getOrNull() ?: emptyList()
            ).map { LibraryEntry(it.name, it.length(), d.name) }
        }
        return loose + foldered
    }

    /**
     * Where a browser should point, for the in-VR list to show.
     *
     * The app opens straight into a panorama now, so this panel may never be looked
     * at - and it was the only place the address appeared.
     */
    val serverUrl: String? get() = runCatching { server.url }.getOrNull()

    /** null is the top level: everything the search directories hold, pooled. */
    private var currentDir: File? = null

    private val searchDirs: List<File>
        get() = listOfNotNull(
            getExternalFilesDir(null),                       // no permission needed
            File(Environment.getExternalStorageDirectory(), "QuestTimeVR"),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        live = this

        // Deliberately not a file browser any more. Choosing happens in the headset,
        // on the panel that floats over the panorama, because that is where the
        // person is. What is left is a card saying the app is running and where to
        // send files - the one thing that genuinely cannot be done from inside VR.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(PAD, PAD, PAD, PAD)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 30f
        })
        note = TextView(this).apply {
            textSize = 15f
            setPadding(0, 16, 0, 8)
            text = getString(R.string.panel_hint)
        }
        root.addView(note)

        serverNote = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, 20)
        }
        root.addView(serverNote)

        root.addView(Button(this).apply {
            text = getString(R.string.grant_storage)
            visibility = if (hasAllFiles()) View.GONE else View.VISIBLE
            setOnClickListener { requestAllFiles() }
        })

        setContentView(root)
        server.start()
        showServerAddress()
        refresh()
        openSomethingToLookAt()
    }

    /**
     * Open a panorama at random, once, on the way in.
     *
     * The panel is a file browser, and being dropped into a file browser is a poor
     * first second of a thing whose whole point is standing somewhere. Random rather
     * than first-alphabetically so a library gets shown off rather than the same
     * picture every time.
     *
     * Once per process, and gated on that alone. `savedInstanceState == null` looks
     * like the natural test for "is this a fresh start" and is not: killing the
     * process leaves the *task* behind, so Android hands back a restored bundle on
     * the next launch and the condition is quietly false forever after. The static
     * flag resets with the process, which is exactly the lifetime wanted.
     */
    private fun openSomethingToLookAt() {
        if (autoOpened) return
        autoOpened = true
        val candidates = FileList.dedupe(searchDirs.flatMap { dir ->
            runCatching { dir.listFiles { f -> accept(f) }?.toList() }.getOrNull() ?: emptyList()
        })
        val pick = candidates.randomOrNull()
        if (pick == null) {
            // An empty headset used to mean staying on the flat panel, which is the
            // one case where that was worst: a first-time user needs the web address
            // more than anyone, and got a 2D screen instead of the app. The welcome
            // panorama is generated rather than bundled - see [Welcome] - so there
            // is always somewhere to stand.
            Log.i(VrActivity.TAG, "nothing on the headset yet - opening the welcome panorama")
            startActivity(
                Intent(this, VrActivity::class.java)
                    .putExtra(VrActivity.EXTRA_WELCOME, true)
                    .putExtra(VrActivity.EXTRA_SHOW_PICKER, true)
            )
            return
        }
        Log.i(VrActivity.TAG, "opening ${pick.name} at random, of ${candidates.size}")
        startActivity(
            Intent(this, VrActivity::class.java)
                .putExtra(VrActivity.EXTRA_PATH, pick.absolutePath)
                // Arrive with the list already up. The panel used to be where you
                // chose, and it is gone; opening into a panorama with no way to see
                // what else is there would be worse than the browser it replaced.
                .putExtra(VrActivity.EXTRA_SHOW_PICKER, true)
        )
    }

    override fun onResume() {
        super.onResume()
        showing = true
        // The picker is silent by definition. This is the authoritative signal:
        // VrActivity stays alive behind the panel and never sees onPause, so it
        // cannot work this out for itself.
        Ambience.of(this).pause()
        showServerAddress()
        refresh()
    }

    private fun showServerAddress() {
        val url = server.url
        val text =
            if (url != null) getString(R.string.server_ready, url)
            else getString(R.string.server_offline)
        Log.i(VrActivity.TAG, "picker: url=$url text.len=${text.length} '${text.take(50)}'")
        serverNote.text = text
        serverNote.visibility = View.VISIBLE
    }

    private fun hasAllFiles(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun requestAllFiles() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
        }.onFailure {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    /**
     * Whether to offer this file. Movies are trusted to the decoder, which reports
     * properly if they turn out not to be panoramas. Images are checked by shape
     * first - the header alone, no full decode - so an ordinary photo in Download
     * does not appear as something to stand inside.
     */
    private fun accept(f: File): Boolean {
        if (!f.isFile || !FileList.isPanorama(f)) return false
        if (!FileList.isImage(f.name)) return true
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(f.absolutePath, opts) }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return false
        return opts.outWidth.toDouble() / opts.outHeight >= FileList.MIN_PANORAMA_ASPECT
    }

    /**
     * Kept as a no-op hook rather than deleted.
     *
     * The list it used to fill is gone; the picker in the headset reads the same
     * directories for itself, and the upload page reads them through
     * [libraryForWeb]. Callers still exist in onResume and this keeps them honest
     * about there being nothing left to refresh here.
     */
    private fun refresh() = Unit

    private fun rowButton(label: String, onTap: () -> Unit): Button =
        Button(this).apply {
            text = label
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { onTap() }
        }

    override fun onPause() {
        showing = false
        super.onPause()
    }

    override fun onDestroy() {
        server.stop()
        if (live === this) live = null
        // Closing the panel is the other way people mean "quit". Without this the
        // immersive activity carries on in its own task with nothing to return to.
        if (isFinishing) Quit.everything(this)
        super.onDestroy()
    }

    /** Back steps up a folder before it leaves the app. */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (currentDir != null) {
            currentDir = null
            refresh()
            return
        }
        super.onBackPressed()
    }

    companion object {
        /** The running instance, so a quit from either side can reach it. */
        @Volatile
        @JvmStatic
        var live: MainActivity? = null

        /**
         * True while the panel is the thing being looked at.
         *
         * This is what separates "quit the app" from "step back to the picker". If
         * the viewer is destroyed while the panel is up, the user went back on
         * purpose and expects the panel to still be there; if it is destroyed while
         * the panel is not showing, the app was closed.
         */
        @Volatile
        @JvmStatic
        var showing: Boolean = false

        /**
         * Whether this process has already jumped into a panorama.
         *
         * Process-wide rather than per-instance, because the panel is not destroyed
         * when you step back to it - only paused - so an instance field would never
         * see a second launch anyway, and a fresh process is exactly when this should
         * fire again.
         */
        @Volatile
        @JvmStatic
        var autoOpened: Boolean = false

        private const val PAD = 48
    }
}
