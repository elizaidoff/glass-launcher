package com.glass.launcher.ui

import android.appwidget.AppWidgetManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.glass.launcher.App
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
private fun rememberNow(): ZonedDateTime {
    val now by produceState(ZonedDateTime.now()) {
        while (true) {
            delay(1000L * (60 - ZonedDateTime.now().second).coerceAtLeast(1))
            value = ZonedDateTime.now()
        }
    }
    return now
}

@Composable
fun TodayView(onSearch: () -> Unit, onEdit: () -> Unit) {
    val prefs = App.instance.prefs
    val tick by prefs.version.collectAsState()
    val list = remember(tick) { prefs.widgets }
    val now = rememberNow()
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
        items(list, key = { it }) { w ->
            when {
                w == "builtin:clock" -> ClockWidget(now)
                w == "builtin:hebdate" -> HebrewDateWidget(now)
                w == "builtin:battery" -> BatteryWidget(now)
                w == "builtin:airpods" -> AirPodsWidget()
                w.startsWith("appwidget:") -> w.removePrefix("appwidget:").toIntOrNull()?.let { HostedWidget(it) }
            }
        }
        item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Pill("עריכה", onEdit) }
        }
    }
}

@Composable
private fun WidgetCard(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().glass(RoundedCornerShape(26.dp)).padding(18.dp)) { content() }
}

@Composable
private fun ClockWidget(now: ZonedDateTime) {
    WidgetCard {
        Column {
            Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Light)
            Text(now.format(DateTimeFormatter.ofPattern("EEEE, d 'ב'MMMM", He)), color = Color.White.copy(alpha = 0.85f), fontSize = 17.sp)
        }
    }
}

@Composable
private fun HebrewDateWidget(now: ZonedDateTime) {
    val city = Cities.getOrElse(App.instance.prefs.cityIndex) { Cities[0] }
    val (rise, set) = remember(now.toLocalDate(), city) { SunTimes.of(now.toLocalDate(), city, now.zone) }
    val after = set != null && now.toLocalTime().isAfter(set)
    val f = DateTimeFormatter.ofPattern("HH:mm")
    WidgetCard {
        Column {
            Text(HebrewDate.format(now, after), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                listOfNotNull(rise?.let { "זריחה ${it.format(f)}" }, set?.let { "שקיעה ${it.format(f)}" }, city.name).joinToString("  ·  "),
                color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp,
            )
        }
    }
}

@Composable
private fun BatteryWidget(now: ZonedDateTime) {
    val ctx = LocalContext.current
    val pods by AirPods.state.collectAsState()
    val p = AirPods.fresh(pods)
    val phone = remember(now) { SystemActions.batteryPercent(ctx) }
    val charging = remember(now) { SystemActions.batteryCharging(ctx) }
    WidgetCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            BatteryRing(phone, charging, Icons.Rounded.PhoneAndroid)
            if (p != null) {
                p.left?.let { BatteryRing(it, p.leftCharging, Icons.Rounded.Headphones, "שמאל") }
                p.right?.let { BatteryRing(it, p.rightCharging, Icons.Rounded.Headphones, "ימין") }
                p.case?.let { BatteryRing(it, p.caseCharging, Icons.Rounded.Work, "מארז") }
            }
        }
    }
}

@Composable
private fun AirPodsWidget() {
    val pods by AirPods.state.collectAsState()
    val p = AirPods.fresh(pods)
    val ctx = LocalContext.current
    WidgetCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Headphones, null, tint = Color.White, modifier = Modifier.size(26.dp))
                Spacer(Modifier.size(10.dp))
                Text("AirPods", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(14.dp))
            when {
                !AirPods.hasPermission(ctx) -> Text("צריך לאשר הרשאת בלוטות' בהגדרות הלאנצ'ר", color = Color.White.copy(alpha = 0.8f))
                p == null -> Text("פתח את המארז ליד הטלפון", color = Color.White.copy(alpha = 0.8f))
                else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    p.left?.let { BarRow("שמאל", it, p.leftCharging) }
                    p.right?.let { BarRow("ימין", it, p.rightCharging) }
                    p.case?.let { BarRow("מארז", it, p.caseCharging) }
                }
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
        Text(name, color = Color.White, fontSize = 15.sp, modifier = Modifier.size(width = 52.dp, height = 20.dp))
        Box(Modifier.weight(1f).height(10.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(Color.White.copy(alpha = 0.2f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
                drawRoundRect(
                    levelColor(v, charging),
                    size = size.copy(width = size.width * v / 100f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2),
                )
            }
        }
        Spacer(Modifier.size(10.dp))
        if (charging) Icon(Icons.Rounded.Bolt, null, tint = Color(0xFF30D158), modifier = Modifier.size(16.dp))
        Text("$v%", color = Color.White, fontSize = 15.sp)
    }
}

@Composable
private fun BatteryRing(v: Int, charging: Boolean, icon: ImageVector, label: String? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 5.dp.toPx()
                drawArc(Color.White.copy(alpha = 0.2f), 0f, 360f, false, style = Stroke(w))
                drawArc(levelColor(v, charging), -90f, 360f * v / 100f, false, style = Stroke(w, cap = StrokeCap.Round))
            }
            Icon(if (charging) Icons.Rounded.Bolt else icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text("$v%", color = Color.White, fontSize = 14.sp)
        if (label != null) Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
    }
}

/** A real Android widget from another app, shown inside a glass card. */
@Composable
private fun HostedWidget(id: Int) {
    val ctx = LocalContext.current
    val info = remember(id) { AppWidgetManager.getInstance(ctx).getAppWidgetInfo(id) } ?: return
    val density = ctx.resources.displayMetrics.density
    val h = (info.minHeight / density).coerceIn(110f, 420f)
    Box(Modifier.fillMaxWidth().height(h.dp).glass(RoundedCornerShape(26.dp))) {
        AndroidView(
            factory = { c -> App.instance.widgetHost.createView(c, id, info).apply { setAppWidget(id, info) } },
            modifier = Modifier.fillMaxSize().padding(6.dp),
        )
    }
}
