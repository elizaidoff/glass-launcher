package com.glass.launcher.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.glass.launcher.messages.SmsStore
import kotlinx.coroutines.flow.MutableStateFlow

/** Live calls, shared between the call service and the call screen. */
object CallManager {
    val calls = MutableStateFlow<List<Call>>(emptyList())
    val audio = MutableStateFlow<CallAudioState?>(null)
    val tick = MutableStateFlow(0)
    var service: CallService? = null

    val current: Call? get() = calls.value.firstOrNull { it.state != Call.STATE_DISCONNECTED }

    fun number(c: Call): String = c.details?.handle?.schemeSpecificPart ?: ""

    fun answer(c: Call) = c.answer(VideoProfile.STATE_AUDIO_ONLY)
    fun hangUp(c: Call) { if (c.state == Call.STATE_RINGING) c.reject(false, null) else c.disconnect() }

    fun mute(on: Boolean) = service?.setMuted(on)
    fun speaker(on: Boolean) = service?.setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE)
    fun bluetooth() = service?.setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)

    fun dial(ctx: Context, number: String) {
        val tm = ctx.getSystemService(TelecomManager::class.java)
        runCatching { tm.placeCall(Uri.fromParts("tel", number, null), null) }
            .onFailure { runCatching { ctx.startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    fun isDefaultDialer(ctx: Context) = ctx.getSystemService(TelecomManager::class.java).defaultDialerPackage == ctx.packageName

    // ---------------------------------------------------------------- notification

    private const val CH_IN = "calls_incoming"
    private const val CH_ON = "calls_ongoing"
    const val NOTIF_ID = 4242

    fun notification(ctx: Context, c: Call): Notification {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_IN) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_IN, "שיחות נכנסות", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
            nm.createNotificationChannel(NotificationChannel(CH_ON, "שיחה פעילה", NotificationManager.IMPORTANCE_LOW))
        }
        val ringing = c.state == Call.STATE_RINGING
        val num = number(c)
        val name = if (num.isBlank()) "מספר חסוי" else SmsStore.name(ctx, num)
        val open = PendingIntent.getActivity(ctx, 1, Intent(ctx, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val answer = PendingIntent.getBroadcast(ctx, 2, Intent(ctx, CallActionReceiver::class.java).setAction("answer"), PendingIntent.FLAG_IMMUTABLE)
        val end = PendingIntent.getBroadcast(ctx, 3, Intent(ctx, CallActionReceiver::class.java).setAction("end"), PendingIntent.FLAG_IMMUTABLE)
        val person = Person.Builder().setName(name).build()
        val b = NotificationCompat.Builder(ctx, if (ringing) CH_IN else CH_ON)
            .setSmallIcon(com.glass.launcher.R.drawable.ic_phone_fg)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .setStyle(if (ringing) NotificationCompat.CallStyle.forIncomingCall(person, end, answer) else NotificationCompat.CallStyle.forOngoingCall(person, end))
        // Android requires a full-screen intent on call-style notifications; on the quiet channel it never pops up.
        b.setFullScreenIntent(open, ringing)
        if (ringing) b.setPriority(NotificationCompat.PRIORITY_MAX)
        return b.build()
    }

    // ---------------------------------------------------------------- call log

    data class LogEntry(val number: String, val date: Long, val type: Int, val duration: Long, val count: Int)

    fun log(ctx: Context, missedOnly: Boolean): List<LogEntry> {
        val out = ArrayList<LogEntry>()
        runCatching {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE, CallLog.Calls.DURATION),
                if (missedOnly) "${CallLog.Calls.TYPE} = ${CallLog.Calls.MISSED_TYPE}" else null, null, "${CallLog.Calls.DATE} DESC LIMIT 300",
            )?.use { c ->
                while (c.moveToNext()) {
                    val e = LogEntry(c.getString(0) ?: "", c.getLong(1), c.getInt(2), c.getLong(3), 1)
                    val last = out.lastOrNull()
                    // Like iPhone: repeated calls from the same number in a row show once with "(3)".
                    if (last != null && last.number == e.number && last.type == e.type) out[out.lastIndex] = last.copy(count = last.count + 1)
                    else out += e
                }
            }
        }
        return out
    }
}

class CallService : InCallService() {
    private val cb = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) { update(); if (state == Call.STATE_DISCONNECTED) removeLater(call) }
        override fun onDetailsChanged(call: Call, details: Call.Details) = update()
    }

    override fun onCreate() { super.onCreate(); CallManager.service = this }
    override fun onDestroy() { CallManager.service = null; super.onDestroy() }

    override fun onCallAdded(call: Call) {
        call.registerCallback(cb)
        CallManager.calls.value = CallManager.calls.value + call
        update()
        startActivity(Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(cb)
        CallManager.calls.value = CallManager.calls.value - call
        update()
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) { CallManager.audio.value = audioState }

    private fun removeLater(call: Call) {
        android.os.Handler(mainLooper).postDelayed({ CallManager.calls.value = CallManager.calls.value - call; update() }, 1200)
    }

    private fun update() {
        CallManager.tick.value++
        val nm = getSystemService(NotificationManager::class.java)
        val c = CallManager.current
        if (c == null) nm.cancel(CallManager.NOTIF_ID)
        else runCatching { nm.notify(CallManager.NOTIF_ID, CallManager.notification(this, c)) }
    }
}

class CallActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val c = CallManager.current ?: return
        when (i.action) {
            "answer" -> { CallManager.answer(c); ctx.startActivity(Intent(ctx, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            "end" -> CallManager.hangUp(c)
        }
    }
}
