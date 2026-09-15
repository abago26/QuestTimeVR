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
            runCatching { d.listFiles { f -> f.isFile && FileList.isPanorama(f.name) }?.toList() }
                .getOrNull() ?: emptyList()
        })
    }

    private var files: List<File> = emptyList()
    private var selected = 0
    private var picking = false

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
                INPUT_MENU, INPUT_SELECT ->
                    if (picking || showingInfo) closePanels() else openPicker()
                // The trigger commits. Separate from the button that opens, so a
                // single press cannot both summon the list and choose whatever
                // happened to be highlighted when it appeared.
                INPUT_CONFIRM -> if (picking) confirmPick()
                INPUT_INFO -> if (showingInfo) closePanels() else showInfo()
                INPUT_UP -> move(-1)
                INPUT_DOWN -> move(1)
            }
        }
    }

    private var showingInfo = false

    private fun closePanels() {
        picking = false
        showingInfo = false
        nativeSetPicking(false)
        nativeShowMenu(false)
    }

    private fun openPicker() {
        showingInfo = false
        nativeSetPicking(true)
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
    private fun rowCount() = files.size + 1
    private val musicRow get() = files.size

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
        val f = files.getOrNull(selected)
        closePanels()
        if (f == null) return
        // Same route the panel takes, so there is one way a file gets opened.
        startActivity(Intent(this, VrActivity::class.java).putExtra(EXTRA_PATH, f.absolutePath))
    }

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
                path?.let { Qtvr.inspect(File(it).readBytes()).summary }
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
            val (px, w, h) = MenuBar.buildList(
                files.map { it.nameWithoutExtension }, selected, musicMuted = ambience.muted)
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
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null) {
            finishWith("No file was passed to the viewer.")
            return
        }

        val gen = ++generation
        status.text = getString(R.string.decoding)

        // Redrawn per file, because the name on it is this file's. Sent before the
        // session starts so the bar is ready the first time someone asks for it.
        runCatching {
            val (px, w, h) = MenuBar.build(
                title = File(path).nameWithoutExtension,
                hint = getString(R.string.menu_hint),
            )
            nativeSetMenu(px, w, h)
        }.onFailure { Log.w(TAG, "menu bar could not be drawn", it) }

        // Start the music now rather than when the panorama appears: decoding takes
        // a few seconds, and the fade-in covers exactly that gap.
        ambience.open()

        // Deliberately not stopping the running session here: decoding takes a
        // moment and the old panorama is better company than a black void. Native's
        // claimSession hands over at the instant the new one is ready.
        Log.i(TAG, "opening ${File(path).name}")
        thread(name = "qtvr-decode") {
            val result = runCatching { load(File(path)) }
            Handler(Looper.getMainLooper()).post {
                if (gen != generation) return@post          // superseded mid-decode
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

    private fun load(file: File): Any {
        val bytes = file.readBytes()
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
            else Qtvr.extract(bytes, jpeg)
        val pano = Qtvr.downscaleToFit(extracted, MAX_TEXTURE_DIM)
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
