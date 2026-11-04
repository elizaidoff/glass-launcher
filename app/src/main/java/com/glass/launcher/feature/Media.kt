package com.glass.launcher.feature

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class MediaInfo(
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val playing: Boolean,
    val pkg: String,
)

/**
 * Notification access lets the launcher see what is playing (for the Dynamic Island)
 * and how many notifications each app has (for the red badges on icons).
 */
class NotifListener : NotificationListenerService() {
    override fun onListenerConnected() {
        connected = true
        Media.start(this)
        refreshBadges()
    }

    override fun onListenerDisconnected() { connected = false }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refreshBadges()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refreshBadges()

    private fun refreshBadges() {
        val list = runCatching { activeNotifications }.getOrNull() ?: return
        badges.value = list.filter { it.isClearable && !it.isOngoing }
            .groupBy { it.packageName }.mapValues { it.value.size }
    }

    companion object {
        @Volatile var connected = false
        val badges = MutableStateFlow<Map<String, Int>>(emptyMap())

        fun isEnabled(ctx: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.split(':').any { it.startsWith(ctx.packageName + "/") }
        }
    }
}

object Media {
    private val _now = MutableStateFlow<MediaInfo?>(null)
    val now: StateFlow<MediaInfo?> = _now
    private var controller: MediaController? = null
    private var started = false

    private val cb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() { controller = null; publish() }
    }

    fun start(ctx: Context) {
        if (started) return
        val msm = ctx.getSystemService(MediaSessionManager::class.java) ?: return
        val comp = ComponentName(ctx, NotifListener::class.java)
        runCatching {
            msm.addOnActiveSessionsChangedListener({ list -> pick(list) }, comp)
            pick(msm.getActiveSessions(comp))
            started = true
        }
    }

    private fun pick(list: List<MediaController>?) {
        val best = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(cb)
            controller = best
            best?.registerCallback(cb)
        }
        publish()
    }

    private fun publish() {
        val c = controller
        val md = c?.metadata
        _now.value = if (c == null || md == null) null else MediaInfo(
            title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "",
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
            art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART),
            playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
            pkg = c.packageName,
        )
    }

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() = controller?.transportControls?.skipToNext()
    fun previous() = controller?.transportControls?.skipToPrevious()
}
