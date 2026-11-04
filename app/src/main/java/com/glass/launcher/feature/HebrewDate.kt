package com.glass.launcher.feature

import android.icu.util.HebrewCalendar
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.GregorianCalendar
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

data class City(val name: String, val lat: Double, val lon: Double)

val Cities = listOf(
    City("ירושלים", 31.778, 35.235),
    City("בני ברק", 32.084, 34.834),
    City("תל אביב", 32.085, 34.781),
    City("חיפה", 32.794, 34.990),
    City("אשדוד", 31.804, 34.655),
    City("בית שמש", 31.747, 34.988),
    City("ביתר עילית", 31.698, 35.115),
    City("מודיעין עילית", 31.933, 35.041),
    City("אלעד", 32.052, 34.951),
    City("נתניה", 32.321, 34.853),
    City("פתח תקווה", 32.087, 34.887),
    City("צפת", 32.965, 35.496),
    City("טבריה", 32.795, 35.531),
    City("באר שבע", 31.252, 34.791),
)

object HebrewDate {
    private val months = listOf(
        "תשרי", "חשוון", "כסלו", "טבת", "שבט", "אדר א׳", "אדר", "ניסן", "אייר", "סיוון", "תמוז", "אב", "אלול",
    )

    private fun isLeap(year: Int) = ((7 * year + 1) % 19) < 7

    /** Hebrew date for the given moment. After sunset the date moves on to the next day. */
    fun format(now: ZonedDateTime, afterSunset: Boolean): String {
        val greg = GregorianCalendar.from(now)
        val hc = HebrewCalendar()
        hc.timeInMillis = greg.timeInMillis
        if (afterSunset) hc.add(Calendar.DATE, 1)
        val day = hc.get(Calendar.DAY_OF_MONTH)
        val m = hc.get(Calendar.MONTH)
        val y = hc.get(Calendar.YEAR)
        val month = if (m == 6 && isLeap(y)) "אדר ב׳" else months[m]
        return "${gematria(day)} $month ${gematria(y)}"
    }

    fun gematria(n0: Int): String {
        var n = n0 % 1000
        val sb = StringBuilder()
        while (n >= 400) { sb.append('ת'); n -= 400 }
        if (n >= 300) { sb.append('ש'); n -= 300 }
        if (n >= 200) { sb.append('ר'); n -= 200 }
        if (n >= 100) { sb.append('ק'); n -= 100 }
        when (n) {
            15 -> { sb.append("טו"); n = 0 }
            16 -> { sb.append("טז"); n = 0 }
        }
        val tens = "יכלמנסעפצ"
        if (n >= 10) { sb.append(tens[n / 10 - 1]); n %= 10 }
        if (n > 0) sb.append("אבגדהוזחט"[n - 1])
        return if (sb.length == 1) "$sb׳" else sb.insert(sb.length - 1, '״').toString()
    }
}

/** Astronomical sunrise and sunset (standard NOAA approximation). */
object SunTimes {
    private const val ZENITH = 90.833

    fun of(date: LocalDate, city: City, zone: ZoneId): Pair<LocalTime?, LocalTime?> =
        calc(date, city, zone, true) to calc(date, city, zone, false)

    private fun calc(date: LocalDate, c: City, zone: ZoneId, rising: Boolean): LocalTime? {
        val n = date.dayOfYear
        val lngHour = c.lon / 15.0
        val t = n + ((if (rising) 6.0 else 18.0) - lngHour) / 24.0
        val m = 0.9856 * t - 3.289
        var l = m + 1.916 * sin(rad(m)) + 0.020 * sin(rad(2 * m)) + 282.634
        l = norm(l, 360.0)
        var ra = deg(atan(0.91764 * tan(rad(l))))
        ra = norm(ra, 360.0)
        ra += floor(l / 90) * 90 - floor(ra / 90) * 90
        ra /= 15.0
        val sinDec = 0.39782 * sin(rad(l))
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(rad(ZENITH)) - sinDec * sin(rad(c.lat))) / (cosDec * cos(rad(c.lat)))
        if (cosH > 1 || cosH < -1) return null
        val h = (if (rising) 360 - deg(acos(cosH)) else deg(acos(cosH))) / 15.0
        val tt = h + ra - 0.06571 * t - 6.622
        val ut = norm(tt - lngHour, 24.0)
        val secs = (ut * 3600).toLong()
        val utc = date.atStartOfDay(ZoneId.of("UTC")).plusSeconds(secs)
        return utc.withZoneSameInstant(zone).toLocalTime().withNano(0)
    }

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun norm(v: Double, m: Double): Double { var x = v % m; if (x < 0) x += m; return x }
}
