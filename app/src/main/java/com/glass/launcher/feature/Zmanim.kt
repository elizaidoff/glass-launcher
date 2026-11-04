package com.glass.launcher.feature

import android.content.Context
import com.glass.launcher.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** One published time of the day. */
data class Zman(val title: String, val time: ZonedDateTime)

data class DayTimes(val date: LocalDate, val place: String, val list: List<Zman>)

data class ShabbatTimes(val candles: ZonedDateTime?, val havdalah: ZonedDateTime?, val parasha: String?, val place: String)

/**
 * Times of the day and Shabbat times from Hebcal (hebcal.com), a long-running public Jewish calendar service.
 * The phone needs internet; results are kept so they still show offline for the same day.
 * The city Hebcal used is shown with the times so you can always see what they are for.
 */
object Zmanim {
    private val zone: ZoneId = ZoneId.of("Asia/Jerusalem")
    private val _day = MutableStateFlow<DayTimes?>(null)
    val day: StateFlow<DayTimes?> = _day
    private val _shabbat = MutableStateFlow<ShabbatTimes?>(null)
    val shabbat: StateFlow<ShabbatTimes?> = _shabbat

    /** Hebcal field -> Hebrew title, in the order they are shown. */
    val fields = listOf(
        "alotHaShachar" to "עלות השחר",
        "misheyakir" to "זמן טלית ותפילין",
        "sunrise" to "הנץ החמה",
        "sofZmanShmaMGA" to "סוף זמן ק״ש (מג״א)",
        "sofZmanShma" to "סוף זמן ק״ש (גר״א)",
        "sofZmanTfillaMGA" to "סוף זמן תפילה (מג״א)",
        "sofZmanTfilla" to "סוף זמן תפילה (גר״א)",
        "chatzot" to "חצות היום",
        "minchaGedola" to "מנחה גדולה",
        "minchaKetana" to "מנחה קטנה",
        "plagHaMincha" to "פלג המנחה",
        "sunset" to "שקיעה",
        "tzeit7083deg" to "צאת הכוכבים",
    )

    /** Well-known GeoNames ids, so Hebcal also applies its own local defaults (e.g. Jerusalem 40 minutes). */
    private val geonames = mapOf("ירושלים" to 281184, "תל אביב" to 293397, "חיפה" to 294801)

    private fun locationQuery(c: City): String =
        geonames[c.name]?.let { "geonameid=$it" }
            ?: "latitude=${c.lat}&longitude=${c.lon}&tzid=Asia%2FJerusalem"

    fun city(): City = Cities.getOrElse(App.instance.prefs.cityIndex) { Cities[0] }

    /** Candle-lighting minutes before sunset: the user's choice, else Hebcal's own default for the city. */
    fun candleMinutes(): Int? = App.instance.prefs.candleMinutes.takeIf { it > 0 }

    init { loadCache() }

    private fun loadCache() {
        val p = App.instance.prefs
        runCatching { p.zmanimCache?.let { parseDay(JSONObject(it)) }?.let { _day.value = it } }
        runCatching { p.shabbatCache?.let { parseShabbat(JSONObject(it)) }?.let { _shabbat.value = it } }
    }

    suspend fun refresh(force: Boolean = false) = withContext(Dispatchers.IO) {
        val today = LocalDate.now(zone)
        val p = App.instance.prefs
        val key = "${p.cityIndex}:${p.candleMinutes}:${p.havdalahMode}:$today"
        if (!force && key == p.zmanimKey && _day.value != null) return@withContext
        val c = city()
        runCatching {
            val z = get("https://www.hebcal.com/zmanim?cfg=json&${locationQuery(c)}&date=$today")
            parseDay(JSONObject(z))?.let { _day.value = it; p.zmanimCache = z }
        }
        runCatching {
            val b = candleMinutes()?.let { "&b=$it" } ?: ""
            val hav = when (val m = p.havdalahMode) { 0 -> "&M=on"; else -> "&m=$m" }
            val s = get("https://www.hebcal.com/shabbat?cfg=json&${locationQuery(c)}$b$hav&leyning=off&lg=he")
            parseShabbat(JSONObject(s))?.let { _shabbat.value = it; p.shabbatCache = s }
        }
        if (_day.value?.date == today) p.zmanimKey = key
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "GlassLauncher")
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun placeName(o: JSONObject): String {
        val loc = o.optJSONObject("location") ?: return city().name
        return loc.optString("title").ifEmpty { loc.optString("name") }.ifEmpty { city().name }
    }

    private fun parseDay(o: JSONObject): DayTimes? {
        val times = o.optJSONObject("times") ?: return null
        val list = fields.mapNotNull { (k, t) ->
            times.optString(k).takeIf { it.isNotEmpty() }?.let { runCatching { Zman(t, OffsetDateTime.parse(it).atZoneSameInstant(zone)) }.getOrNull() }
        }
        if (list.isEmpty()) return null
        val date = runCatching { LocalDate.parse(o.optString("date").take(10)) }.getOrElse { list.first().time.toLocalDate() }
        return DayTimes(date, placeName(o), list)
    }

    private fun parseShabbat(o: JSONObject): ShabbatTimes? {
        val items = o.optJSONArray("items") ?: return null
        var candles: ZonedDateTime? = null
        var havdalah: ZonedDateTime? = null
        var parasha: String? = null
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            val d = it.optString("date")
            fun t() = runCatching { OffsetDateTime.parse(d).atZoneSameInstant(zone) }.getOrNull()
            when (it.optString("category")) {
                "candles" -> if (candles == null) candles = t()
                "havdalah" -> if (havdalah == null) havdalah = t()
                "parashat" -> if (parasha == null) parasha = it.optString("hebrew").ifEmpty { it.optString("title") }
            }
        }
        if (candles == null && havdalah == null) return null
        return ShabbatTimes(candles, havdalah, parasha, placeName(o))
    }

    const val YESHIVA_URL = "https://www.yeshiva.org.il/calendar/timesday"
}
