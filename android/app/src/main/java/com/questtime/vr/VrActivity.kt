package com.questtime.vr

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Decodes the chosen file and hands the panorama to the OpenXR compositor.
 *
 * This is a normal Activity rather than a NativeActivity: OpenXR on Android only
 * needs the JavaVM and an Activity reference, which XR_KHR_android_create_instance
 * takes from JNI, so there is no reason to give up Kotlin for the app shell.
 */
class VrActivity : Activity() {

    /** False if the session could not be started; ask [nativeLastError] why. */
    private external fun nativeStart(
        pixels: ByteBuffer, width: Int, height: Int,
        centralAngle: Float, aspectRatio: Float,
    ): Boolean

    /** Cubic: six square faces, RGBA, back to back in QuickTime VR's stored order. */
    private external fun nativeStartCube(faces: ByteBuffer, faceSize: Int): Boolean

    private external fun nativeStop()
    private external fun nativeLastError(): String
    private external fun nativeIsRunning(): Boolean

    /** The menu bar's pixels, RGBA. Picked up the first time the bar is shown. */
    private external fun nativeSetMenu(pixels: ByteBuffer, width: Int, height: Int)

    /** Show or hide whatever bitmap was last handed over. */
    private external fun nativeShowMenu(show: Boolean)

    /** While true the thumbstick scrolls the list instead of turning the view. */
    private external fun nativeSetPicking(picking: Boolean)
    private external fun nativeSetGazeHot(hot: Boolean)
    private external fun nativeSetGazeLabel(pixels: ByteBuffer, width: Int, height: Int)

    // -- the in-headset picker ---------------------------------------------

    /**
     * What is on the headset, as the picker lists it.
     *
     * Read once per open rather than held: files arrive from the browser while the
     * app is running, so a list cached at startup goes stale exactly when someone has
     * just sent something and wants to look at it.
     */
    private fun panoramas(): List<File> {
        val dirs = listOfNotNull(
            getExternalFilesDir(null),
            File(Environment.getExternalStorageDirectory(), "QuestTimeVR"),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        )
        return FileList.dedupe(dirs.flatMap { d ->
            runCatching { d.listFiles { f -> f.isFile && FileList.isPanorama(f) }?.toList() }
                .getOrNull() ?: emptyList()
        })
    }

    private var files: List<File> = emptyList()
    private var selected = 0
    private var picking = false

    /**
     * The scene whose nodes the list is showing, or null while it is showing files.
     *
     * The picker is two levels deep for a scene and one for everything else:
     * choosing a multi-node file opens its nodes rather than the file, because
     * "open Lincoln Memorial" does not name a place to stand. A single-node file
     * never has a second level, so nothing changes for the files that always worked.
     */
    private var sceneFile: File? = null
    private var sceneNodes: List<VrNode> = emptyList()

    /** Whichever list is up: the files, or one scene's nodes. */
    private fun listNames(): List<String> =
        if (sceneFile != null) sceneNodes.map { it.label() } else files.map { it.nameWithoutExtension }

    /**
     * A button press from the render thread.
     *
     * Native reports that something was pressed and nothing more; what it means lives
     * here, next to the file list and the text stack. Called from the OpenXR thread,
     * so everything it touches is hopped to the main thread first.
     */
    @Suppress("unused")   // called from vr_renderer.cpp by name
    fun onVrInput(code: Int) {
        Handler(Looper.getMainLooper()).post {
            Log.i(TAG, "input $code (picking=$picking showingInfo=$showingInfo)")
            when (code) {
                // A and X open and close, and nothing else - the left menu button
                // cannot do it because Horizon OS keeps that one for its own menu
                // (input 0 never arrived once across a session of testing, while 1
                // and 2 arrived every time). It stays bound in case that changes.
                // Inside a scene the same button steps back to the files, because
                // the node list is somewhere you went rather than something you
                // opened - closing outright would lose the place you were choosing.
                INPUT_MENU, INPUT_SELECT -> when {
                    picking && sceneFile != null -> showFiles()
                    picking || showingInfo -> closePanels()
                    else -> openPicker()
                }
                // The trigger commits. Separate from the button that opens, so a
                // single press cannot both summon the list and choose whatever
                // happened to be highlighted when it appeared.
                // The trigger commits in the list, and walks through a doorway when
                // the list is not up. One button, two meanings, but never both at
                // once - the list being up is what tells them apart.
                INPUT_CONFIRM -> if (picking) confirmPick() else travelThroughGaze()
                INPUT_INFO -> if (showingInfo) closePanels() else showInfo()
                INPUT_UP -> move(-1)
                INPUT_DOWN -> move(1)
            }
        }
    }

    private var showingInfo = false

    /**
     * The hand is pointing at a row.
     *
     * Native reports a fraction of the panel's height; [MenuBar.rowAt] turns that
     * into a row, because the layout is its business. Moving the highlight is the
     * whole of the feedback - there is no cursor drawn, since that would mean
     * re-uploading the panel every frame to move a dot.
     */
    @Suppress("unused")   // called from vr_renderer.cpp by name
    fun onVrHover(vThousandths: Int) {
        Handler(Looper.getMainLooper()).post {
            if (!picking) return@post
            val firstVisible = (selected / MenuBar.PAGE) * MenuBar.PAGE
            val row = MenuBar.rowAt(vThousandths, listNames().size, firstVisible)
            if (row < 0 || row == selected) return@post
            selected = row
            drawPicker()
        }
    }

    // ---- hot spots ---------------------------------------------------------

    /** The open node's mask and hot spots, or null when it has none. */
    private var mask: HotspotMask? = null

    /** The scene's node table, kept so a hot spot can be resolved to a node. */
    private var openNodes: List<VrNode> = emptyList()

    /** The hot-spot id under the gaze, 0 for none. */
    private var gazeId = 0

    /** Geometry of the panorama on screen, for turning a gaze into a texel. */
    private var gazeCentral = 0f
    private var gazeAspect = 0f

    /**
     * Where the decoded band sits in the displayed image, as fractions.
     *
     * Not the same thing: the gradient caps make the picture taller than the file's
     * own rows, and the mask only covers those rows. See [Hotspots.intoBand].
     */
    private var gazeBandTop = 0f
    private var gazeBandSpan = 1f

    /** Rows the panorama had before the caps were added, recorded by [load]. */
    private var bandRows = 0

    /**
     * The viewer is looking somewhere.
     *
     * Native reports a direction in the panorama's own frame - it has already taken
     * the snap turn out - and the mapping to a texel lives in [Hotspots] where it can
     * be tested without a headset. Nothing is drawn here: the answer goes back to the
     * renderer as a single bit, and the reticle appears when it is set.
     */
    @Suppress("unused")   // called from vr_renderer.cpp by name
    fun onVrGaze(yawMilli: Int, pitchMilli: Int) {
        val m = mask ?: return
        if (gazeCentral <= 0f || gazeAspect <= 0f) return
        val (u, v) = Hotspots.texel(yawMilli / 1000f, pitchMilli / 1000f, gazeCentral, gazeAspect)
        val id = Hotspots.idAt(m, u, Hotspots.intoBand(v, gazeBandTop, gazeBandSpan))
        if (id == gazeId) return
        gazeId = id
        // A hot spot that leads nowhere should not light up as though it did.
        val live = id != 0 && openNodes.isNotEmpty() &&
            Hotspots.destination(openNodes, openNodes[currentNode()], id) != null
        if (live) {
            // The label first, then the bit that makes it visible: native only shows
            // the card alongside the reticle, and drawing after switching on would
            // put last doorway's name under this one's dot for a frame or two.
            val name = Hotspots.label(openNodes, openNodes[currentNode()], id)
                ?: getString(R.string.gaze_unnamed)
            Log.i(TAG, "gaze on hot spot $id: $name")
            runCatching {
                val (px, w, h) = MenuBar.buildLabel(name, getString(R.string.gaze_action))
                nativeSetGazeLabel(px, w, h)
            }.onFailure { Log.w(TAG, "gaze label could not be drawn", it) }
        }
        nativeSetGazeHot(live)
    }

    private fun currentNode() =
        intent.getIntExtra(EXTRA_NODE, 0).coerceIn(0, maxOf(0, openNodes.size - 1))

    /**
     * Walk through whatever is under the gaze, if anything is.
     *
     * Returns whether it did, so the trigger can fall through to its other meanings
     * when there is no doorway in front of you.
     */
    private fun travelThroughGaze(): Boolean {
        if (gazeId == 0 || openNodes.isEmpty()) return false
        val to = Hotspots.destination(openNodes, openNodes[currentNode()], gazeId) ?: return false
        val path = intent.getStringExtra(EXTRA_PATH) ?: return false
        Log.i(TAG, "walking through hot spot $gazeId to node ${to + 1}")
        gazeId = 0
        nativeSetGazeHot(false)
        open(File(path), to)
        return true
    }

    private fun closePanels() {
        picking = false
        showingInfo = false
        sceneFile = null
        nativeSetPicking(false)
        nativeShowMenu(false)
    }

    private fun openPicker() {
        showingInfo = false
        nativeSetPicking(true)
        // A count from the last time the list was up is stale the moment it closes,
        // and a stale one reads as the result of a press that never happened.
        rescanNote = null
        sceneFile = null
        files = panoramas()
        // Start on the file already open, so the list opens where you are rather
        // than at the top of an alphabet you did not choose.
        val here = intent.getStringExtra(EXTRA_PATH)
        selected = files.indexOfFirst { it.absolutePath == here }.coerceAtLeast(0)
        picking = true
        drawPicker()
    }

    /**
     * Rows are the files plus one more: the music switch.
     *
     * Carried in the same list rather than given its own control, because every
     * button on the controller already does something and a setting nobody can find
     * is the same as no setting.
     */
    /** Whatever is listed, then the band's action rows. */
    private fun rowCount() = listNames().size + MenuBar.ACTION_COUNT
    private val musicRow get() = listNames().size + MenuBar.ACTION_MUSIC
    private val rescanRow get() = listNames().size + MenuBar.ACTION_RESCAN
    private val detailsRow get() = listNames().size + MenuBar.ACTION_DETAILS

    /**
     * What the last rescan found, shown on its own row until the list is closed.
     *
     * Held here rather than recomputed in the drawing, because the point of it is
     * that somebody asked - a count that is simply always there says nothing about
     * whether the button worked.
     */
    private var rescanNote: String? = null

    private fun move(by: Int) {
        if (!picking) return
        selected = (selected + by).coerceIn(0, rowCount() - 1)
        drawPicker()
    }

    private fun confirmPick() {
        // The music row stays on the list: toggling is something you may want to do
        // twice in a row, and closing the panel to do it again would be tedious.
        if (selected == musicRow) {
            ambience.toggleMuted()
            drawPicker()
            return
        }
        /*
         * Files arrive while you are standing in a panorama.
         *
         * Opening the list already re-lists, so this row is not the only way to
         * pick up a new file - closing and reopening does it too. It exists because
         * that is invisible: somebody who has just dropped files into the browser
         * page is looking at a list that does not have them, and nothing on screen
         * suggests the fix is to close the thing they are reading. A row that says
         * what it looked for and what it found answers that without them guessing.
         *
         * It drops back to the files even when a scene's nodes are showing, because
         * a new file is not in the scene you are looking at and leaving you there
         * would be answering a different question.
         */
        if (selected == rescanRow) {
            sceneFile = null
            sceneNodes = emptyList()
            files = panoramas()
            rescanNote = "${files.size} found"
            Log.i(TAG, "rescan: ${files.size} on the headset")
            // Keep the highlight on the row that was just pressed. The list it is
            // measured from has changed length, so the row's index has too.
            selected = rescanRow
            drawPicker()
            return
        }
        // Reachable by pointing, so the description does not depend on knowing that
        // B exists. The row says "B or Y" beside it, which is how anyone finds out.
        if (selected == detailsRow) {
            showInfo()
            return
        }
        // Inside a scene, a row is a place to stand.
        sceneFile?.let { scene ->
            val node = sceneNodes.getOrNull(selected) ?: return
            closePanels()
            open(scene, node.index)
            return
        }

        val f = files.getOrNull(selected) ?: run { closePanels(); return }
        // A scene is not a thing you can open - "Lincoln Memorial" is nine places -
        // so choosing one descends into its nodes instead. Whether it is a scene is
        // not knowable without reading the file, which is why this goes to a worker
        // and why there is no answer to act on yet.
        descend(f)
    }

    /** Same route the panel takes, so there is one way a panorama gets opened. */
    private fun open(file: File, node: Int) {
        // Node and path together: re-opening the same scene at a different node is a
        // real change, and comparing paths alone would dismiss it as "already showing".
        if (file.absolutePath == intent.getStringExtra(EXTRA_PATH) &&
            node == intent.getIntExtra(EXTRA_NODE, 0)
        ) {
            Log.i(TAG, "already showing ${file.name} node $node - not reloading")
            return
        }
        startActivity(
            Intent(this, VrActivity::class.java)
                .putExtra(EXTRA_PATH, file.absolutePath)
                .putExtra(EXTRA_NODE, node)
        )
    }

    /**
     * Open a chosen file, or step into it when it turns out to be a scene.
     *
     * Reading the node table means reading the whole file - eight megabytes for
     * Lincoln Memorial - so it happens on a worker, for the same reason [showInfo]
     * does: doing it here would stall the frame the answer is meant to appear in.
     * One read answers both questions, which is why this is not a cheap "is it a
     * scene" test followed by a second pass to get the names.
     */
    private fun descend(f: File) {
        val gen = ++pickGeneration
        thread(name = "qtvr-nodes") {
            val nodes = runCatching { Qtvr.nodes(AppleZip.readPaired(f)) }.getOrDefault(emptyList())
            Handler(Looper.getMainLooper()).post {
                // Dismissed, or a second row chosen, while the file was being read.
                if (!picking || gen != pickGeneration) return@post
                if (nodes.size > 1) {
                    sceneFile = f
                    sceneNodes = nodes
                    // Land on the node already showing, if this is the open scene.
                    selected = if (f.absolutePath == intent.getStringExtra(EXTRA_PATH))
                        intent.getIntExtra(EXTRA_NODE, 0).coerceIn(0, nodes.size - 1) else 0
                    drawPicker()
                    return@post
                }
                closePanels()
                open(f, 0)
            }
        }
    }

    /**
     * Bumped whenever a row is chosen, so a node table that arrives after the list
     * moved on is dropped rather than opening a scene nobody asked for any more.
     */
    private var pickGeneration = 0

    /** Step back out of a scene to the list of files. */
    private fun showFiles() {
        val was = sceneFile
        sceneFile = null
        sceneNodes = emptyList()
        selected = files.indexOfFirst { it.absolutePath == was?.absolutePath }.coerceAtLeast(0)
        drawPicker()
    }

    /**
     * What the menu bar calls the open panorama.
     *
     * Deliberately does not read the file to find the node's name: this runs on the
     * main thread as the viewer starts, and reading a scene to label a bar is the
     * same stall [showInfo] goes to a worker to avoid. The number is free and says
     * enough to tell two nodes apart.
     */
    private fun barTitle(file: File, node: Int): String =
        file.nameWithoutExtension + if (node > 0) "  \u00b7  node ${node + 1}" else ""

    /**
     * What this file is, as a small card.
     *
     * Read off the disk on a worker rather than here: inspect reads the whole file,
     * and doing that on the main thread for a large panorama stalls the very frame
     * the panel is supposed to appear in.
     */
    private fun showInfo() {
        picking = false
        nativeSetPicking(false)
        showingInfo = true
        val path = intent.getStringExtra(EXTRA_PATH)
        val name = path?.let { File(it).nameWithoutExtension } ?: "No file open"
        thread(name = "qtvr-inspect") {
            val detail = runCatching {
                path?.let { Qtvr.inspect(AppleZip.readPaired(File(it))).summary }
            }.getOrNull().orEmpty().ifEmpty { "Nothing open" }
            Handler(Looper.getMainLooper()).post {
                if (!showingInfo) return@post          // dismissed while reading
                runCatching {
                    val (px, w, h) = MenuBar.buildInfo(name, detail)
                    nativeSetMenu(px, w, h)
                    nativeShowMenu(true)
                }.onFailure { Log.w(TAG, "info card failed", it) }
            }
        }
    }

    private fun drawPicker() {
        runCatching {
            val scene = sceneFile
            val (px, w, h) = MenuBar.buildList(
                listNames(), selected, musicMuted = ambience.muted,
                title = scene?.nameWithoutExtension ?: "Panoramas",
                subtitle = if (scene != null) "${sceneNodes.size} places in this scene"
                    else "${files.size} on the headset",
                inScene = scene != null,
                rescanNote = rescanNote,
                // Read from the panel rather than held here: the server is its
                // object, and the address changes if the network does.
                serverUrl = MainActivity.live?.serverUrl,
            )
            nativeSetMenu(px, w, h)
            nativeShowMenu(true)
        }.onFailure { Log.w(TAG, "picker failed", it) }
    }

    private lateinit var status: TextView

    /** Process-wide; the picker stops it, opening a panorama starts it. */
    private val ambience get() = Ambience.of(this)

    /**
     * Bumped on every open. A decode that finishes after a newer one has started is
     * stale and must not seize the session, and a pending "give up and finish" from
     * a failed open must not close the activity out from under a later success.
     */
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        live = this

        status = TextView(this).apply {
            textSize = 22f
            setPadding(64, 64, 64, 64)
            text = getString(R.string.decoding)
        }
        setContentView(status)
        openFrom(intent)
    }

    override fun onResume() {
        super.onResume()
        ambience.resume()
    }

    /**
     * Covers both leaving for the picker and the system menu taking focus. The music
     * fades rather than cutting, and picks up where it left off on the way back.
     */
    override fun onPause() {
        ambience.pause()
        super.onPause()
    }

    /**
     * The activity is singleTask, so picking a second file does not create a second
     * instance - the intent arrives here instead. Without this the new path was
     * simply dropped and the previous panorama stayed up, which read as the picker
     * being dead.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFrom(intent)
    }

    private fun openFrom(intent: Intent) {
        val welcome = intent.getBooleanExtra(EXTRA_WELCOME, false)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null && !welcome) {
            finishWith("No file was passed to the viewer.")
            return
        }

        val node = intent.getIntExtra(EXTRA_NODE, 0)
        val gen = ++generation
        status.text = getString(R.string.decoding)

        // Redrawn per file, because the name on it is this file's. Sent before the
        // session starts so the bar is ready the first time someone asks for it.
        runCatching {
            val (px, w, h) = MenuBar.build(
                title = if (welcome) getString(R.string.welcome_title)
                        else barTitle(File(path!!), node),
                hint = getString(R.string.menu_hint),
            )
            nativeSetMenu(px, w, h)
        }.onFailure { Log.w(TAG, "menu bar could not be drawn", it) }

        // Opened by the launcher: bring the list up once the panorama is in, so the
        // first thing seen is a place plus a way to choose another.
        if (intent.getBooleanExtra(EXTRA_SHOW_PICKER, false)) {
            intent.removeExtra(EXTRA_SHOW_PICKER)
            Handler(Looper.getMainLooper()).postDelayed({
                if (gen == generation && !picking) openPicker()
            }, 1200)
        }

        // Start the music now rather than when the panorama appears: decoding takes
        // a few seconds, and the fade-in covers exactly that gap.
        ambience.open()

        // Deliberately not stopping the running session here: decoding takes a
        // moment and the old panorama is better company than a black void. Native's
        // claimSession hands over at the instant the new one is ready.
        // Cleared before the decode, not after: the old node's hot spots describe a
        // panorama that is about to be replaced, and acting on one of them while the
        // new one loads walks somewhere from a place you are no longer standing.
        mask = null
        openNodes = emptyList()
        gazeId = 0
        runCatching { nativeSetGazeHot(false) }

        Log.i(TAG, if (welcome) "opening the welcome panorama" else "opening ${File(path!!).name} node $node")
        thread(name = "qtvr-decode") {
            // The mask is another whole track to decode, so it happens here on the
            // worker beside the picture rather than on the frame that shows it.
            // The welcome panorama has no file behind it, and so no hot spots.
            val hot = if (welcome) null else runCatching {
                val bytes = AppleZip.readPaired(File(path!!))
                Qtvr.hotspotMask(bytes, node) to Qtvr.nodes(bytes)
            }.getOrNull()
            // Drawn on the worker like every other decode. It is a few megapixels of
            // Canvas work, which is not free, and the frame it would otherwise land
            // on is the first one the user ever sees.
            val result = runCatching {
                if (welcome) Welcome.panorama(MainActivity.live?.serverUrl).let {
                    bandRows = it.height
                    Caps.addGradient(it, Caps.targetHeight(it))
                } else load(File(path!!), node)
            }
            Handler(Looper.getMainLooper()).post {
                if (gen != generation) return@post          // superseded mid-decode
                mask = hot?.first
                openNodes = hot?.second ?: emptyList()
                mask?.let { m ->
                    Log.i(TAG, "hot spots: ${m.spots.size} on this node")
                    // Redraw the bar now that there is something more to say. It was
                    // drawn before the decode with the hint that fits every file;
                    // this one only makes sense where there is a way on, and whether
                    // there is could not be known without reading the file.
                    runCatching {
                        val (px, w, h) = MenuBar.build(
                            title = barTitle(File(path!!), node),
                            hint = getString(R.string.menu_hint_hotspots),
                        )
                        nativeSetMenu(px, w, h)
                    }.onFailure { Log.w(TAG, "menu bar could not be redrawn", it) }
                }
                result.onSuccess { scene ->
                    when (scene) {
                        is Panorama -> start(scene)
                        is CubeScene -> startCube(scene)
                        else -> failWith("Unrecognised panorama.")
                    }
                }.onFailure { e ->
                    Log.e(TAG, "decode failed", e)
                    failWith(e.message ?: "Could not read that file.")
                }
            }
        }
    }

    /** A session can fail after nativeStart returns, on the render thread. */
    private fun pollForLateFailure() {
        val gen = generation
        Handler(Looper.getMainLooper()).postDelayed({
            if (gen != generation) return@postDelayed
            val err = nativeLastError()
            if (err.isNotEmpty() && !nativeIsRunning()) finishWith(err)
        }, 2500)
    }

    /** Stop the old panorama before reporting, so the message is not read over it. */
    private fun failWith(message: String) {
        nativeStop()
        ambience.pause()
        finishWith(message)
    }

    private fun load(file: File, node: Int): Any {
        // Paired rather than read raw: a classic file copied loose from a Mac keeps
        // its header in a '._' sidecar beside it. See AppleZip.readPaired.
        val bytes = AppleZip.readPaired(file)
        // Photo-JPEG tiles go through Android's own decoder; Cinepak is handled in
        // Kotlin and is the path verified against ffmpeg.
        val jpeg = JpegDecoder { data, w, h ->
            val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return@JpegDecoder null
            if (bmp.width != w || bmp.height != h) return@JpegDecoder null
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            bmp.recycle()
            ByteArray(w * h * 3).also { out ->
                for (i in px.indices) {
                    val p = px[i]
                    out[i * 3] = ((p shr 16) and 0xFF).toByte()
                    out[i * 3 + 1] = ((p shr 8) and 0xFF).toByte()
                    out[i * 3 + 2] = (p and 0xFF).toByte()
                }
            }
        }
        // Fill the empty space above and below the captured band with a gradient
        // taken from its own edges - a cylinder layer covers more angle simply by
        // having more rows, and the original pixels do not move.
        val extracted =
            if (isImage(file.name)) loadImagePanorama(bytes)
            else if (Qtvr.isCubic(bytes)) return Qtvr.extractCube(bytes, jpeg)
            else Qtvr.extract(bytes, jpeg, node)
        val pano = Qtvr.downscaleToFit(extracted, MAX_TEXTURE_DIM)
        // Remembered before the caps go on, because afterwards there is no way to
        // tell the decoded rows from the gradient ones.
        bandRows = pano.height
        return Caps.addGradient(pano, Caps.targetHeight(pano))
    }

    private fun isImage(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
    }

    /**
     * An already-assembled cylindrical panorama, as produced by upscale.py.
     *
     * Taken as a full turn, which every panorama in this collection is. The vertical
     * extent still comes from the pixel aspect ratio, exactly as it does for a
     * decoded .mov, so an upscaled file lands at the same horizon as its original.
     */
    private fun loadImagePanorama(bytes: ByteArray): Panorama {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw IllegalArgumentException("That image could not be decoded.")
        val w = bmp.width
        val h = bmp.height
        if (w < 2 || h < 2) throw IllegalArgumentException("That image is too small to be a panorama.")
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val rgb = ByteArray(w * h * 3)
        for (i in px.indices) {
            val p = px[i]
            rgb[i * 3] = ((p shr 16) and 0xFF).toByte()
            rgb[i * 3 + 1] = ((p shr 8) and 0xFF).toByte()
            rgb[i * 3 + 2] = (p and 0xFF).toByte()
        }
        Log.i(TAG, "image panorama ${w}x${h}, taken as a full turn")
        return Panorama(rgb, w, h, null)
    }

    private fun start(pano: Panorama) {
        // The geometry a gaze is turned into a texel with. Taken from the panorama
        // actually on screen - gradient caps and all - because that is what the
        // compositor is showing and therefore what the viewer is pointing at.
        gazeCentral = pano.centralAngle
        gazeAspect = pano.aspectRatio
        val band = if (bandRows in 1..pano.height) bandRows else pano.height
        gazeBandTop = ((pano.height - band) / 2).toFloat() / pano.height
        gazeBandSpan = band.toFloat() / pano.height
        Log.i(TAG, "panorama ${pano.width}x${pano.height} " +
            "centralAngle=${pano.centralAngle} aspect=${pano.aspectRatio}")
        // The texture is uploaded flipped, so native's readback of (0,0) is this
        // image's bottom-left. Logging both sides lets the two be compared directly.
        val w = pano.width
        val h = pano.height
        fun at(x: Int, y: Int): String {
            val o = (y * w + x) * 3
            return "[${pano.rgb[o].toInt() and 0xFF}," +
                "${pano.rgb[o + 1].toInt() and 0xFF},${pano.rgb[o + 2].toInt() and 0xFF}]"
        }
        Log.i(TAG, "texture should read back (0,0)=${at(0, h - 1)} " +
            "(mid)=${at(w / 2, h - 1 - h / 2)}")

        val rgba = pano.toRgba()
        val buf = ByteBuffer.allocateDirect(rgba.size).apply {
            put(rgba)
            rewind()
        }

        status.text = getString(R.string.entering_vr)
        // native copies out of this buffer before it returns, so nothing here has
        // to outlive the call - which matters when the copy is 64 MB.
        if (!nativeStart(buf, pano.width, pano.height, pano.centralAngle, pano.aspectRatio)) {
            failWith(nativeLastError().ifEmpty { "The viewer could not start." })
            return
        }

        // The native thread reports failures asynchronously; surface them rather
        // than leaving the user looking at a blank panel.
        pollForLateFailure()
    }

    private fun startCube(scene: CubeScene) {
        Log.i(TAG, "cubic panorama: 6 faces of ${scene.size}x${scene.size}")
        val n = scene.size
        val buf = ByteBuffer.allocateDirect(n * n * 4 * 6)
        for (face in scene.faces) {
            var s = 0
            while (s < face.size) {
                buf.put(face[s]); buf.put(face[s + 1]); buf.put(face[s + 2]); buf.put(-1)
                s += 3
            }
        }
        buf.rewind()

        status.text = getString(R.string.entering_vr)
        if (!nativeStartCube(buf, n)) {
            failWith(nativeLastError().ifEmpty { "The viewer could not start." })
            return
        }

        pollForLateFailure()
    }

    private fun finishWith(message: String) {
        val gen = generation
        status.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        // Only give up if nothing newer has been opened in the meantime.
        Handler(Looper.getMainLooper()).postDelayed({ if (gen == generation) finish() }, 4000)
    }

    override fun onDestroy() {
        nativeStop()
        // Paused rather than released: the player is process-wide and re-preparing
        // a 25 MB track on every open would show up as a delay.
        ambience.pause()
        if (live === this) live = null
        // Being destroyed here means the app was quit, not that someone stepped back
        // to the picker: Horizon OS keeps both alive together, so going back to the
        // panel never destroys this. The panel lives in its own task, so quitting the
        // immersive app leaves it sitting there looking like the app is still open -
        // which is exactly what it looked like.
        if (isFinishing) Quit.everything(this)
        super.onDestroy()
    }

    companion object {
        const val TAG = "QuestTimeVR"
        const val EXTRA_PATH = "com.questtime.vr.PATH"
        /** Set by the launcher so the list is already open on arrival. */
        const val EXTRA_SHOW_PICKER = "com.questtime.vr.SHOW_PICKER"

        /**
         * Open the generated welcome panorama instead of a file.
         *
         * A separate extra rather than a magic EXTRA_PATH value, because every path
         * in here is eventually handed to File() and a sentinel that looks like a
         * filename is a sentinel that will one day be opened as one.
         */
        const val EXTRA_WELCOME = "com.questtime.vr.WELCOME"

        /**
         * Which node of a scene to show, from zero. Absent means the first.
         *
         * An index rather than the node's own id, because the image track is
         * partitioned in storage order and ids have gaps - see [VrNode.id].
         */
        const val EXTRA_NODE = "com.questtime.vr.NODE"

        // Mirrors kInput* in vr_renderer.cpp. Native reports the press; the meaning
        // is decided here.
        /** The running instance, so a quit from either side can reach it. */
        @Volatile
        @JvmStatic
        var live: VrActivity? = null

        const val INPUT_MENU = 0
        const val INPUT_SELECT = 1
        const val INPUT_INFO = 2
        const val INPUT_UP = 3
        const val INPUT_DOWN = 4
        const val INPUT_CONFIRM = 5

        /**
         * Quest 3 reports maxSwapchainImageWidth/Height of 8192. Native re-checks
         * against the real runtime values and refuses clearly if this is ever wrong.
         */
        const val MAX_TEXTURE_DIM = 8192

        init {
            System.loadLibrary("questtimevr")
        }
    }
}
