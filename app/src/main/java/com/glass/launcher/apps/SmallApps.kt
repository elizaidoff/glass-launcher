package com.glass.launcher.apps

import android.content.Intent
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.glass.launcher.App
import com.glass.launcher.feature.Cities
import com.glass.launcher.feature.HebrewDate
import com.glass.launcher.ui.KitText
import com.glass.launcher.ui.hideSystemNav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

/** Base for the small companion apps: edge-to-edge, right-to-left, the kit font, Android buttons hidden. */
abstract class GlassApp : ComponentActivity() {
    @Composable abstract fun Content()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { KitText { Content() } } }
    }
    override fun onResume() { super.onResume(); hideSystemNav() }
}

private val orange = Color(0xFFFF9F0A)
private val gray = Color(0xFF8E8E93)

// =============================================================== Calculator

class CalculatorActivity : GlassApp() {
    @Composable override fun Content() = Calculator()
}

@Composable
private fun Calculator() {
    var display by remember { mutableStateOf("0") }
    var acc by remember { mutableStateOf<Double?>(null) }
    var op by remember { mutableStateOf<Char?>(null) }
    var fresh by remember { mutableStateOf(true) }
    fun fmt(v: Double): String = if (v.isNaN() || v.isInfinite()) "שגיאה"
        else if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) "%,d".format(v.toLong())
        else "%.10g".format(java.util.Locale.US, v).trimEnd('0').trimEnd('.')
    fun value() = display.replace(",", "").toDoubleOrNull() ?: 0.0
    fun apply(a: Double, b: Double, o: Char) = when (o) { '+' -> a + b; '−' -> a - b; '×' -> a * b; '÷' -> a / b; else -> b }
    fun press(k: String) {
        when (k) {
            "AC", "C" -> { if (k == "AC") { acc = null; op = null }; display = "0"; fresh = true }
            "±" -> display = fmt(-value())
            "%" -> display = fmt(value() / 100)
            "+", "−", "×", "÷" -> {
                val a = acc
                if (a != null && op != null && !fresh) { val r = apply(a, value(), op!!); acc = r; display = fmt(r) } else acc = value()
                op = k[0]; fresh = true
            }
            "=" -> { val a = acc; val o = op; if (a != null && o != null) { display = fmt(apply(a, value(), o)); acc = null; op = null; fresh = true } }
            "." -> { if (fresh) { display = "0."; fresh = false } else if (!display.contains('.')) display += "." }
            else -> {
                if (fresh || display == "0") { display = k; fresh = false }
                else if (display.replace(",", "").length < 9) {
                    val raw = display.replace(",", "") + k
                    display = if (raw.contains('.')) raw else "%,d".format(raw.toLong())
                }
            }
        }
    }
    val rows = listOf(
        listOf(if (display == "0") "AC" else "C", "±", "%", "÷"),
        listOf("7", "8", "9", "×"), listOf("4", "5", "6", "−"), listOf("1", "2", "3", "+"), listOf("0", ".", "="),
    )
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(Modifier.fillMaxSize().background(Color.Black).navigationBarsPadding().padding(14.dp), verticalArrangement = Arrangement.Bottom) {
            Text(display, color = Color.White, fontSize = if (display.length > 7) 64.sp else 88.sp, fontWeight = FontWeight.Light,
                textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp))
            Spacer(Modifier.height(8.dp))
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    r.forEach { k ->
                        val isOp = k in listOf("÷", "×", "−", "+", "=")
                        val top = k in listOf("AC", "C", "±", "%")
                        val selected = isOp && op?.toString() == k && fresh
                        Box(
                            Modifier.weight(if (k == "0") 2.1f else 1f).aspectRatio(if (k == "0") 2.1f else 1f).clip(RoundedCornerShape(50))
                                .background(when { selected -> Color.White; isOp -> orange; top -> Color(0xFFA5A5A5); else -> Color(0xFF333333) })
                                .clickable { press(k) },
                            contentAlignment = if (k == "0") Alignment.CenterStart else Alignment.Center,
                        ) {
                            Text(k, color = when { selected -> orange; top -> Color.Black; else -> Color.White }, fontSize = 36.sp,
                                modifier = if (k == "0") Modifier.padding(start = 30.dp) else Modifier)
                        }
                    }
                }
            }
        }
    }
}

// =============================================================== Clock

class ClockActivity : GlassApp() {
    @Composable override fun Content() = ClockApp()
}

@Composable
private fun ClockApp() {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> WorldClock()
                1 -> Alarms()
                2 -> Stopwatch()
                else -> Timer()
            }
        }
        Row(Modifier.fillMaxWidth().background(Color(0xF0161618)).navigationBarsPadding().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("שעון עולמי", "מעורר", "סטופר", "טיימר").forEachIndexed { i, t ->
                Text(t, color = if (tab == i) orange else gray, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { tab = i }.padding(8.dp))
            }
        }
    }
}

@Composable
private fun Title(t: String) = Text(t, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.statusBarsPadding().padding(start = 16.dp, top = 30.dp, bottom = 8.dp))

private val worldZones = listOf("Asia/Jerusalem" to "ירושלים", "America/New_York" to "ניו יורק", "Europe/London" to "לונדון", "Europe/Paris" to "פריז", "America/Los_Angeles" to "לוס אנג'לס", "Australia/Sydney" to "סידני")

@Composable
private fun WorldClock() {
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = ZonedDateTime.now() } }
    LazyColumn {
        item { Title("שעון עולמי") }
        items(worldZones) { (z, name) ->
            val t = now.withZoneSameInstant(ZoneId.of(z))
            val diff = (t.offset.totalSeconds - now.offset.totalSeconds) / 3600
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (diff == 0) "היום, ‎+0 שע'" else "היום, ${if (diff > 0) "+" else ""}$diff שע'", color = gray, fontSize = 13.sp)
                    Text(name, color = Color.White, fontSize = 24.sp)
                }
                Text(t.format(DateTimeFormatter.ofPattern("HH:mm")), color = Color.White, fontSize = 50.sp, fontWeight = FontWeight.Light)
            }
            Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(Color(0xFF38383A)))
        }
    }
}

@Composable
private fun Alarms() {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Title("מעורר")
        Text("ההתראות עצמן מצלצלות דרך השעון של הטלפון, כך שהן אמינות גם כשהטלפון כבוי מסך.", color = gray, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
        listOf(6 to 0, 6 to 30, 7 to 0, 7 to 30).forEach { (h, m) ->
            Row(Modifier.fillMaxWidth().clickable {
                runCatching { ctx.startActivity(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m).putExtra(AlarmClock.EXTRA_SKIP_UI, true)) }
                android.widget.Toast.makeText(ctx, "נקבע מעורר ל-%d:%02d".format(h, m), android.widget.Toast.LENGTH_SHORT).show()
            }.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text("%d:%02d".format(h, m), color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Light, modifier = Modifier.weight(1f))
                Text("הוספה", color = orange, fontSize = 17.sp, modifier = Modifier.align(Alignment.CenterVertically))
            }
        }
        Text("כל המעוררים", color = orange, fontSize = 17.sp, modifier = Modifier.clickable {
            runCatching { ctx.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
        }.padding(16.dp))
    }
}

@Composable
private fun RoundBtn(text: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(Modifier.size(86.dp).clip(CircleShape).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = fg, fontSize = 17.sp)
    }
}

@Composable
private fun Stopwatch() {
    var running by remember { mutableStateOf(false) }
    var base by remember { mutableLongStateOf(0L) }
    var acc by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(0L) }
    val laps = remember { mutableStateListOf<Long>() }
    LaunchedEffect(running) { while (running) { now = System.currentTimeMillis(); delay(30) } }
    val elapsed = acc + if (running) now - base else 0L
    fun fmt(ms: Long) = "%02d:%02d,%02d".format(ms / 60000, ms / 1000 % 60, ms / 10 % 100)
    Column(Modifier.fillMaxSize().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(90.dp))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(fmt(elapsed), color = Color.White, fontSize = 76.sp, fontWeight = FontWeight.Thin)
        }
        Spacer(Modifier.height(40.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            RoundBtn(if (running) "הקפה" else "איפוס", Color(0xFF333333), Color.White) {
                if (running) laps.add(0, elapsed) else { acc = 0; laps.clear() }
            }
            RoundBtn(if (running) "עצירה" else "התחלה", if (running) Color(0xFF3A1210) else Color(0xFF0F3A1A), if (running) Color(0xFFFF453A) else Color(0xFF30D158)) {
                if (running) { acc += System.currentTimeMillis() - base; running = false } else { base = System.currentTimeMillis(); now = base; running = true }
            }
        }
        LazyColumn(Modifier.padding(16.dp)) {
            items(laps.size) { i ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Text("הקפה ${laps.size - i}", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    Text(fmt(laps[i]), color = Color.White, fontSize = 17.sp)
                }
            }
        }
    }
}

@Composable
private fun Timer() {
    val ctx = LocalContext.current
    var minutes by remember { mutableIntStateOf(5) }
    Column(Modifier.fillMaxSize().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Title("טיימר")
        Spacer(Modifier.height(40.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("−", color = orange, fontSize = 48.sp, modifier = Modifier.clickable { minutes = (minutes - 1).coerceAtLeast(1) }.padding(20.dp))
            Text("$minutes דק'", color = Color.White, fontSize = 60.sp, fontWeight = FontWeight.Light)
            Text("+", color = orange, fontSize = 48.sp, modifier = Modifier.clickable { minutes = (minutes + 1).coerceAtMost(600) }.padding(20.dp))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(16.dp)) {
            items(listOf(1, 3, 5, 10, 15, 30, 60)) { m ->
                Text("$m", color = Color.White, fontSize = 17.sp, modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF333333)).clickable { minutes = m }.padding(horizontal = 18.dp, vertical = 10.dp))
            }
        }
        Spacer(Modifier.height(30.dp))
        RoundBtn("התחלה", Color(0xFF0F3A1A), Color(0xFF30D158)) {
            runCatching { ctx.startActivity(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60).putExtra(AlarmClock.EXTRA_SKIP_UI, true)) }
            android.widget.Toast.makeText(ctx, "הטיימר רץ – $minutes דקות", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

// =============================================================== Weather

class WeatherActivity : GlassApp() {
    @Composable override fun Content() = Weather()
}

private data class Wx(val temp: Int, val code: Int, val hi: Int, val lo: Int, val hours: List<Triple<Int, Int, Int>>, val days: List<Triple<LocalDate, Int, Pair<Int, Int>>>)

private fun wxName(c: Int) = when (c) {
    0 -> "בהיר"; 1, 2 -> "מעונן חלקית"; 3 -> "מעונן"; 45, 48 -> "ערפל"; in 51..57 -> "טפטוף"; in 61..67 -> "גשם"; in 71..77 -> "שלג"
    in 80..82 -> "ממטרים"; in 95..99 -> "סופת רעמים"; else -> ""
}
private fun wxIcon(c: Int, night: Boolean = false) = when (c) {
    0 -> if (night) "🌙" else "☀️"; 1, 2 -> "⛅"; 3 -> "☁️"; 45, 48 -> "🌫️"; in 51..67, in 80..82 -> "🌧️"; in 71..77 -> "❄️"; in 95..99 -> "⛈️"; else -> "☁️"
}

private fun fetchWeather(lat: Double, lon: Double): Wx? = runCatching {
    val url = java.net.URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&hourly=temperature_2m,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=10")
    val o = org.json.JSONObject(url.readText())
    val cur = o.getJSONObject("current")
    val h = o.getJSONObject("hourly"); val ht = h.getJSONArray("time"); val htemp = h.getJSONArray("temperature_2m"); val hc = h.getJSONArray("weather_code")
    val nowH = java.time.LocalDateTime.now().withMinute(0).withSecond(0).withNano(0)
    val hours = (0 until ht.length()).map { i -> java.time.LocalDateTime.parse(ht.getString(i)) to i }
        .filter { !it.first.isBefore(nowH) }.take(24).map { (t, i) -> Triple(t.hour, htemp.getDouble(i).toInt(), hc.getInt(i)) }
    val d = o.getJSONObject("daily"); val dt = d.getJSONArray("time")
    val days = (0 until dt.length()).map { i ->
        Triple(LocalDate.parse(dt.getString(i)), d.getJSONArray("weather_code").getInt(i), d.getJSONArray("temperature_2m_min").getDouble(i).toInt() to d.getJSONArray("temperature_2m_max").getDouble(i).toInt())
    }
    Wx(cur.getDouble("temperature_2m").toInt(), cur.getInt("weather_code"), days.first().third.second, days.first().third.first, hours, days)
}.getOrNull()

@Composable
private fun Weather() {
    val city = Cities.getOrElse(App.instance.prefs.cityIndex) { Cities[0] }
    val wx by produceState<Wx?>(null) { value = withContext(Dispatchers.IO) { fetchWeather(city.lat, city.lon) } }
    val night = LocalTime.now().hour !in 6..18
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(if (night) listOf(Color(0xFF0B1A3A), Color(0xFF223566)) else listOf(Color(0xFF3D8FE0), Color(0xFF7AB8F0))))) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally, contentPadding = PaddingValues(16.dp)) {
            item {
                Spacer(Modifier.height(30.dp))
                Text(city.name, color = Color.White, fontSize = 34.sp)
                val w = wx
                if (w == null) { Text("טוען…", color = Color.White.copy(alpha = 0.8f), fontSize = 18.sp, modifier = Modifier.padding(30.dp)); return@item }
                Text("${w.temp}°", color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Thin)
                Text(wxName(w.code), color = Color.White, fontSize = 20.sp)
                Text("ג׳:${w.hi}°  נ׳:${w.lo}°", color = Color.White, fontSize = 20.sp)
                Spacer(Modifier.height(20.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.15f)).padding(12.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        items(w.hours) { (hr, t, c) ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (hr == LocalTime.now().hour) "עכשיו" else "$hr", color = Color.White, fontSize = 14.sp)
                                Text(wxIcon(c, hr !in 6..18), fontSize = 22.sp, modifier = Modifier.padding(vertical = 6.dp))
                                Text("$t°", color = Color.White, fontSize = 17.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.15f)).padding(14.dp)) {
                    Text("תחזית ל-10 ימים", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    val gMin = w.days.minOf { it.third.first }; val gMax = w.days.maxOf { it.third.second }
                    w.days.forEachIndexed { i, (d, c, mm) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (i == 0) "היום" else hebDay(d.dayOfWeek.value), color = Color.White, fontSize = 17.sp, modifier = Modifier.width(70.dp))
                            Text(wxIcon(c), fontSize = 20.sp, modifier = Modifier.width(36.dp))
                            Text("${mm.first}°", color = Color.White.copy(alpha = 0.7f), fontSize = 17.sp, modifier = Modifier.width(40.dp))
                            Canvas(Modifier.weight(1f).height(6.dp)) {
                                val span = (gMax - gMin).coerceAtLeast(1)
                                val a = (mm.first - gMin) / span.toFloat() * size.width; val b = (mm.second - gMin) / span.toFloat() * size.width
                                drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height, StrokeCap.Round)
                                drawLine(Brush.horizontalGradient(listOf(Color(0xFF7DD3FC), Color(0xFFFFD60A))), Offset(size.width - b, size.height / 2), Offset(size.width - a, size.height / 2), size.height, StrokeCap.Round)
                            }
                            Text("${mm.second}°", color = Color.White, fontSize = 17.sp, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                Text("נתונים: Open-Meteo · העיר נקבעת בהגדרות זכוכית ← זמני היום", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

private fun hebDay(dow: Int) = when (dow) { 7 -> "ראשון"; 1 -> "שני"; 2 -> "שלישי"; 3 -> "רביעי"; 4 -> "חמישי"; 5 -> "שישי"; else -> "שבת" }

// =============================================================== Calendar

class CalendarActivity : GlassApp() {
    @Composable override fun Content() = CalendarApp()
}

private data class Ev(val title: String, val begin: Long, val allDay: Boolean)

private fun events(ctx: android.content.Context, day: LocalDate): List<Ev> {
    if (ctx.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) return emptyList()
    val z = ZoneId.systemDefault()
    val b = day.atStartOfDay(z).toInstant().toEpochMilli(); val e = day.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { android.content.ContentUris.appendId(it, b); android.content.ContentUris.appendId(it, e) }.build()
    val out = ArrayList<Ev>()
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY), null, null, CalendarContract.Instances.BEGIN)?.use { c ->
            while (c.moveToNext()) out += Ev(c.getString(0) ?: "", c.getLong(1), c.getInt(2) == 1)
        }
    }
    return out
}

@Composable
private fun CalendarApp() {
    val ctx = LocalContext.current
    var month by remember { mutableStateOf(LocalDate.now().withDayOfMonth(1)) }
    var sel by remember { mutableStateOf(LocalDate.now()) }
    var granted by remember { mutableStateOf(ctx.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted = it }
    val evs by produceState(emptyList<Ev>(), sel, granted) { value = withContext(Dispatchers.IO) { events(ctx, sel) } }
    val red = Color(0xFFFF3B30)
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val bg = if (dark) Color.Black else Color.White; val fg = if (dark) Color.White else Color.Black
    Column(Modifier.fillMaxSize().background(bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale("he"))), color = fg, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(HebrewDate.format(month.plusDays(14).atStartOfDay(ZoneId.systemDefault()).withHour(12), false).substringAfter(' '), color = gray, fontSize = 14.sp)
            }
            Text("‹", color = red, fontSize = 34.sp, modifier = Modifier.clickable { month = month.minusMonths(1) }.padding(horizontal = 12.dp))
            Text("היום", color = red, fontSize = 17.sp, modifier = Modifier.clickable { month = LocalDate.now().withDayOfMonth(1); sel = LocalDate.now() })
            Text("›", color = red, fontSize = 34.sp, modifier = Modifier.clickable { month = month.plusMonths(1) }.padding(horizontal = 12.dp))
        }
        Row(Modifier.fillMaxWidth()) { listOf("א", "ב", "ג", "ד", "ה", "ו", "ש").forEach { Text(it, color = gray, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) } }
        val lead = month.dayOfWeek.value % 7
        val days = month.lengthOfMonth()
        (0 until 6).forEach { w ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                (0 until 7).forEach { d ->
                    val n = w * 7 + d - lead + 1
                    Box(Modifier.weight(1f).height(54.dp), contentAlignment = Alignment.Center) {
                        if (n in 1..days) {
                            val date = month.withDayOfMonth(n)
                            val isSel = date == sel; val isToday = date == LocalDate.now()
                            Column(Modifier.clip(CircleShape).clickable { sel = date }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(32.dp).clip(CircleShape).background(if (isSel) (if (isToday) red else fg) else Color.Transparent), contentAlignment = Alignment.Center) {
                                    Text("$n", color = if (isSel) bg else if (isToday) red else if (d == 6) gray else fg, fontSize = 18.sp)
                                }
                                Text(HebrewDate.gematria(hebDayOfMonth(date)), color = gray, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFF38383A)))
        Text(sel.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", java.util.Locale("he"))) + " · " + HebrewDate.format(sel.atStartOfDay(ZoneId.systemDefault()).withHour(12), false),
            color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(16.dp))
        if (!granted) Text("הצגת אירועים מהיומן", color = red, fontSize = 16.sp, modifier = Modifier.clickable { ask.launch(android.Manifest.permission.READ_CALENDAR) }.padding(horizontal = 16.dp))
        LazyColumn(Modifier.weight(1f)) {
            if (granted && evs.isEmpty()) item { Text("אין אירועים", color = gray, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
            items(evs) { e ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Box(Modifier.width(4.dp).height(40.dp).clip(RoundedCornerShape(2.dp)).background(red))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(e.title, color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (e.allDay) "כל היום" else java.time.Instant.ofEpochMilli(e.begin).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")), color = gray, fontSize = 14.sp)
                    }
                }
            }
        }
        Text("+ אירוע חדש", color = red, fontSize = 17.sp, modifier = Modifier.clickable {
            runCatching {
                ctx.startActivity(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, sel.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()))
            }
        }.padding(16.dp))
    }
}

private fun hebDayOfMonth(d: LocalDate): Int {
    val hc = android.icu.util.HebrewCalendar()
    hc.timeInMillis = d.atStartOfDay(ZoneId.systemDefault()).withHour(12).toInstant().toEpochMilli()
    return hc.get(android.icu.util.Calendar.DAY_OF_MONTH)
}

@Suppress("unused") private fun pt(r: Float, a: Double) = Offset((r * sin(a)).toFloat(), (-r * cos(a)).toFloat())
