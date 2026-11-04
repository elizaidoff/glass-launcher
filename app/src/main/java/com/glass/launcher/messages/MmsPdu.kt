package com.glass.launcher.messages

import java.io.ByteArrayOutputStream

/**
 * A small reader/writer for MMS "PDUs" (the binary format the carrier uses, WAP-209/230).
 * Only what a messages app needs: who sent it, to whom, the text and the pictures.
 */
object MmsPdu {

    class Part(val contentType: String, val name: String?, val data: ByteArray, val charset: Int = 106)

    class Pdu(
        val type: Int,
        val from: String?,
        val to: List<String>,
        val cc: List<String>,
        val subject: String?,
        val contentLocation: String?,
        val transactionId: String?,
        val messageId: String?,
        val date: Long,
        val parts: List<Part>,
    )

    // header field codes (without the high bit)
    private const val BCC = 0x01
    private const val CC = 0x02
    private const val CONTENT_LOCATION = 0x03
    private const val CONTENT_TYPE = 0x04
    private const val DATE = 0x05
    private const val FROM = 0x09
    private const val MESSAGE_ID = 0x0B
    private const val MESSAGE_TYPE = 0x0C
    private const val SUBJECT = 0x16
    private const val TO = 0x17
    private const val TRANSACTION_ID = 0x18

    const val TYPE_SEND_REQ = 0x80
    const val TYPE_NOTIFICATION_IND = 0x82
    const val TYPE_RETRIEVE_CONF = 0x84

    private val wellKnownTypes = mapOf(
        0x00 to "*/*", 0x01 to "text/*", 0x02 to "text/html", 0x03 to "text/plain", 0x07 to "text/x-vCard",
        0x08 to "text/vnd.wap.wml", 0x1C to "image/*", 0x1D to "image/gif", 0x1E to "image/jpeg", 0x1F to "image/tiff",
        0x20 to "image/png", 0x21 to "image/vnd.wap.wbmp", 0x22 to "application/vnd.wap.multipart.*",
        0x23 to "application/vnd.wap.multipart.mixed", 0x33 to "application/vnd.wap.multipart.related",
        0x30 to "application/vnd.wap.multipart.alternative",
    )

    // ------------------------------------------------------------------ reading

    private class Reader(val b: ByteArray, var p: Int = 0) {
        fun more() = p < b.size
        fun peek() = b[p].toInt() and 0xFF
        fun byte() = b[p++].toInt() and 0xFF

        fun uintvar(): Int {
            var v = 0
            while (true) {
                val x = byte()
                v = (v shl 7) or (x and 0x7F)
                if (x and 0x80 == 0) return v
            }
        }

        fun text(): String {
            if (more() && peek() == 0x7F) p++ // quote
            val s = p
            while (p < b.size && b[p].toInt() != 0) p++
            val out = String(b, s, p - s, Charsets.UTF_8)
            if (p < b.size) p++
            return out
        }

        /** Value length (0..30 or 31 + uintvar). Only call when peek() <= 31. */
        fun valueLength(): Int { val x = byte(); return if (x == 31) uintvar() else x }

        fun skipValue() {
            val x = peek()
            when {
                x <= 30 -> { p++; p += x }
                x == 31 -> { p++; val l = uintvar(); p += l }
                x < 128 -> text()
                else -> p++
            }
        }

        fun integer(): Long {
            val x = peek()
            if (x >= 128) { p++; return (x and 0x7F).toLong() }
            val len = byte()
            var v = 0L
            repeat(len) { v = (v shl 8) or byte().toLong() }
            return v
        }

        /** Encoded-string-value: plain text, or length + charset + text. */
        fun encodedString(): String {
            val x = peek()
            if (x > 31) return text()
            val len = valueLength()
            val end = p + len
            val cs = integer().toInt()
            val s = p
            var e = s
            while (e < end && b[e].toInt() != 0) e++
            val out = decode(b.copyOfRange(if (s < b.size && b[s].toInt() == 0x7F) s + 1 else s, e), cs)
            p = end
            return out
        }
    }

    fun decode(bytes: ByteArray, charset: Int): String = when (charset) {
        1000 -> String(bytes, Charsets.UTF_16BE)
        1015 -> String(bytes, Charsets.UTF_16)
        4 -> String(bytes, Charsets.ISO_8859_1)
        3 -> String(bytes, Charsets.US_ASCII)
        else -> String(bytes, Charsets.UTF_8)
    }

    private fun cleanAddress(a: String) = a.substringBefore("/TYPE=").trim()

    /** Reads a content-type value; returns (type, charset, name). */
    private fun Reader.contentType(): Triple<String, Int, String?> {
        val x = peek()
        if (x >= 128) { p++; return Triple(wellKnownTypes[x and 0x7F] ?: "application/octet-stream", 106, null) }
        if (x > 31) return Triple(text(), 106, null)
        val len = valueLength()
        val end = p + len
        val media = if (peek() >= 128) (wellKnownTypes[byte() and 0x7F] ?: "application/octet-stream") else text()
        var cs = 106
        var name: String? = null
        while (p < end) {
            val t = peek()
            if (t >= 128) {
                p++
                when (t and 0x7F) {
                    0x01 -> cs = integer().toInt()                       // charset
                    0x05, 0x17 -> name = text()                           // name
                    else -> if (p < end) skipValue()
                }
            } else { text(); if (p < end) skipValue() }
        }
        p = end
        return Triple(media, cs, name)
    }

    fun parse(bytes: ByteArray): Pdu? = runCatching {
        val r = Reader(bytes)
        var type = 0
        var from: String? = null
        val to = ArrayList<String>()
        val cc = ArrayList<String>()
        var subject: String? = null
        var location: String? = null
        var tid: String? = null
        var mid: String? = null
        var date = 0L
        var multipart = false
        loop@ while (r.more()) {
            val f = r.peek()
            if (f < 0x80) { r.text(); r.skipValue(); continue }
            r.p++
            when (f and 0x7F) {
                MESSAGE_TYPE -> type = r.byte()
                FROM -> {
                    val len = r.valueLength()
                    val end = r.p + len
                    if (r.peek() == 0x80) { r.p++; from = cleanAddress(r.encodedString()) }
                    r.p = end
                }
                TO -> to += cleanAddress(r.encodedString())
                CC -> cc += cleanAddress(r.encodedString())
                BCC -> r.encodedString()
                SUBJECT -> subject = r.encodedString()
                CONTENT_LOCATION -> location = r.text()
                TRANSACTION_ID -> tid = r.text()
                MESSAGE_ID -> mid = r.text()
                DATE -> date = r.integer()
                CONTENT_TYPE -> {
                    val (t, _, _) = r.contentType()
                    multipart = t.contains("multipart")
                    break@loop // content type is always the last header; the body follows
                }
                else -> r.skipValue()
            }
        }
        val parts = ArrayList<Part>()
        if (multipart && r.more()) {
            val n = r.uintvar()
            repeat(n) {
                val hl = r.uintvar()
                val dl = r.uintvar()
                val hEnd = r.p + hl
                val (ct, cs, nm) = r.contentType()
                var name = nm
                while (r.p < hEnd) {
                    val h = r.peek()
                    if (h >= 128) {
                        r.p++
                        when (h and 0x7F) {
                            0x0E -> name = name ?: r.text() // content-location
                            else -> if (r.p < hEnd) r.skipValue()
                        }
                    } else { r.text(); if (r.p < hEnd) r.text() }
                }
                r.p = hEnd
                parts += Part(ct, name, bytes.copyOfRange(r.p, (r.p + dl).coerceAtMost(bytes.size)), cs)
                r.p += dl
            }
        }
        Pdu(type, from, to, cc, subject, location, tid, mid, date, parts)
    }.getOrNull()

    // ------------------------------------------------------------------ writing

    private fun ByteArrayOutputStream.uintvar(v0: Int) {
        var v = v0
        val tmp = ArrayList<Int>()
        tmp += v and 0x7F
        v = v ushr 7
        while (v > 0) { tmp += (v and 0x7F) or 0x80; v = v ushr 7 }
        tmp.reversed().forEach { write(it) }
    }

    private fun ByteArrayOutputStream.text(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        if (b.isNotEmpty() && (b[0].toInt() and 0xFF) >= 128) write(0x7F)
        write(b); write(0)
    }

    /** Builds a send-req: the text (UTF-8) and pictures as parts of one message, sent to everyone in [to]. */
    fun sendReq(to: List<String>, text: String, images: List<Pair<String, ByteArray>>): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(0x80 or MESSAGE_TYPE); o.write(TYPE_SEND_REQ)
        o.write(0x80 or TRANSACTION_ID); o.text("T" + java.lang.Long.toHexString(System.currentTimeMillis()))
        o.write(0x80 or 0x0D); o.write(0x92) // MMS version 1.2
        o.write(0x80 or FROM); o.write(0x01); o.write(0x81) // "insert my number"
        to.forEach { a ->
            o.write(0x80 or TO)
            val clean = a.filter { it.isDigit() || it == '+' }
            o.text("$clean/TYPE=PLMN")
        }
        o.write(0x80 or CONTENT_TYPE); o.write(0x80 or 0x23) // multipart/mixed
        val parts = ArrayList<Pair<ByteArray, ByteArray>>() // headers, data
        if (text.isNotBlank()) {
            val h = ByteArrayOutputStream()
            h.write(0x03); h.write(0x80 or 0x03); h.write(0x81); h.write(0xEA) // text/plain; charset=utf-8
            h.write(0x80 or 0x0E); h.text("text0.txt")
            parts += h.toByteArray() to text.toByteArray(Charsets.UTF_8)
        }
        images.forEachIndexed { i, (mime, data) ->
            val h = ByteArrayOutputStream()
            h.text(mime)
            h.write(0x80 or 0x0E); h.text("image$i." + (if (mime.endsWith("png")) "png" else "jpg"))
            parts += h.toByteArray() to data
        }
        o.uintvar(parts.size)
        parts.forEach { (h, d) -> o.uintvar(h.size); o.uintvar(d.size); o.write(h); o.write(d) }
        return o.toByteArray()
    }
}
