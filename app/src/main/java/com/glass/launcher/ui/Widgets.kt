@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.glass.launcher.ui

import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.util.SizeF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.glass.launcher.App
import com.glass.launcher.data.HomeGrid
import com.glass.launcher.data.HomeGrid.Size
import com.glass.launcher.feature.AirPods
import com.glass.launcher.feature.Cities
import com.glass.launcher.feature.HebrewDate
import com.glass.launcher.feature.SunTimes
import com.glass.launcher.feature.SystemActions
import kotlinx.coroutines.delay
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val He = Locale("he", "IL")

@Composable
fun rememberNow(): ZonedDateTime {
    val now by produceState(ZonedDateTime.now()) {
        while (true) {
            delay(1000L * (60 - ZonedDateTime.now().second).coerceAtLeast(1))
            value = ZonedDateTime.now()
        }
    }
    return now
}

val BuiltinWidgets = listOf(
    "builtin:clock" to "שעון ותאריך",
    "builtin:hebdate" to "תאריך עברי, זריחה ושקיעה",
    "builtin:battery" to "סוללות (טלפון ו-AirPods)",
    "builtin:airpods" to "AirPods",
    "builtin:zmanim" to "זמני היום",
    "builtin:shabbat" to "כניסת ויציאת שבת",
)

fun widgetTitle(key: String): String {
    BuiltinWidgets.firstOrNull { it.first == key }?.let { return it.second }
    val id = key.removePrefix("appwidget:").toIntOrNull() ?: return key
    val ctx = App.instance
    return AppWidgetManager.getInstance(ctx).getAppWidgetInfo(id)?.loadLabel(ctx.packageManager) ?: "ווידג'ט"
}

/** Widgets page ("Today view"). Small widgets sit two in a row, like on iPhone. */
@Composable
fun TodayView(onSearch: () -> Unit, onAdd: () -> Unit, onReplace: (String) -> Unit = {}) {
    val prefs = App.instance.prefs
    val tick by prefs.version.collectAsState()
    val list = remember(tick) { prefs.widgets }
    var menuFor by remember { mutableStateOf<String?>(null) }
    val rows = remember(list) {
        val out = mutableListOf<List<String>>()
        var pendingSmall: String? = null
        list.forEach { e ->
            if (todaySpan(e).first <= 2) {
                if (pendingSmall == null) pendingSmall = e else { out.add(listOf(pendingSmall!!, e)); pendingSmall = null }
            } else {
                pendingSmall?.let { out.add(listOf(it)); pendingSmall = null }
                out.add(listOf(e))
            }
        }
        pendingSmall?.let { out.add(listOf(it)) }
        out
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 40.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().glass(RoundedCornerShape(14.dp), 0.9f).tap(onSearch).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("חיפוש", color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
            }
        }
        items(rows, key = { it.joinToString() }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { e ->
                    val (key, size) = HomeGrid.parse(e)
                    val (sw, sh) = todaySpan(e)
                    val mod = if (sw <= 2) Modifier.weight(1f).aspectRatio(2f / sh)
                    else Modifier.fillMaxWidth().aspectRatio(4f / sh)
                    WidgetCard(key, size, mod, onLongPress = { menuFor = e })
                }
                if (row.size == 1 && todaySpan(row[0]).first <= 2) Spacer(Modifier.weight(1f))
            }
        }
        item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Pill("הוספת ווידג'ט", onAdd) }
        }
    }
    menuFor?.let { e ->
        val (key, size) = HomeGrid.parse(e)
        GlassMenu(
            Size.entries.filter { it != size }.map { s ->
                MenuItem("גודל: ${s.label}") {
                    prefs.widgets = prefs.widgets.map { if (it == e) HomeGrid.entry(key, s) else it }
                }
            } + listOf(
                MenuItem("הזזה למעלה") {
                    val l = prefs.widgets.toMutableList(); val i = l.indexOf(e)
                    if (i > 0) { l.add(i - 1, l.removeAt(i)); prefs.widgets = l }
                },
                MenuItem("הזזה למטה") {
                    val l = prefs.widgets.toMutableList(); val i = l.indexOf(e)
                    if (i in 0 until l.lastIndex) { l.add(i + 1, l.removeAt(i)); prefs.widgets = l }
                },
                MenuItem("החלפת התוכן (אותו גודל ומקום)") { onReplace(e) },
                MenuItem("הסרה", danger = true) { removeWidgetEverywhere(e, fromToday = true) },
            ),
            onDismiss = { menuFor = null },
        )
    }
}

/** Size of a widget on the widgets page: width 2 = half, 4 = full; height in half-width units. */
fun todaySpan(e: String): Pair<Int, Int> {
    HomeGrid.customSpan(e)?.let { return (if (it.first <= 2) 2 else 4) to it.second.coerceIn(1, 6) }
    return when (HomeGrid.parse(e).second) { Size.S -> 2 to 2; Size.M -> 4 to 2; Size.L -> 4 to 4 }
}

/** New content, same size and place: keeps the size part ("|M", "|3x2") of the old entry. */
fun replaceWidgetKey(old: String, newKey: String): String {
    val token = old.removePrefix("w:").substringAfterLast('|', "M")
    return (if (old.startsWith("w:")) "w:" else "") + newKey + "|" + token
}

/** Drops an Android widget id when nothing uses it any more. */
fun removeWidgetEverywhere(entry: String, fromToday: Boolean) {
    val p = App.instance.prefs
    if (fromToday) p.widgets = p.widgets - entry
    val key = HomeGrid.parse(entry).first
    val stillUsed = p.widgets.any { HomeGrid.parse(it).first == key } ||
        p.homePages.flatten().any { it != null && HomeGrid.isWidget(it) && HomeGrid.parse(it).first == key }
    if (!stillUsed) key.removePrefix("appwidget:").toIntOrNull()?.let { App.instance.widgetHost.deleteAppWidgetId(it) }
}

/** One widget inside its glass card, at the given size. */
@Composable
fun WidgetCard(key: String, size: Size, modifier: Modifier, onLongPress: () -> Unit) {
    val now = rememberNow()
    Box(
        modifier.glass(RoundedCornerShape(26.dp))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = onLongPress,
            )
    ) {
        when {
            key == "builtin:clock" -> ClockWidget(now, size)
            key == "builtin:hebdate" -> HebrewDateWidget(now, size)
            key == "builtin:battery" -> BatteryWidget(now, size)
            key == "builtin:airpods" -> AirPodsWidget(size)
            key == "builtin:zmanim" -> ZmanimWidget(now, size)
            key == "builtin:shabbat" -> ShabbatWidget(size)
            key.startsWith("appwidget:") -> key.removePrefix("appwidget:").toIntOrNull()?.let { HostedWidget(it) }
        }
    }
}

@Composable
private fun Pad(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize().padding(16.dp)) { content() }

@Composable
private fun ClockWidget(now: ZonedDateTime, size: Size) = Pad {
    val big: TextUnit = when (size) { Size.S -> 40.sp; Size.M -> 56.sp; Size.L -> 76.sp }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), color = Color.White, fontSize = big, fontWeight = FontWeight.Light)
        Text(
            now.format(DateTimeFormatter.ofPattern(if (size == Size.S) "EEE, d 'ב'MMM" else "EEEE, d 'ב'MMMM", He)),
            color = Color.White.copy(alpha = 0.85f), fontSize = if (size == Size.S) 13.sp else 17.sp, maxLines = 1,
        )
        if (size == Size.L) {
            Spacer(Modifier.height(14.dp))
            HebrewLine(now, 20.sp)
        }
    }
}

@Composable
private fun HebrewLine(now: ZonedDateTime, fs: TextUnit) {
    val city = Cities.getOrElse(App.instance.prefs.cityIndex) { Cities[0] }
    val (_, set) = remember(now.toLocalDate(), city) { SunTimes.of(now.toLocalDate(), city, now.zone) }
    val after = set != null && now.toLocalTime().isAfter(set)
    Text(HebrewDate.format(now, after), color = Color.White, fontSize = fs, fontWeight = FontWeight.SemiBold, maxLines = 1)
}

@Composable
private fun HebrewDateWidget(now: ZonedDateTime, size: Size) = Pad {
    val city = Cities.getOrElse(App.instance.prefs.cityIndex) { Cities[0] }
    val (rise, set) = remember(now.toLocalDate(), city) { SunTimes.of(now.toLocalDate(), city, now.zone) }
    val f = DateTimeFormatter.ofPattern("HH:mm")
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        HebrewLine(now, if (size == Size.S) 18.sp else 26.sp)
        Spacer(Modifier.height(8.dp))
        if (size == Size.S) {
            rise?.let { Text("זריחה ${it.format(f)}", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp) }
            set?.let { Text("שקיעה ${it.format(f)}", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp) }
        } else {
            Text(
                listOfNotNull(rise?.let { "זריחה ${it.format(f)}" }, set?.let { "שקיעה ${it.format(f)}" }, city.name).joinToString("  ·  "),
                color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp,
            )
        }
    }
}

@Composable
private fun BatteryWidget(now: ZonedDateTime, size: Size) = Pad {
    val ctx = LocalContext.current
    val pods by AirPods.state.collectAsState()
    val p = AirPods.fresh(pods)
    val phone = remember(now) { SystemActions.batteryPercent(ctx) }
    val charging = remember(now) { SystemActions.batteryCharging(ctx) }
    val rings = buildList {
        add(Triple(phone, charging, Icons.Rounded.PhoneAndroid) to null)
        if (p != null) {
            p.left?.let { add(Triple(it, p.leftCharging, Icons.Rounded.Headphones) to "שמאל") }
            p.right?.let { add(Triple(it, p.rightCharging, Icons.Rounded.Headphones) to "ימין") }
            p.case?.let { add(Triple(it, p.caseCharging, Icons.Rounded.Work) to "מארז") }
        }
    }
    if (size == Size.S) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
            rings.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    pair.forEach { (t, l) -> BatteryRing(t.first, t.second, t.third, null, 46) }
                }
            }
        }
    } else {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            rings.forEach { (t, l) -> BatteryRing(t.first, t.second, t.third, l, if (size == Size.L) 72 else 60) }
        }
    }
}

@Composable
private fun AirPodsWidget(size: Size) = Pad {
    val pods by AirPods.state.collectAsState()
    val p = AirPods.fresh(pods)
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Headphones, null, tint = Color.White, modifier = Modifier.size(24.dp))
            Spacer(Modifier.size(8.dp))
            Text("AirPods", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))
        when {
            !AirPods.hasPermission(ctx) -> Text("צריך לאשר בלוטות' בהגדרות זכוכית", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
            p == null -> Text("פתח את המארז ליד הטלפון", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
            else -> Column(verticalArrangement = Arrangement.spacedBy(if (size == Size.S) 6.dp else 10.dp)) {
                p.left?.let { BarRow("שמאל", it, p.leftCharging) }
                p.right?.let { BarRow("ימין", it, p.rightCharging) }
                p.case?.let { BarRow("מארז", it, p.caseCharging) }
                if (!AirPods.isLive(p)) Text("נמדד ${AirPods.ageText(p)}", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            }
        }
    }
}

private fun levelColor(v: Int, charging: Boolean) = when {
    charging -> Color(0xFF30D158)
    v <= 20 -> Color(0xFFFF453A)
    else -> Color.White
}

@Composable
private fun BarRow(name: String, v: Int, charging: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = Color.White, fontSize = 14.sp, modifier = Modifier.size(width = 46.dp, height = 20.dp))
        Box(Modifier.weight(1f).height(9.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(Color.White.copy(alpha = 0.2f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
                drawRoundRect(
                    levelColor(v, charging),
                    size = size.copy(width = size.width * v / 100f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2),
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        if (charging) Icon(Icons.Rounded.Bolt, null, tint = Color(0xFF30D158), modifier = Modifier.size(14.dp))
        Text("$v%", color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun BatteryRing(v: Int, charging: Boolean, icon: ImageVector, label: String?, sizeDp: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(sizeDp.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 5.dp.toPx()
                drawArc(Color.White.copy(alpha = 0.2f), 0f, 360f, false, style = Stroke(w))
                drawArc(levelColor(v, charging), -90f, 360f * v / 100f, false, style = Stroke(w, cap = StrokeCap.Round))
            }
            Icon(if (charging) Icons.Rounded.Bolt else icon, null, tint = Color.White, modifier = Modifier.size((sizeDp * 0.4f).dp))
        }
        Spacer(Modifier.height(4.dp))
        Text("$v%", color = Color.White, fontSize = 13.sp)
        if (label != null) Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A real Android widget from another app, sized to its card. */
@Composable
private fun HostedWidget(id: Int) {
    val ctx = LocalContext.current
    val info = remember(id) { AppWidgetManager.getInstance(ctx).getAppWidgetInfo(id) } ?: return
    BoxWithConstraints(Modifier.fillMaxSize().padding(4.dp)) {
        val wDp = maxWidth.value
        val hDp = maxHeight.value
        AndroidView(
            factory = { c -> App.instance.widgetHost.createView(c, id, info).apply { setAppWidget(id, info) } },
            update = { v ->
                runCatching {
                    if (android.os.Build.VERSION.SDK_INT >= 31) v.updateAppWidgetSize(Bundle(), listOf(SizeF(wDp, hDp)))
                    else @Suppress("DEPRECATION") v.updateAppWidgetSize(null, wDp.toInt(), hDp.toInt(), wDp.toInt(), hDp.toInt())
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ZmanimWidget(now: ZonedDateTime, size: Size) = Pad {
    val day by com.glass.launcher.feature.Zmanim.day.collectAsState()
    androidx.compose.runtime.LaunchedEffect(now.toLocalDate()) { com.glass.launcher.feature.Zmanim.refresh() }
    val f = DateTimeFormatter.ofPattern("HH:mm")
    val d = day
    Column(Modifier.fillMaxSize()) {
        Text("זמני היום", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        if (d == null) {
            Text("מתחבר לאינטרנט…", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            return@Column
        }
        val list = when (size) {
            Size.S -> d.list.filter { it.title.startsWith("הנץ") || it.title.contains("גר״א") && it.title.contains("ק״ש") || it.title == "שקיעה" }
            Size.M -> d.list.filter { it.time.isAfter(now.minusMinutes(1)) }.take(4).ifEmpty { d.list.takeLast(4) }
            Size.L -> d.list
        }
        val next = d.list.firstOrNull { it.time.isAfter(now) }
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(if (size == Size.L) 3.dp else 5.dp)) {
            list.forEach { z ->
                val isNext = z == next
                Row(Modifier.fillMaxWidth()) {
                    Text(z.title, color = if (isNext) Color(0xFFFFD60A) else Color.White, fontSize = if (size == Size.S) 12.sp else 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(z.time.format(f), color = if (isNext) Color(0xFFFFD60A) else Color.White, fontSize = if (size == Size.S) 12.sp else 14.sp)
                }
            }
        }
        if (size != Size.S) {
            Spacer(Modifier.weight(1f))
            Text("לפי Hebcal · ${d.place}", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
private fun ShabbatWidget(size: Size) = Pad {
    val sh by com.glass.launcher.feature.Zmanim.shabbat.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { com.glass.launcher.feature.Zmanim.refresh() }
    val f = DateTimeFormatter.ofPattern("HH:mm")
    val dayF = DateTimeFormatter.ofPattern("EEEE", He)
    val s = sh
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(s?.parasha?.let { "שבת · $it" } ?: "שבת", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        if (s == null) {
            Text("מתחבר לאינטרנט…", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        } else {
            s.candles?.let {
                Text("הדלקת נרות", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                Text("${it.format(dayF)} ${it.format(f)}", color = Color(0xFFFFD60A), fontSize = if (size == Size.S) 20.sp else 26.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            s.havdalah?.let {
                Text("יציאת שבת", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                Text("${it.format(dayF)} ${it.format(f)}", color = Color.White, fontSize = if (size == Size.S) 20.sp else 26.sp, fontWeight = FontWeight.SemiBold)
            }
            if (size != Size.S) Text("לפי Hebcal · ${s.place}", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, maxLines = 1)
        }
    }
}
