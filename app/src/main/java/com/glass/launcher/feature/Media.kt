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

/** A notification shown in our Notification Center. */
data class NotifItem(
    val key: String,
    val pkg: String,
    val title: String,
    val text: String,
    val time: Long,
    val intent: android.app.PendingIntent?,
    val autoCancel: Boolean,
)

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
        instance = this
        Media.start(this)
        refreshBadges()
        runCatching { activeNotifications?.filter { isPodsNotification(it) }?.forEach { readPods(it) } }
    }

    override fun onListenerDisconnected() { connected = false; instance = null }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refreshBadges()
        if (sbn != null && isPodsNotification(sbn)) readPods(sbn)
        else if (sbn != null) com.glass.launcher.lock.LockService.instance?.hud?.banner(sbn)
    }

    /** Any AirPods battery app (MaterialPods, CAPod, AirBattery, …): a notification that names the AirPods and shows a %. */
    private fun isPodsNotification(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName.lowercase()
        if (listOf("materialpods", "capod", "airbattery", "podsbattery", "airdroid.pods", "openpods", "librepods", "assistanttrigger", "airpods").any { pkg.contains(it) }) return true
        val ex = sbn.notification.extras
        val t = (ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: "") + " " + (ex.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: "")
        return t.contains("AirPods", true) && t.contains('%')
    }

    /** That app already talks to the AirPods; take the numbers from its notification. */
    private fun readPods(sbn: StatusBarNotification) {
        val ex = sbn.notification.extras
        val text = listOfNotNull(
            ex.getCharSequence(android.app.Notification.EXTRA_TITLE),
            ex.getCharSequence(android.app.Notification.EXTRA_TEXT),
            ex.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT),
            ex.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT),
            ex.getCharSequence(android.app.Notification.EXTRA_SUMMARY_TEXT),
        ).joinToString(" ") + " " + (ex.getCharSequenceArray(android.app.Notification.EXTRA_TEXT_LINES)?.joinToString(" ") ?: "")
        if (text.isNotBlank()) AirPods.fromNotificationText(text)
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refreshBadges()

    private fun refreshBadges() {
        val list = runCatching { activeNotifications }.getOrNull() ?: return
        val shown = list.filter { it.isClearable && !it.isOngoing && (it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) == 0 }
        badges.value = shown.groupBy { it.packageName }.mapValues { it.value.size }
        items.value = shown.map { sbn ->
            val ex = sbn.notification.extras
            NotifItem(
                key = sbn.key,
                pkg = sbn.packageName,
                title = ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: "",
                text = (ex.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(android.app.Notification.EXTRA_TEXT))?.toString() ?: "",
                time = sbn.postTime,
                intent = sbn.notification.contentIntent,
                autoCancel = (sbn.notification.flags and android.app.Notification.FLAG_AUTO_CANCEL) != 0,
            )
        }.sortedByDescending { it.time }
    }

    companion object {
        @Volatile var connected = false
        @Volatile var instance: NotifListener? = null
        val badges = MutableStateFlow<Map<String, Int>>(emptyMap())
        val items = MutableStateFlow<List<NotifItem>>(emptyList())

        fun dismiss(key: String) { runCatching { instance?.cancelNotification(key) } }
        fun dismissAll(keys: List<String>) { runCatching { instance?.cancelNotifications(keys.toTypedArray()) } }

        fun open(ctx: Context, item: NotifItem) {
            runCatching {
                val opts = android.app.ActivityOptions.makeBasic()
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    opts.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }
                item.intent?.send(ctx, 0, null, null, null, null, opts.toBundle())
                    ?: ctx.packageManager.getLaunchIntentForPackage(item.pkg)?.let { ctx.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            if (item.autoCancel) dismiss(item.key)
        }

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
