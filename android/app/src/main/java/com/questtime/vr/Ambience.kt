package com.questtime.vr

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import kotlin.random.Random

/**
 * Background music while a panorama is open.
 *
 * One instance for the process, not one per Activity. On Horizon OS the 2D panel and
 * the immersive activity are alive at the same time, so VrActivity never receives
 * onPause when you step back to the picker - lifecycle callbacks simply do not carry
 * the "which one is the user looking at" signal here. Instead the picker says so
 * itself: the viewer pausing stops it, opening a panorama starts.
 *
 * Every open drops into the track at a random point. The source is a half-hour
 * playlist, so the same panorama rarely opens on the same passage twice, and a fade
 * on either side keeps the arrival and departure from being abrupt. The seek stays
 * clear of the last [TAIL_GUARD_MS] so a fade-in is never cut short by the loop
 * wrapping a second later.
 */
class Ambience private constructor(private val context: Context) {

    private var player: MediaPlayer? = null
    private var preparing = false
    private var pendingOpen = false

    /** Whether music *should* be sounding, as distinct from whether it currently is. */
    private var wanted = false

    /** When the last open was asked for; see [pause] for why this has to exist. */
    private var lastOpenAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var volume = 0f
    private var target = 0f
    private var step = 0f
    private var onFadeDone: (() -> Unit)? = null

    private val fader = object : Runnable {
        override fun run() {
            val p = player ?: return
            volume = if (step >= 0f) minOf(target, volume + step) else maxOf(target, volume + step)
            runCatching { p.setVolume(volume, volume) }
            if (volume != target) {
                handler.postDelayed(this, TICK_MS)
            } else {
                val done = onFadeDone
                onFadeDone = null
                done?.invoke()
            }
        }
    }

    // -- public ------------------------------------------------------------

    /**
     * Whether the track is allowed to sound at all.
     *
     * Separate from [wanted], which is "should it be playing right now". This is the
     * user's standing answer, and it has to outlive opens and closes - muting and
     * then picking another panorama must not quietly start the music again.
     */
    var muted = false
        private set

    /** Returns the new state, so the caller can say what happened. */
    fun toggleMuted(): Boolean {
        muted = !muted
        Log.i(TAG, "ambience: ${if (muted) "muted" else "unmuted"} by the user")
        if (muted) {
            val p = player
            if (p != null && p.isPlaying) fadeTo(0f, FADE_OUT_MS) { runCatching { p.pause() } }
        } else if (wanted) {
            resume()
        }
        return muted
    }

    /**
     * Start, or move to a fresh random point if already sounding. Safe to call
     * before the player has finished preparing; the request is remembered.
     */
    fun open() {
        if (muted) return
        wanted = true
        lastOpenAt = SystemClock.elapsedRealtime()
        val p = player
        if (p == null) {
            pendingOpen = true
            prepare()
            return
        }
        if (p.isPlaying) {
            // Switching panoramas: duck out, jump elsewhere in the track, come back.
            fadeTo(0f, SWITCH_MS) {
                seekSomewhere(p)
                fadeTo(FULL, FADE_IN_MS)
            }
        } else {
            begin(p)
        }
    }

    /**
     * Fade down and pause - leaving the picker, or the headset losing focus.
     *
     * A pause landing immediately after an open is ignored. It was written for the 2D
     * panel, which paused the music as a *side effect* of launching a panorama rather
     * than because the user went back to it. The panel is gone, so that cause is too;
     * the guard is kept because it costs nothing and focus still flickers while an
     * immersive session comes up. Without this guard that stray pause cleared `wanted` while the player
     * was still preparing, and the track that had just been asked for never started -
     * silently, because there is nothing to report when the state machine is simply
     * doing what it was told.
     */
    fun pause() {
        val sinceOpen = SystemClock.elapsedRealtime() - lastOpenAt
        if (sinceOpen < OPEN_GRACE_MS) {
            Log.i(TAG, "ambience: ignoring a pause ${sinceOpen}ms after an open")
            return
        }
        wanted = false
        val p = player ?: return
        if (!p.isPlaying) return
        fadeTo(0f, FADE_OUT_MS) { runCatching { p.pause() } }
    }

    /** Come back after a [pause], from wherever the track left off. */
    fun resume() {
        if (muted) return
        if (player == null && pendingOpen) return       // still preparing; open() wins
        val p = player ?: return
        if (p.isPlaying) return
        wanted = true
        runCatching {
            volume = 0f
            p.setVolume(0f, 0f)
            p.start()
        }
        fadeTo(FULL, FADE_IN_MS)
    }

    fun release() {
        handler.removeCallbacks(fader)
        onFadeDone = null
        wanted = false
        pendingOpen = false
        player?.let { p -> runCatching { p.reset(); p.release() } }
        player = null
    }

    // -- internals ---------------------------------------------------------

    private fun prepare() {
        if (preparing || player != null) return
        preparing = true
        runCatching {
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // A track sent through the browser wins over the bundled one, so the
            // music can be swapped without rebuilding. The bundled one is looked up
            // by name rather than as R.raw.ambience so the project still compiles
            // without it - it is someone else's music. Neither present is silence.
            val custom = customTrack()
            if (custom != null) {
                p.setDataSource(custom.absolutePath)
                Log.i(TAG, "ambience: uploaded track, ${custom.length() / 1024} KB")
            } else {
                val res =
                    context.resources.getIdentifier("ambience", "raw", context.packageName)
                if (res == 0) {
                    Log.i(TAG, "no background music - running silent")
                    preparing = false
                    p.release()
                    return
                }
                context.resources.openRawResourceFd(res).use { afd ->
                    p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
            }
            p.isLooping = true
            p.setOnPreparedListener { ready ->
                preparing = false
                player = ready
                runCatching { ready.setVolume(0f, 0f) }
                if (pendingOpen && wanted) {
                    pendingOpen = false
                    begin(ready)
                } else {
                    Log.i(TAG, "ambience: ready, but no longer wanted " +
                        "(pendingOpen=$pendingOpen wanted=$wanted)")
                }
            }
            p.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "ambience failed ($what/$extra) - continuing silently")
                preparing = false
                player = null
                true
            }
            // Asynchronous on purpose: this runs while a panorama is decoding, and
            // stalling the main thread on a 25 MB file would show up as a hitch.
            p.prepareAsync()
        }.onFailure {
            Log.w(TAG, "ambience unavailable - continuing silently", it)
            preparing = false
        }
    }

    /** The track uploaded through the browser, if there is one. */
    private fun customTrack(): File? =
        File(context.filesDir, TRACK_FILE).takeIf { it.isFile && it.length() > 0 }

    /**
     * Drop the prepared player so the next open picks up a newly uploaded track.
     * Cheaper than trying to swap the source underneath a playing MediaPlayer, and
     * the next open is a fade-in anyway.
     */
    fun reloadTrack() {
        Log.i(TAG, "ambience: track changed, will reload on the next open")
        release()
    }

    private fun begin(p: MediaPlayer) {
        runCatching {
            seekSomewhere(p)
            volume = 0f
            p.setVolume(0f, 0f)
            p.start()
        }.onFailure {
            Log.w(TAG, "could not start ambience", it)
            return
        }
        fadeTo(FULL, FADE_IN_MS)
    }

    private fun seekSomewhere(p: MediaPlayer) {
        val total = runCatching { p.duration }.getOrDefault(0)
        if (total <= 0) return
        val usable = if (total > TAIL_GUARD_MS * 2) total - TAIL_GUARD_MS else total
        val at = Random.nextInt(usable)
        runCatching { p.seekTo(at) }
        Log.i(TAG, "ambience: in at ${at / 60000}m${(at / 1000) % 60}s of ${total / 60000}m")
    }

    private fun fadeTo(to: Float, durationMs: Long, done: (() -> Unit)? = null) {
        handler.removeCallbacks(fader)
        if (player == null) return
        target = to
        onFadeDone = done
        val ticks = maxOf(1, durationMs / TICK_MS)
        step = (to - volume) / ticks
        if (step == 0f) {
            onFadeDone = null
            done?.invoke()
            return
        }
        handler.post(fader)
    }

    companion object {
        @Volatile
        private var instance: Ambience? = null

        /** The process-wide player. Holds the application context, not an Activity. */
        fun of(context: Context): Ambience =
            instance ?: synchronized(this) {
                instance ?: Ambience(context.applicationContext).also { instance = it }
            }

        /**
         * Whether a track ships inside the app. The repository does not carry one -
         * it is someone else's music - so on a fresh clone this is false and the
         * viewer runs silent until somebody sends one.
         */
        fun hasBundledTrack(context: Context): Boolean =
            context.resources.getIdentifier("ambience", "raw", context.packageName) != 0

        /** Uploaded track, in the app's private files - invisible to the picker. */
        const val TRACK_FILE = "background-music"
        const val TRACK_NAME_FILE = "background-music.name"

        private const val TAG = VrActivity.TAG

        /** Music sits under the experience rather than on top of it. */
        private const val FULL = 0.45f

        private const val TICK_MS = 40L
        private const val FADE_IN_MS = 2500L
        private const val FADE_OUT_MS = 1200L

        /** Shorter both ways when swapping panoramas, so the gap does not drag. */
        private const val SWITCH_MS = 700L

        /** Keep the random seek this far from the end of the track. */
        private const val TAIL_GUARD_MS = 30_000

        /**
         * How long after an open a pause is treated as the panel resuming behind the
         * viewer rather than the user returning to it. Long enough to cover the
         * launch, far shorter than anyone looks at a panorama.
         */
        private const val OPEN_GRACE_MS = 2_500L
    }
}
