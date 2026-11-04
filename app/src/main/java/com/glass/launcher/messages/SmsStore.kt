package com.glass.launcher.messages

import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/** One conversation in the list. */
data class Conversation(
    val threadId: Long,
    val addresses: List<String>,
    val snippet: String,
    val date: Long,
    val unread: Boolean,
)

/** One bubble. [images] are content:// URIs of MMS picture parts. */
data class Message(
    val id: Long,
    val mms: Boolean,
    val address: String,
    val body: String,
    val date: Long,
    val outgoing: Boolean,
    val status: Status,
    val images: List<Uri> = emptyList(),
) {
    enum class Status { Received, Sending, Sent, Delivered, Failed }
}

/**
 * Reads and writes the phone's own message database (the same one every SMS app shares),
 * so nothing is lost when switching apps. Writes only work while Glass is the default SMS app.
 */
object SmsStore {

    /** Bumps whenever the message database changes. */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes
    private var observing = false

    fun observe(ctx: Context) {
        if (observing) return
        observing = true
        ctx.applicationContext.contentResolver.registerContentObserver(
            Uri.parse("content://mms-sms/"), true,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { _changes.value++ }
            },
        )
    }

    fun bump() { _changes.value++ }

    fun isDefault(ctx: Context) = Telephony.Sms.getDefaultSmsPackage(ctx) == ctx.packageName

    // ------------------------------------------------------------------ names

    private val names = ConcurrentHashMap<String, String>()

    /** Contact name for a number, or the number itself. */
    fun name(ctx: Context, address: String): String {
        if (address.isBlank()) return "לא ידוע"
        names[address]?.let { return it }
        val n = runCatching {
            ctx.contentResolver.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: prettyNumber(address)
        names[address] = n
        return n
    }

    fun photo(ctx: Context, address: String): Uri? = runCatching {
        ctx.contentResolver.query(
            Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)),
            arrayOf(ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0)?.let(Uri::parse) else null }
    }.getOrNull()

    fun clearNames() = names.clear()

    private fun prettyNumber(a: String): String {
        if (a.startsWith("+972") && a.length >= 12) {
            val local = "0" + a.removePrefix("+972")
            return if (local.length == 10) local.substring(0, 3) + "-" + local.substring(3) else local
        }
        return a
    }

    fun title(ctx: Context, c: Conversation) = c.addresses.joinToString(", ") { name(ctx, it) }

    // ------------------------------------------------------------------ list

    private fun canonical(ctx: Context): Map<Long, String> {
        val m = HashMap<Long, String>()
        runCatching {
            ctx.contentResolver.query(Uri.parse("content://mms-sms/canonical-addresses"), null, null, null, null)?.use { c ->
                val i = c.getColumnIndex("_id"); val a = c.getColumnIndex("address")
                while (c.moveToNext()) m[c.getLong(i)] = c.getString(a) ?: ""
            }
        }
        return m
    }

    fun conversations(ctx: Context): List<Conversation> {
        val addr = canonical(ctx)
        val out = ArrayList<Conversation>()
        runCatching {
            ctx.contentResolver.query(
                Uri.parse("content://mms-sms/conversations?simple=true"),
                arrayOf("_id", "recipient_ids", "snippet", "date", "read", "message_count"),
                null, null, "date DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(5) == 0) continue
                    val ids = (c.getString(1) ?: "").split(" ").mapNotNull { it.toLongOrNull() }
                    out += Conversation(
                        threadId = c.getLong(0),
                        addresses = ids.mapNotNull { addr[it] }.filter { it.isNotBlank() },
                        snippet = c.getString(2) ?: "",
                        date = c.getLong(3),
                        unread = c.getInt(4) == 0,
                    )
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ one thread

    fun messages(ctx: Context, threadId: Long, limit: Int = 400): List<Message> {
        val out = ArrayList<Message>()
        runCatching {
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf("_id", "address", "body", "date", "type", "status"),
                "thread_id = ?", arrayOf(threadId.toString()), "date DESC LIMIT $limit",
            )?.use { c ->
                while (c.moveToNext()) {
                    val type = c.getInt(4)
                    val outgoing = type != Telephony.TextBasedSmsColumns.MESSAGE_TYPE_INBOX
                    val status = when {
                        !outgoing -> Message.Status.Received
                        type == Telephony.TextBasedSmsColumns.MESSAGE_TYPE_FAILED -> Message.Status.Failed
                        type == Telephony.TextBasedSmsColumns.MESSAGE_TYPE_OUTBOX || type == Telephony.TextBasedSmsColumns.MESSAGE_TYPE_QUEUED -> Message.Status.Sending
                        c.getInt(5) == Telephony.TextBasedSmsColumns.STATUS_COMPLETE -> Message.Status.Delivered
                        else -> Message.Status.Sent
                    }
                    out += Message(c.getLong(0), false, c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3), outgoing, status)
                }
            }
        }
        runCatching {
            ctx.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf("_id", "date", "msg_box", "m_type"),
                "thread_id = ?", arrayOf(threadId.toString()), "date DESC LIMIT $limit",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val box = c.getInt(2)
                    val outgoing = box != Telephony.BaseMmsColumns.MESSAGE_BOX_INBOX
                    val (text, imgs) = mmsParts(ctx, id)
                    out += Message(
                        id, true, mmsSender(ctx, id), text, c.getLong(1) * 1000, outgoing,
                        when {
                            !outgoing -> Message.Status.Received
                            box == Telephony.BaseMmsColumns.MESSAGE_BOX_FAILED -> Message.Status.Failed
                            box == Telephony.BaseMmsColumns.MESSAGE_BOX_OUTBOX -> Message.Status.Sending
                            else -> Message.Status.Sent
                        },
                        imgs,
                    )
                }
            }
        }
        out.sortBy { it.date }
        return out
    }

    private fun mmsParts(ctx: Context, id: Long): Pair<String, List<Uri>> {
        val text = StringBuilder()
        val imgs = ArrayList<Uri>()
        runCatching {
            ctx.contentResolver.query(Uri.parse("content://mms/part"), arrayOf("_id", "ct", "text", "_data"), "mid = ?", arrayOf(id.toString()), null)?.use { c ->
                while (c.moveToNext()) {
                    val ct = c.getString(1) ?: ""
                    val pid = c.getLong(0)
                    when {
                        ct == "text/plain" -> {
                            val t = c.getString(2) ?: if (c.getString(3) != null) readPart(ctx, pid) else null
                            if (!t.isNullOrBlank()) { if (text.isNotEmpty()) text.append('\n'); text.append(t) }
                        }
                        ct.startsWith("image/") -> imgs += Uri.parse("content://mms/part/$pid")
                    }
                }
            }
        }
        return text.toString() to imgs
    }

    private fun readPart(ctx: Context, pid: Long): String? = runCatching {
        ctx.contentResolver.openInputStream(Uri.parse("content://mms/part/$pid"))?.use { it.readBytes().decodeToString() }
    }.getOrNull()

    private fun mmsSender(ctx: Context, id: Long): String = runCatching {
        ctx.contentResolver.query(Uri.parse("content://mms/$id/addr"), arrayOf("address", "type"), "type = 137", null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: ""

    fun threadFor(ctx: Context, addresses: List<String>): Long =
        runCatching { Telephony.Threads.getOrCreateThreadId(ctx, addresses.toSet()) }.getOrDefault(-1L)

    fun addressesOf(ctx: Context, threadId: Long): List<String> =
        conversations(ctx).firstOrNull { it.threadId == threadId }?.addresses ?: emptyList()

    // ------------------------------------------------------------------ writes (default app only)

    fun markRead(ctx: Context, threadId: Long) {
        if (!isDefault(ctx)) return
        runCatching {
            val v = ContentValues().apply { put("read", 1); put("seen", 1) }
            ctx.contentResolver.update(Telephony.Sms.CONTENT_URI, v, "thread_id = ? AND read = 0", arrayOf(threadId.toString()))
            ctx.contentResolver.update(Telephony.Mms.CONTENT_URI, v, "thread_id = ? AND read = 0", arrayOf(threadId.toString()))
        }
    }

    fun deleteThread(ctx: Context, threadId: Long): Boolean = runCatching {
        ctx.contentResolver.delete(Uri.parse("content://mms-sms/conversations/$threadId"), null, null) > 0
    }.getOrDefault(false)

    fun deleteMessage(ctx: Context, m: Message): Boolean = runCatching {
        val uri = if (m.mms) Uri.parse("content://mms/${m.id}") else Uri.parse("content://sms/${m.id}")
        ctx.contentResolver.delete(uri, null, null) > 0
    }.getOrDefault(false)

    fun insertIncoming(ctx: Context, address: String, body: String, date: Long, subId: Int): Uri? = runCatching {
        ctx.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, ContentValues().apply {
            put("address", address); put("body", body); put("date", date); put("date_sent", date)
            put("read", 0); put("seen", 0); put("type", Telephony.TextBasedSmsColumns.MESSAGE_TYPE_INBOX)
            if (subId >= 0) put("sub_id", subId)
        })
    }.getOrNull()

    fun insertOutgoing(ctx: Context, address: String, body: String, threadId: Long): Uri? = runCatching {
        ctx.contentResolver.insert(Telephony.Sms.Outbox.CONTENT_URI, ContentValues().apply {
            put("address", address); put("body", body); put("date", System.currentTimeMillis())
            put("read", 1); put("seen", 1); put("type", Telephony.TextBasedSmsColumns.MESSAGE_TYPE_OUTBOX)
            if (threadId > 0) put("thread_id", threadId)
        })
    }.getOrNull()

    fun setType(ctx: Context, uri: Uri, type: Int) {
        runCatching { ctx.contentResolver.update(uri, ContentValues().apply { put("type", type) }, null, null) }
    }

    fun setDelivered(ctx: Context, uri: Uri) {
        runCatching { ctx.contentResolver.update(uri, ContentValues().apply { put("status", Telephony.TextBasedSmsColumns.STATUS_COMPLETE) }, null, null) }
    }

    fun sameNumber(a: String, b: String) = PhoneNumberUtils.areSamePhoneNumber(a, b, "il")
}
