package com.glass.launcher.feature

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The extra things the home-screen search finds besides apps, like iPhone's Spotlight. */
object Spotlight {

    data class Contact(val name: String, val number: String?, val lookup: Uri)
    data class SettingLink(val title: String, val action: String, val words: List<String>)

    /** Android settings pages with Hebrew names, searchable from the home screen. */
    val settings = listOf(
        SettingLink("Wi‑Fi", Settings.ACTION_WIFI_SETTINGS, listOf("wifi", "וויפי", "ויפי", "רשת", "אינטרנט", "אלחוטי")),
        SettingLink("בלוטות'", Settings.ACTION_BLUETOOTH_SETTINGS, listOf("בלוטוס", "bluetooth", "אוזניות")),
        SettingLink("תצוגה ובהירות", Settings.ACTION_DISPLAY_SETTINGS, listOf("תצוגה", "בהירות", "מסך", "display")),
        SettingLink("צלילים ורטט", Settings.ACTION_SOUND_SETTINGS, listOf("צליל", "צלצול", "רטט", "עוצמה", "sound")),
        SettingLink("סוללה", Intent.ACTION_POWER_USAGE_SUMMARY, listOf("סוללה", "battery", "טעינה")),
        SettingLink("מיקום", Settings.ACTION_LOCATION_SOURCE_SETTINGS, listOf("מיקום", "gps", "location")),
        SettingLink("תאריך ושעה", Settings.ACTION_DATE_SETTINGS, listOf("תאריך", "שעה", "שעון")),
        SettingLink("שפה ומקלדת", Settings.ACTION_LOCALE_SETTINGS, listOf("שפה", "עברית", "מקלדת", "language")),
        SettingLink("אפליקציות", Settings.ACTION_APPLICATION_SETTINGS, listOf("אפליקציות", "יישומים", "apps")),
        SettingLink("התראות", Settings.ACTION_APP_NOTIFICATION_SETTINGS, listOf("התראות", "notifications")),
        SettingLink("נגישות", Settings.ACTION_ACCESSIBILITY_SETTINGS, listOf("נגישות", "accessibility")),
        SettingLink("אבטחה וטביעת אצבע", Settings.ACTION_SECURITY_SETTINGS, listOf("אבטחה", "טביעת", "אצבע", "סיסמה", "נעילה")),
        SettingLink("נקודה חמה", "android.settings.TETHER_SETTINGS", listOf("נקודה חמה", "hotspot", "שיתוף אינטרנט")),
        SettingLink("נתונים סלולריים", Settings.ACTION_DATA_ROAMING_SETTINGS, listOf("נתונים", "סלולרי", "גלישה", "סים")),
        SettingLink("אחסון", Settings.ACTION_INTERNAL_STORAGE_SETTINGS, listOf("אחסון", "זיכרון", "storage")),
        SettingLink("מצב טיסה", Settings.ACTION_AIRPLANE_MODE_SETTINGS, listOf("טיסה", "airplane")),
        SettingLink("נא לא להפריע", "android.settings.ZEN_MODE_SETTINGS", listOf("להפריע", "שקט", "dnd")),
        SettingLink("אודות הטלפון", Settings.ACTION_DEVICE_INFO_SETTINGS, listOf("אודות", "גרסה", "about")),
    )

    fun matchSettings(q: String): List<SettingLink> {
        val t = q.trim().lowercase()
        if (t.length < 2) return emptyList()
        return settings.filter { s -> s.title.lowercase().contains(t) || s.words.any { it.contains(t) || t.contains(it) } }.take(4)
    }

    fun hasContacts(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun contacts(ctx: Context, q: String): List<Contact> {
        val t = q.trim()
        if (t.length < 2 || !hasContacts(ctx)) return emptyList()
        val out = ArrayList<Contact>()
        runCatching {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?",
                arrayOf("%$t%", "%$t%"),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
            )?.use { c ->
                val seen = HashSet<String>()
                while (c.moveToNext() && out.size < 5) {
                    val name = c.getString(0) ?: continue
                    if (!seen.add(name)) continue
                    out += Contact(name, c.getString(1), ContactsContract.Contacts.getLookupUri(c.getLong(3), c.getString(2)))
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------ calculator

    /** "12*3+4" → "40". Null when the text isn't a sum. */
    fun calculate(q: String): String? {
        val s = q.replace(" ", "").replace('×', '*').replace('÷', '/').replace(',', '.')
        if (s.length < 3 || !s.any { it in "+-*/%^" } || !s.all { it.isDigit() || it in "+-*/%^().," }) return null
        if (!s.first().let { it.isDigit() || it == '(' || it == '-' }) return null
        return runCatching {
            val v = Parser(s).parse()
            if (v.isNaN() || v.isInfinite()) null
            else if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString()
            else "%.8f".format(java.util.Locale.US, v).trimEnd('0').trimEnd('.')
        }.getOrNull()
    }

    private class Parser(val s: String) {
        var i = 0
        fun parse(): Double { val v = expr(); require(i == s.length); return v }
        fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) { val op = s[i++]; val r = term(); v = if (op == '+') v + r else v - r }
            return v
        }
        fun term(): Double {
            var v = power()
            while (i < s.length && (s[i] == '*' || s[i] == '/' || s[i] == '%')) {
                val op = s[i++]; val r = power()
                v = when (op) { '*' -> v * r; '/' -> v / r; else -> v % r }
            }
            return v
        }
        fun power(): Double {
            val b = unary()
            if (i < s.length && s[i] == '^') { i++; return Math.pow(b, power()) }
            return b
        }
        fun unary(): Double {
            if (i < s.length && s[i] == '-') { i++; return -unary() }
            if (i < s.length && s[i] == '(') { i++; val v = expr(); require(i < s.length && s[i] == ')'); i++; return v }
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            require(i > st)
            return s.substring(st, i).toDouble()
        }
    }

    // ------------------------------------------------------------ dates

    private val formats = listOf("d.M.yyyy", "d/M/yyyy", "d-M-yyyy", "d.M.yy", "d/M/yy").map { DateTimeFormatter.ofPattern(it) }

    /** "5.3.2026" → its Hebrew date; "היום"/"מחר" too. */
    fun hebrewDate(q: String): String? {
        val t = q.trim()
        val d: LocalDate = when (t) {
            "היום", "תאריך" -> LocalDate.now()
            "מחר" -> LocalDate.now().plusDays(1)
            "אתמול" -> LocalDate.now().minusDays(1)
            else -> formats.firstNotNullOfOrNull { f -> runCatching { LocalDate.parse(t, f) }.getOrNull() } ?: return null
        }
        val z = d.atStartOfDay(ZoneId.systemDefault()).withHour(12)
        return HebrewDate.format(z, false)
    }

    fun openSetting(ctx: Context, s: SettingLink) {
        SystemActions.start(ctx, Intent(s.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun call(ctx: Context, c: Contact) {
        val n = c.number ?: return openContact(ctx, c)
        SystemActions.start(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(n))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openContact(ctx: Context, c: Contact) {
        SystemActions.start(ctx, Intent(Intent.ACTION_VIEW, c.lookup).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
