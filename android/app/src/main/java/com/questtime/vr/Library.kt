package com.questtime.vr

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
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
 * Where panoramas are looked for, and which files count - one definition for the
 * in-VR list, the random pick on launch, and the upload page.
 *
 * It used to live in the 2D panel, with a second, slightly different copy in the
 * viewer: the viewer skipped Movies and never checked an image's shape, so a photo in
 * Download could be offered in the headset while the page said it was not there.
 * With the panel gone there is one copy, and the three callers cannot disagree.
 */
object Library {

    fun searchDirs(context: Context): List<File> = listOfNotNull(
        context.getExternalFilesDir(null),                       // no permission needed
        File(Environment.getExternalStorageDirectory(), "QuestTimeVR"),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
    )

    /**
     * Whether to offer this file. Movies are trusted to the decoder, which reports
     * properly if they turn out not to be panoramas. Images are checked by shape
     * first - the header alone, no full decode - so an ordinary photo in Download
     * does not appear as something to stand inside.
     */
    fun accept(f: File): Boolean {
        if (!f.isFile || !FileList.isPanorama(f)) return false
        if (!FileList.isImage(f.name)) return true
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(f.absolutePath, opts) }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return false
        return opts.outWidth.toDouble() / opts.outHeight >= FileList.MIN_PANORAMA_ASPECT
    }

    /**
     * Everything at the top level of the search directories, one entry per file.
     *
     * Read on every call rather than held: files arrive from the browser while the
     * app is running, so a cached list goes stale exactly when someone has just sent
     * something and wants to look at it.
     */
    fun panoramas(context: Context): List<File> =
        FileList.dedupe(searchDirs(context).flatMap { dir ->
            runCatching { dir.listFiles { f -> accept(f) }?.toList() }.getOrNull() ?: emptyList()
        })

    /**
     * Everything reachable, flattened for the upload page - loose files first, then
     * each folder's contents.
     */
    fun forWeb(context: Context): List<LibraryEntry> {
        val dirs = searchDirs(context)
        val loose = panoramas(context).map { LibraryEntry(it.name, it.length(), "") }
        val foldered = FileList.panoramaFolders(dirs, ::accept).flatMap { d ->
            FileList.dedupe(
                runCatching { d.listFiles { f -> accept(f) }?.toList() }.getOrNull() ?: emptyList()
            ).map { LibraryEntry(it.name, it.length(), d.name) }
        }
        return loose + foldered
    }

    /** All-files access, without which only the app's own folder is visible. */
    fun hasAllFiles(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
}

/**
 * The upload server, process-wide.
 *
 * It belonged to the 2D panel, which meant the address existed only while that panel
 * did - and the in-VR list and the welcome panorama had to reach into another
 * Activity to find it. Owned by the process instead, it starts with the viewer and
 * there is exactly one of it however the app was entered.
 */
object Server {
    @Volatile
    private var instance: UploadServer? = null

    fun of(context: Context): UploadServer =
        instance ?: synchronized(this) {
            instance ?: context.applicationContext.let { app ->
                UploadServer(
                    targetDir = app.getExternalFilesDir(null) ?: app.filesDir,
                    // Private storage: the music must not turn up in the panorama list.
                    musicDir = app.filesDir,
                    cacheDir = app.cacheDir,
                    onMusicChanged = { Ambience.of(app).reloadTrack() },
                    hasBundledTrack = { Ambience.hasBundledTrack(app) },
                    library = { Library.forWeb(app) },
                )
            }.also { instance = it }
        }

    /** Null when there is no Wi-Fi, or the server has not started. */
    fun url(context: Context): String? = runCatching { of(context).url }.getOrNull()
}
