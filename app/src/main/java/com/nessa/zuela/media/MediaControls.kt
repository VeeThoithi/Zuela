package com.nessa.zuela.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.nessa.zuela.notify.ZuelaNotificationListener

/**
 * Controls whatever media is currently playing (Spotify, YouTube Music, etc.)
 * through Android's MediaSession API. Reading active sessions requires the
 * Notification Listener to be enabled — the same permission the message
 * features use — so [available] reports whether we can control anything.
 */
class MediaControls(private val context: Context) {

    private fun controllers(): List<MediaController> = try {
        val manager =
            context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        manager.getActiveSessions(
            ComponentName(context, ZuelaNotificationListener::class.java)
        )
    } catch (e: SecurityException) {
        emptyList() // Notification access not granted.
    } catch (e: Exception) {
        emptyList()
    }

    /** Prefer a session that's actually playing; otherwise the first one. */
    private fun active(): MediaController? {
        val list = controllers()
        return list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull()
    }

    val available: Boolean get() = controllers().isNotEmpty()

    fun play(): Boolean = withActive { it.transportControls.play() }
    fun pause(): Boolean = withActive { it.transportControls.pause() }
    fun next(): Boolean = withActive { it.transportControls.skipToNext() }
    fun previous(): Boolean = withActive { it.transportControls.skipToPrevious() }
    fun stop(): Boolean = withActive { it.transportControls.stop() }

    private inline fun withActive(action: (MediaController) -> Unit): Boolean {
        val controller = active() ?: return false
        return try {
            action(controller)
            true
        } catch (e: Exception) {
            false
        }
    }
}
