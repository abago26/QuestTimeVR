package com.questtime.vr

import android.app.Activity
import android.util.Log

/**
 * Closing the whole app when either half of it is closed.
 *
 * The panel and the immersive viewer live in **separate tasks** - Horizon OS puts an
 * immersive activity in its own container, so two `singleTask` activities with the
 * same affinity still end up rooted in different tasks. Quitting one therefore leaves
 * the other running, and the app looks like it is still open because half of it is.
 *
 * Stepping back from a panorama to the picker is not a quit, and does not come
 * through here: Horizon OS keeps both alive together and never destroys the viewer
 * for that. A destroy with `isFinishing` really does mean the app was closed.
 */
object Quit {

    /**
     * Guards the second half of a mutual close.
     *
     * Each side finishes the other, so without this the first one through would be
     * finished by the second and call straight back. It is never reset: the flag
     * lives as long as the process, and the process is what is being torn down.
     */
    @Volatile
    private var quitting = false

    fun everything(from: Activity) {
        // The viewer going away while the panel is up is someone stepping back to
        // pick another file, not a quit - and they expect the panel to still be
        // there. Only a viewer destroyed with nothing else showing means the app was
        // closed. Without this the safer-looking change does the opposite of what was
        // asked: leaving a panorama would take the whole app with it.
        if (from is VrActivity && MainActivity.showing) {
            Log.i(VrActivity.TAG, "viewer closed with the panel up - staying in the picker")
            return
        }
        if (quitting) return
        quitting = true
        Log.i(VrActivity.TAG, "quitting - closing both halves from ${from.javaClass.simpleName}")

        // The music is process-wide and outlives either activity, so it has to be
        // told separately or it plays on into whatever the user does next.
        runCatching { Ambience.of(from).pause() }

        val other: Activity? =
            if (from is VrActivity) MainActivity.live else VrActivity.live
        // finishAndRemoveTask, not finish: they are in different tasks, and leaving an
        // empty task behind is what puts a dead entry in the app switcher.
        runCatching { other?.finishAndRemoveTask() }
            .onFailure { Log.w(VrActivity.TAG, "could not close the other half", it) }
    }
}
