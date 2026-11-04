package com.glass.launcher.messages

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager

/** Sends texts, and keeps the "sent / delivered / failed" marks under the bubbles up to date. */
object SmsSender {

    @Suppress("DEPRECATION")
    private fun manager(ctx: Context, subId: Int = -1): SmsManager =
        if (subId >= 0) SmsManager.getSmsManagerForSubscriptionId(subId)
        else if (android.os.Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()

    /**
     * One person → SMS. Several people or a picture → MMS (a real group, like iPhone).
     * Returns false when nothing could be sent.
     */
    fun send(ctx: Context, addresses: List<String>, text: String, images: List<Uri> = emptyList()): Boolean {
        val to = addresses.filter { it.isNotBlank() }
        if (to.isEmpty() || (text.isBlank() && images.isEmpty())) return false
        if (to.size > 1 || images.isNotEmpty()) return MmsSender.send(ctx, to, text, images)
        val address = to[0]
        val sm = manager(ctx)
        val parts = sm.divideMessage(text)
        val def = SmsStore.isDefault(ctx)
        // A non-default app's sent messages are saved by Android itself; as the default app we save them.
        val uri = if (def) SmsStore.insertOutgoing(ctx, address, text, SmsStore.threadFor(ctx, to)) else null
        val sent = ArrayList<PendingIntent>()
        val delivered = ArrayList<PendingIntent>()
        parts.forEachIndexed { i, _ ->
            val last = i == parts.lastIndex
            sent += PendingIntent.getBroadcast(
                ctx, (System.nanoTime() % Int.MAX_VALUE).toInt() + i,
                Intent(ctx, SentReceiver::class.java).setAction(ACTION_SENT).setData(uri).putExtra("last", last),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
            )
            delivered += PendingIntent.getBroadcast(
                ctx, (System.nanoTime() % Int.MAX_VALUE).toInt() + 100 + i,
                Intent(ctx, SentReceiver::class.java).setAction(ACTION_DELIVERED).setData(uri).putExtra("last", last),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
            )
        }
        return runCatching {
            if (parts.size == 1) sm.sendTextMessage(address, null, text, sent[0], delivered[0])
            else sm.sendMultipartTextMessage(address, null, parts, sent, delivered)
            SmsStore.bump()
            true
        }.getOrElse {
            uri?.let { u -> SmsStore.setType(ctx, u, Telephony.TextBasedSmsColumns.MESSAGE_TYPE_FAILED) }
            false
        }
    }

    const val ACTION_SENT = "com.glass.launcher.SMS_SENT"
    const val ACTION_DELIVERED = "com.glass.launcher.SMS_DELIVERED"
}

class SentReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val uri = i.data ?: return SmsStore.bump()
        when (i.action) {
            SmsSender.ACTION_SENT -> if (i.getBooleanExtra("last", true) || resultCode != android.app.Activity.RESULT_OK) {
                SmsStore.setType(ctx, uri, if (resultCode == android.app.Activity.RESULT_OK) Telephony.TextBasedSmsColumns.MESSAGE_TYPE_SENT else Telephony.TextBasedSmsColumns.MESSAGE_TYPE_FAILED)
            }
            SmsSender.ACTION_DELIVERED -> if (i.getBooleanExtra("last", true)) SmsStore.setDelivered(ctx, uri)
        }
        SmsStore.bump()
    }
}
