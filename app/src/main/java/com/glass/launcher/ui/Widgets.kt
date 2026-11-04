@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.glass.launcher.ui

import androidx.compose.ui.graphics.graphicsLayer

import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.util.SizeF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
    "builtin:stack" to "ערימה חכמה",
    "builtin:rings" to "טבעות פעילות (כושר)",
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

private fun widgetBg(key: String, dark: Boolean, shape: androidx.compose.ui.graphics.Shape): Modifier = when (key) {
    "builtin:clock", "builtin:battery", "builtin:airpods" -> Modifier.background(Color(0xFF1C1C1E), shape)
    "builtin:rings" -> Modifier.background(Color.Black, shape)
    "builtin:hebdate" -> Modifier.background(if (dark) Color(0xFF1C1C1E) else Color.White, shape)
    "builtin:zmanim" -> Modifier.background(
        androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF4A90E2), Color(0xFF1F4E9C))), shape,
    )
    "builtin:shabbat" -> Modifier.background(
        androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF1B1F3B), Color(0xFF3B2C5E))), shape,
    )
    else -> if (key.startsWith("appwidget:") && !App.instance.prefs.appWidgetGlass) Modifier else Modifier.glass(shape)
}

@Composable
private fun WidgetContent(key: String, now: ZonedDateTime, size: Size, dark: Boolean) {
    when {
        key == "builtin:clock" -> ClockWidget(now, size)
        key == "builtin:hebdate" -> HebrewDateWidget(now, size, dark)
        key == "builtin:battery" -> BatteryWidget(now, size)
        key == "builtin:airpods" -> AirPodsWidget(size)
        key == "builtin:zmanim" -> ZmanimWidget(now, size)
        key == "builtin:shabbat" -> ShabbatWidget(size)
        key == "builtin:rings" -> RingsWidget(size)
        key.startsWith("appwidget:") -> key.removePrefix("appwidget:").toIntOrNull()?.let { HostedWidget(it) }
    }
}

/** Widgets in the Smart Stack, in order. */
val StackKeys = listOf("builtin:clock", "builtin:hebdate", "builtin:zmanim", "builtin:shabbat", "builtin:battery")

/** What the stack shows first, by the time: Friday afternoon & Shabbat → Shabbat times, morning → zmanim. */
private fun smartPick(now: ZonedDateTime): Int {
    val d = now.dayOfWeek
    val h = now.hour
    return when {
        (d == java.time.DayOfWeek.FRIDAY && h >= 10) || (d == java.time.DayOfWeek.SATURDAY) -> 3
        h in 4..9 -> 2
        h in 18..23 -> 1
        else -> 0
    }
}

/** iPhone's Smart Stack: several widgets in one place; swipe up and down, it also turns by itself to what fits now. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SmartStack(now: ZonedDateTime, size: Size, dark: Boolean, shape: androidx.compose.ui.graphics.Shape) {
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = smartPick(now)) { StackKeys.size }
    val pick = smartPick(now)
    androidx.compose.runtime.LaunchedEffect(pick) { if (!pager.isScrollInProgress) pager.animateScrollToPage(pick) }
    Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.pager.VerticalPager(pager, Modifier.fillMaxSize()) { i ->
            val k = StackKeys[i]
            Box(Modifier.fillMaxSize().clip(shape).then(widgetBg(k, dark, shape))) { WidgetContent(k, now, size, dark) }
        }
        // Dots on the side while it moves, like iPhone.
        val moving = pager.isScrollInProgress
        val a by androidx.compose.animation.core.animateFloatAsState(if (moving) 1f else 0f, label = "dots")
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 5.dp).graphicsLayer { alpha = a },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(StackKeys.size) { i ->
                Box(Modifier.size(5.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color.White.copy(alpha = if (i == pager.currentPage) 0.95f else 0.35f)))
            }
        }
    }
}

/** One widget inside its card, at the given size. Built-in widgets have their own Apple-like look; others sit on glass. */
@Composable
fun WidgetCard(key: String, size: Size, modifier: Modifier, onLongPress: () -> Unit) {
    val now = rememberNow()
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val shape = RoundedCornerShape(24.dp)
    val isStack = key == "builtin:stack"
    Box(
        modifier
            .then(if (key.startsWith("appwidget:") && !App.instance.prefs.appWidgetGlass) Modifier else Modifier.androidx_shadow(shape).clip(shape))
            .then(if (isStack) Modifier else widgetBg(key, dark, shape))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    if (key == "builtin:rings") runCatching {
                        App.instance.startActivity(android.content.Intent(App.instance, com.glass.launcher.fitness.FitnessActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
                onLongClick = onLongPress,
            )
    ) {
        if (isStack) SmartStack(now, size, dark, shape) else WidgetContent(key, now, size, dark)
    }
}

private fun Modifier.androidx_shadow(shape: androidx.compose.ui.graphics.Shape): Modifier =
    this.then(Modifier.shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.3f)))

@Composable
private fun Pad(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize().padding(16.dp)) { content() }

/** Apple-style clock: an analog dial (small and large), or a big digital time (medium). */
@Composable
private fun ClockWidget(now: ZonedDateTime, size: Size) {
    val style = App.instance.prefs.clockStyle
    if (style == "digital") {
        Pad {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), color = Color.White, fontSize = if (size == Size.S) 46.sp else 64.sp, fontWeight = FontWeight.Light)
                Text(now.format(DateTimeFormatter.ofPattern("EEEE, d 'ב'MMMM", He)), color = Color(0xFFFF9F0A), fontSize = 14.sp, maxLines = 1)
                HebrewLine(now, 14.sp, Color.White.copy(alpha = 0.8f))
            }
        }
        return
    }
    if (size == Size.M || (style == "both" && size != Size.S)) {
        Pad {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.fillMaxHeight().aspectRatio(1f)) { AnalogDial(now) }
                Spacer(Modifier.size(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Light)
                    Text(now.format(DateTimeFormatter.ofPattern("EEEE, d 'ב'MMMM", He)), color = Color(0xFFFF9F0A), fontSize = 14.sp, maxLines = 1)
                    HebrewLine(now, 14.sp, Color.White.copy(alpha = 0.8f))
                }
            }
        }
    } else {
        Box(Modifier.fillMaxSize().padding(if (size == Size.S) 12.dp else 22.dp)) { AnalogDial(now) }
    }
}

@Composable
private fun AnalogDial(now: ZonedDateTime) {
    Canvas(Modifier.fillMaxSize()) {
        val r = kotlin.math.min(this.size.width, this.size.height) / 2
        val c = androidx.compose.ui.geometry.Offset(this.size.width / 2, this.size.height / 2)
        drawCircle(Color(0xFFF2F2F7), r, c)
        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0 - 90)
            val major = i % 5 == 0
            val r1 = r * (if (major) 0.80f else 0.88f)
            val r2 = r * 0.94f
            drawLine(
                if (major) Color(0xFF1C1C1E) else Color(0xFF8E8E93),
                androidx.compose.ui.geometry.Offset(c.x + (r1 * kotlin.math.cos(a)).toFloat(), c.y + (r1 * kotlin.math.sin(a)).toFloat()),
                androidx.compose.ui.geometry.Offset(c.x + (r2 * kotlin.math.cos(a)).toFloat(), c.y + (r2 * kotlin.math.sin(a)).toFloat()),
                strokeWidth = if (major) r * 0.035f else r * 0.015f, cap = StrokeCap.Round,
            )
        }
        fun hand(deg: Double, len: Float, w: Float, col: Color) {
            val a = Math.toRadians(deg - 90)
            drawLine(col, c, androidx.compose.ui.geometry.Offset(c.x + (len * kotlin.math.cos(a)).toFloat(), c.y + (len * kotlin.math.sin(a)).toFloat()), w, StrokeCap.Round)
        }
        val m = now.minute
        val h = now.hour % 12
        hand(h * 30.0 + m * 0.5, r * 0.5f, r * 0.08f, Color(0xFF1C1C1E))
        hand(m * 6.0, r * 0.75f, r * 0.055f, Color(0xFF1C1C1E))
        drawCircle(Color(0xFFFF9F0A), r * 0.06f, c)
        drawCircle(Color(0xFFF2F2F7), r * 0.025f, c)
    }
}

@Composable
private fun HebrewLine(now: ZonedDateTime, fs: TextUnit, color: Color = Color.White) {
    val city = Cities.getOrElse(App.instance.prefs.cityIndex) { Cities[0] }
    val (_, set) = remember(now.toLocalDate(), city) { SunTimes.of(now.toLocalDate(), city, now.zone) }
    val after = set != null && now.toLocalTime().isAfter(set)
    Text(HebrewDate.format(now, after), color = color, fontSize = fs, fontWeight = FontWeight.SemiBold, maxLines = 1)
}

/** Like Apple's Calendar widget: red weekday, the big (Hebrew) day, month and year. */
@Composable
private fun HebrewDateWidget(now: ZonedDateTime, size: Size, dark: Boolean) = Pad {
    val city = Cities.getOrElse(App.instance.prefs.cityIndex) { Cities[0] }
    val (rise, set) = remember(now.toLocalDate(), city) { SunTimes.of(now.toLocalDate(), city, now.zone) }
    val after = set != null && now.toLocalTime().isAfter(set)
    val full = HebrewDate.format(now, after)
    val parts = full.split(' ')
    val ink = if (dark) Color.White else Color.Black
    val sub = if (dark) Color(0xFF8E8E93) else Color(0xFF6C6C70)
    val f = DateTimeFormatter.ofPattern("HH:mm")
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f)) {
            Text(now.format(DateTimeFormatter.ofPattern("EEEE", He)).removePrefix("יום "), color = Color(0xFFFF3B30), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(parts.firstOrNull() ?: "", color = ink, fontSize = if (size == Size.S) 46.sp else 54.sp, fontWeight = FontWeight.Light, maxLines = 1)
            Text(parts.drop(1).joinToString(" "), color = ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text(now.format(DateTimeFormatter.ofPattern("d 'ב'MMMM", He)), color = sub, fontSize = 13.sp, maxLines = 1)
        }
        if (size != Size.S) {
            Box(Modifier.fillMaxHeight().padding(horizontal = 12.dp).width(0.5.dp).background(sub.copy(alpha = 0.4f)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(city.name, color = sub, fontSize = 13.sp)
                rise?.let { Text("זריחה  ${it.format(f)}", color = ink, fontSize = 15.sp) }
                set?.let { Text("שקיעה  ${it.format(f)}", color = ink, fontSize = 15.sp) }
            }
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
    v <= 20 && !charging -> Color(0xFFFF453A)
    else -> Color(0xFF30D158) // Apple's battery green
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

/** Apple's Activity widget: the three rings and today's numbers. Refreshes when shown, at most every 15 minutes. */
@Composable
private fun RingsWidget(size: Size) = Pad {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val s by com.glass.launcher.feature.Health.summary.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.glass.launcher.feature.Health.cached()
        while (true) {
            val last = com.glass.launcher.feature.Health.summary.value?.updated ?: 0L
            if (System.currentTimeMillis() - last > 15 * 60_000L) com.glass.launcher.feature.Health.refresh(ctx)
            kotlinx.coroutines.delay(15 * 60_000L)
        }
    }
    val p = App.instance.prefs
    val t = s?.today
    val rings = p.ringsOrder.map { com.glass.launcher.fitness.Rings.metric(it, s) }
    if (size == Size.S) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { com.glass.launcher.fitness.RingStack(rings, 110.dp, 15.dp) }
    } else Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            rings.forEach { r -> Text(r.text, color = r.c1, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        }
        com.glass.launcher.fitness.RingStack(rings, 120.dp, 15.dp)
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
