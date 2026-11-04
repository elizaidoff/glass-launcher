package com.glass.launcher.messages

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

/** Group messages and pictures (MMS): sending, downloading what arrives, and saving both to the phone's database. */
object MmsSender {

    private const val PHONE = "com.android.phone"

    private fun dir(ctx: Context) = File(ctx.cacheDir, "mms").apply { mkdirs() }

    private fun shareUri(ctx: Context, f: File): Uri {
        val u = FileProvider.getUriForFile(ctx, ctx.packageName + ".mms", f)
        ctx.grantUriPermission(PHONE, u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        return u
    }

    @Suppress("DEPRECATION")
    private fun manager(ctx: Context): SmsManager =
        if (android.os.Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()

    /** A picture made small enough for MMS (carriers accept about 300 KB). */
    private fun shrink(ctx: Context, uri: Uri): Pair<String, ByteArray>? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > 1600) sample *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        var scaled = bmp
        var q = 85
        var out: ByteArray
        while (true) {
            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, q, bos)
            out = bos.toByteArray()
            if (out.size <= 290_000) break
            if (q > 50) q -= 10
            else scaled = Bitmap.createScaledBitmap(scaled, (scaled.width * 0.8f).toInt(), (scaled.height * 0.8f).toInt(), true)
        }
        "image/jpeg" to out
    }.getOrNull()

    fun send(ctx: Context, to: List<String>, text: String, images: List<Uri>): Boolean {
        val pics = images.mapNotNull { shrink(ctx, it) }
        val pdu = MmsPdu.sendReq(to, text, pics)
        val f = File(dir(ctx), "send_${System.currentTimeMillis()}.pdu")
        f.writeBytes(pdu)
        val saved = if (SmsStore.isDefault(ctx)) persist(ctx, Telephony.BaseMmsColumns.MESSAGE_BOX_OUTBOX, null, to, text, pics, System.currentTimeMillis()) else null
        val pi = PendingIntent.getBroadcast(
            ctx, (System.nanoTime() % Int.MAX_VALUE).toInt(),
            Intent(ctx, MmsResultReceiver::class.java).setAction(ACTION_MMS_SENT).setData(saved).putExtra("file", f.absolutePath),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ONE_SHOT,
        )
        return runCatching {
            manager(ctx).sendMultimediaMessage(ctx, shareUri(ctx, f), null, null, pi)
            SmsStore.bump()
            true
        }.getOrElse {
            saved?.let { u -> setBox(ctx, u, Telephony.BaseMmsColumns.MESSAGE_BOX_FAILED) }
            false
        }
    }

    /** An MMS notice arrived: ask the carrier for the message itself. */
    fun download(ctx: Context, contentLocation: String) {
        val f = File(dir(ctx), "recv_${System.currentTimeMillis()}.pdu")
        f.createNewFile()
        val pi = PendingIntent.getBroadcast(
            ctx, (System.nanoTime() % Int.MAX_VALUE).toInt(),
            Intent(ctx, MmsResultReceiver::class.java).setAction(ACTION_MMS_DOWNLOADED).putExtra("file", f.absolutePath),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ONE_SHOT,
        )
        runCatching { manager(ctx).downloadMultimediaMessage(ctx, contentLocation, shareUri(ctx, f), null, pi) }
    }

    fun setBox(ctx: Context, uri: Uri, box: Int) {
        runCatching { ctx.contentResolver.update(uri, ContentValues().apply { put("msg_box", box) }, null, null) }
        SmsStore.bump()
    }

    /** Saves an MMS in the phone's database so every messages app sees it. */
    fun persist(
        ctx: Context, box: Int, from: String?, to: List<String>, text: String,
        pics: List<Pair<String, ByteArray>>, dateMs: Long, subject: String? = null,
    ): Uri? = runCatching {
        val others = (listOfNotNull(from) + to).filter { it.isNotBlank() }.distinct().let { all ->
            val mine = ownNumber(ctx)
            all.filterNot { a -> mine != null && SmsStore.sameNumber(a, mine) }.ifEmpty { all }
        }
        val thread = SmsStore.threadFor(ctx, if (box == Telephony.BaseMmsColumns.MESSAGE_BOX_INBOX) others else to)
        val base = when (box) {
            Telephony.BaseMmsColumns.MESSAGE_BOX_INBOX -> Telephony.Mms.Inbox.CONTENT_URI
            Telephony.BaseMmsColumns.MESSAGE_BOX_OUTBOX -> Telephony.Mms.Outbox.CONTENT_URI
            else -> Telephony.Mms.Sent.CONTENT_URI
        }
        val v = ContentValues().apply {
            put("thread_id", thread)
            put("date", dateMs / 1000)
            put("date_sent", dateMs / 1000)
            put("msg_box", box)
            put("read", if (box == Telephony.BaseMmsColumns.MESSAGE_BOX_INBOX) 0 else 1)
            put("seen", if (box == Telephony.BaseMmsColumns.MESSAGE_BOX_INBOX) 0 else 1)
            put("m_type", if (box == Telephony.BaseMmsColumns.MESSAGE_BOX_INBOX) 132 else 128)
            put("v", 18)
            put("ct_t", "application/vnd.wap.multipart.related")
            put("text_only", if (pics.isEmpty()) 1 else 0)
            subject?.let { put("sub", it); put("sub_cs", 106) }
        }
        val uri = ctx.contentResolver.insert(base, v) ?: return null
        val id = uri.lastPathSegment ?: return null
        val partUri = Uri.parse("content://mms/$id/part")
        if (text.isNotBlank()) {
            ctx.contentResolver.insert(partUri, ContentValues().apply {
                put("mid", id); put("ct", "text/plain"); put("chset", 106); put("cl", "text0.txt"); put("cid", "<text0>"); put("text", text)
            })
        }
        pics.forEachIndexed { i, (mime, data) ->
            ctx.contentResolver.insert(partUri, ContentValues().apply {
                put("mid", id); put("ct", mime); put("cl", "image$i.jpg"); put("cid", "<image$i>")
            })?.let { pu -> ctx.contentResolver.openOutputStream(pu)?.use { it.write(data) } }
        }
        val addrUri = Uri.parse("content://mms/$id/addr")
        fun addr(a: String, type: Int) = ctx.contentResolver.insert(addrUri, ContentValues().apply {
            put("msg_id", id); put("address", a); put("type", type); put("charset", 106)
        })
        if (from != null) addr(from, 137) else addr("insert-address-token", 137)
        to.forEach { addr(it, 151) }
        SmsStore.bump()
        uri
    }.getOrNull()

    private fun ownNumber(ctx: Context): String? = runCatching {
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_PHONE_NUMBERS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        val sm = ctx.getSystemService(android.telephony.SubscriptionManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 33) sm.getPhoneNumber(android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId()).takeIf { it.isNotBlank() }
        else legacyNumber(ctx)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun legacyNumber(ctx: Context): String? =
        ctx.getSystemService(android.telephony.TelephonyManager::class.java).line1Number?.takeIf { it.isNotBlank() }

    const val ACTION_MMS_SENT = "com.glass.launcher.MMS_SENT"
    const val ACTION_MMS_DOWNLOADED = "com.glass.launcher.MMS_DOWNLOADED"
}

/** Results from Android's MMS service. */
class MmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val file = i.getStringExtra("file")?.let(::File)
        val ok = resultCode == android.app.Activity.RESULT_OK
        when (i.action) {
            MmsSender.ACTION_MMS_SENT -> {
                i.data?.let { MmsSender.setBox(ctx, it, if (ok) Telephony.BaseMmsColumns.MESSAGE_BOX_SENT else Telephony.BaseMmsColumns.MESSAGE_BOX_FAILED) }
                file?.delete()
            }
            MmsSender.ACTION_MMS_DOWNLOADED -> {
                val pending = goAsync()
                Thread {
                    try {
                        val bytes = file?.takeIf { it.exists() }?.readBytes()
                        val pdu = bytes?.let { MmsPdu.parse(it) }
                        if (ok && pdu != null && pdu.type == MmsPdu.TYPE_RETRIEVE_CONF) {
                            val text = pdu.parts.filter { it.contentType == "text/plain" }.joinToString("\n") { MmsPdu.decode(it.data, it.charset) }
                            val pics = pdu.parts.filter { it.contentType.startsWith("image/") }.map { it.contentType to it.data }
                            val date = if (pdu.date > 0) pdu.date * 1000 else System.currentTimeMillis()
                            val uri = MmsSender.persist(ctx, Telephony.BaseMmsColumns.MESSAGE_BOX_INBOX, pdu.from, pdu.to + pdu.cc, text, pics, date, pdu.subject)
                            if (uri != null) MessageNotifier.incoming(ctx, pdu.from ?: "", text.ifBlank { if (pics.isNotEmpty()) "📷 תמונה" else "הודעת MMS" })
                        } else if (!ok) {
                            MessageNotifier.incoming(ctx, "", "לא הצלחנו להוריד הודעת MMS. בדוק שהנתונים הסלולריים פועלים.")
                        }
                    } finally {
                        file?.delete()
                        pending.finish()
                    }
                }.start()
            }
        }
    }
}
