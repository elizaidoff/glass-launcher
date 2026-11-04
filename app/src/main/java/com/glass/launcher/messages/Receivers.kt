package com.glass.launcher.messages

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput

/** A text arrived (only delivered to the default SMS app). */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(i) ?: return
        if (msgs.isEmpty()) return
        val address = msgs[0].displayOriginatingAddress ?: msgs[0].originatingAddress ?: ""
        val body = msgs.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
        val subId = i.getIntExtra("subscription", -1)
        val pending = goAsync()
        Thread {
            try {
                SmsStore.insertIncoming(ctx, address, body, System.currentTimeMillis(), subId)
                SmsStore.bump()
                MessageNotifier.incoming(ctx, address, body)
            } finally { pending.finish() }
        }.start()
    }
}

/** An MMS notice arrived (default SMS app only): fetch the message. */
class MmsPushReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val data = i.getByteArrayExtra("data") ?: return
        val pdu = MmsPdu.parse(data) ?: return
        if (pdu.type == MmsPdu.TYPE_NOTIFICATION_IND && pdu.contentLocation != null) MmsSender.download(ctx, pdu.contentLocation)
    }
}

/** "Reply with a message" from the incoming-call screen. Required for the default SMS app role. */
class QuickReplyService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val to = intent?.data?.schemeSpecificPart?.split(",", ";")?.map { it.trim() }?.filter { it.isNotEmpty() }
        if (!text.isNullOrBlank() && !to.isNullOrEmpty()) SmsSender.send(this, to, text)
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

/** Reply / mark-as-read buttons on the message notification. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val address = i.getStringExtra("address") ?: return
        val thread = SmsStore.threadFor(ctx, listOf(address))
        when (i.action) {
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(i)?.getCharSequence(KEY_REPLY)?.toString()
                if (!text.isNullOrBlank()) SmsSender.send(ctx, listOf(address), text)
                SmsStore.markRead(ctx, thread)
                MessageNotifier.cancel(ctx, address)
            }
            ACTION_READ -> { SmsStore.markRead(ctx, thread); MessageNotifier.cancel(ctx, address) }
        }
    }

    companion object {
        const val ACTION_REPLY = "com.glass.launcher.MSG_REPLY"
        const val ACTION_READ = "com.glass.launcher.MSG_READ"
        const val KEY_REPLY = "reply"
    }
}

object MessageNotifier {
    private const val CHANNEL = "messages"

    private fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "הודעות", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "הודעות SMS ו-MMS חדשות"
            })
        }
    }

    private fun idFor(address: String) = address.filter { it.isDigit() }.takeLast(9).toIntOrNull() ?: address.hashCode()

    fun cancel(ctx: Context, address: String) = ctx.getSystemService(NotificationManager::class.java).cancel(idFor(address))

    fun incoming(ctx: Context, address: String, body: String) {
        if (MessagesActivity.openThreadAddress?.let { SmsStore.sameNumber(it, address) } == true) return // already looking at it
        channel(ctx)
        val name = if (address.isBlank()) "הודעות" else SmsStore.name(ctx, address)
        val thread = SmsStore.threadFor(ctx, listOf(address))
        val open = PendingIntent.getActivity(
            ctx, idFor(address),
            Intent(ctx, MessagesActivity::class.java).putExtra("thread", thread).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val sender = Person.Builder().setName(name).setKey(address).build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("אני").build())
            .addMessage(body, System.currentTimeMillis(), sender)
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(com.glass.launcher.R.drawable.ic_messages_fg)
            .setColor(0xFF34C759.toInt())
            .setStyle(style)
            .setContentTitle(name)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
        if (address.isNotBlank()) {
            val reply = PendingIntent.getBroadcast(
                ctx, idFor(address) + 1,
                Intent(ctx, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.ACTION_REPLY).putExtra("address", address),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val read = PendingIntent.getBroadcast(
                ctx, idFor(address) + 2,
                Intent(ctx, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.ACTION_READ).putExtra("address", address),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(
                NotificationCompat.Action.Builder(0, "תשובה", reply)
                    .addRemoteInput(RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY).setLabel("הודעה").build())
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .build()
            )
            b.addAction(NotificationCompat.Action.Builder(0, "סימון כנקרא", read).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).build())
        }
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(idFor(address), b.build()) }
    }

    @Suppress("unused") private fun uri(a: String) = Uri.parse("smsto:$a")
}
