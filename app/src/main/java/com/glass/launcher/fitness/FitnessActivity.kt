package com.glass.launcher.fitness

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.health.connect.client.PermissionController
import com.glass.launcher.App
import com.glass.launcher.feature.Health
import com.glass.launcher.ui.KitText
import com.glass.launcher.ui.hideSystemNav
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Apple's ring colours. */
object Rings {
    val move = Color(0xFFFA114F)
    val moveEnd = Color(0xFFFF5E8A)
    val exercise = Color(0xFF92E82A)
    val exerciseEnd = Color(0xFFC2FF5E)
    val steps = Color(0xFF00D7E5)
    val stepsEnd = Color(0xFF6DF5FF)

    class Ring(val id: String, val name: String, val value: Float, val c1: Color, val c2: Color, val text: String)

    val all = listOf("move" to "תנועה", "exercise" to "אימון", "steps" to "צעדים", "distance" to "מרחק", "floors" to "קומות")

    fun metric(id: String, s: Health.Summary?): Ring {
        val p = App.instance.prefs
        val t = s?.today
        return when (id) {
            "exercise" -> Ring(id, "אימון", (t?.exerciseMin ?: 0) / p.exerciseGoal.toFloat(), exercise, exerciseEnd, "${t?.exerciseMin ?: 0}/${p.exerciseGoal} דק'")
            "steps" -> Ring(id, "צעדים", (t?.steps ?: 0) / p.stepsGoal.toFloat(), steps, stepsEnd, "${t?.steps ?: 0}/${p.stepsGoal} צעדים")
            "distance" -> Ring(id, "מרחק", ((s?.distanceKm ?: 0.0) / p.distanceGoal).toFloat(), Color(0xFFFFD60A), Color(0xFFFFF08A), "%.1f/%d ק\"מ".format(s?.distanceKm ?: 0.0, p.distanceGoal))
            "floors" -> Ring(id, "קומות", (s?.floors ?: 0) / p.floorsGoal.toFloat(), Color(0xFFBF5AF2), Color(0xFFE0A8FF), "${s?.floors ?: 0}/${p.floorsGoal} קומות")
            else -> Ring("move", "תנועה", (t?.moveKcal ?: 0) / p.moveGoal.toFloat(), move, moveEnd, "${t?.moveKcal ?: 0}/${p.moveGoal} קק\"ל")
        }
    }
}

/** Rings in any order and number (1–3), outer first. */
@Composable
fun RingStack(rings: List<Rings.Ring>, size: Dp, stroke: Dp) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(rings.map { it.value }) { a.snapTo(0f); a.animateTo(1f, tween(1100)) }
    Canvas(Modifier.size(size)) {
        val sw = stroke.toPx(); val gap = sw * 0.12f
        rings.take(3).forEachIndexed { i, r ->
            val inset = sw / 2 + i * (sw + gap)
            val tl = Offset(inset, inset); val sz = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            drawArc(r.c1.copy(alpha = 0.25f), 0f, 360f, false, tl, sz, style = Stroke(sw))
            val sweep = 360f * r.value * a.value
            if (sweep > 0.5f) drawArc(Brush.sweepGradient(listOf(r.c1, r.c2, r.c1), center = center), -90f, sweep.coerceAtMost(359.9f), false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
        }
    }
}

/** Three concentric rings; each can go past 100% (a second lap darker on top, like Apple's). */
@Composable
fun ActivityRings(move: Float, exercise: Float, steps: Float, size: Dp, stroke: Dp, animate: Boolean = true) {
    val a = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(move, exercise, steps) { if (animate) { a.snapTo(0f); a.animateTo(1f, tween(1100)) } }
    Canvas(Modifier.size(size)) {
        val sw = stroke.toPx()
        val gap = sw * 0.12f
        listOf(Triple(move, Rings.move, Rings.moveEnd), Triple(exercise, Rings.exercise, Rings.exerciseEnd), Triple(steps, Rings.steps, Rings.stepsEnd))
            .forEachIndexed { i, (v, c1, c2) ->
                val inset = sw / 2 + i * (sw + gap)
                val tl = Offset(inset, inset)
                val sz = Size(this.size.width - inset * 2, this.size.height - inset * 2)
                drawArc(c1.copy(alpha = 0.25f), 0f, 360f, false, tl, sz, style = Stroke(sw))
                val sweep = 360f * v * a.value
                if (sweep > 0.5f) drawArc(
                    Brush.sweepGradient(listOf(c1, c2, c1), center = center), -90f, sweep.coerceAtMost(359.9f), false, tl, sz,
                    style = Stroke(sw, cap = StrokeCap.Round),
                )
                if (sweep > 360f) drawArc(c2, -90f + (sweep - 360f), 2f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            }
    }
}

class FitnessActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { KitText { FitnessScreen() } }
        }
    }

    override fun onResume() { super.onResume(); hideSystemNav() }
}

private val card = Color(0xFF1C1C1E)
private val sub = Color(0xFF8E8E93)

@Composable
private fun FitnessScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by Health.summary.collectAsState()
    var state by remember { mutableIntStateOf(0) } // 0 checking, 1 no app, 2 needs permission, 3 ok
    var tab by remember { mutableIntStateOf(0) }
    val ask = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        scope.launch { state = if (Health.granted(ctx)) 3.also { Health.refresh(ctx) } else 2 }
    }
    LaunchedEffect(Unit) {
        Health.cached()
        state = when {
            !Health.available(ctx) -> 1
            !Health.granted(ctx) -> 2
            else -> 3
        }
        if (state == 3) Health.refresh(ctx)
    }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f)) {
            when (state) {
                1 -> Message("כדי לראות את נתוני השעון צריך את Health Connect (מובנה באנדרואיד 14 ומעלה).", "פתיחה") {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))) }
                }
                2 -> Message("Garmin Connect כותב את הצעדים, הדופק, השינה והאימונים ל-Health Connect. אשר ל'כושר' לקרוא אותם – רק לקריאה, שום דבר לא יוצא מהטלפון.\n\nבאפליקציית Garmin Connect: הגדרות ← אפליקציות מחוברות ← Health Connect.", "אישור גישה") {
                    ask.launch(Health.permissions)
                }
                else -> {
                    // Re-draw when a goal changes.
                    val tick by App.instance.prefs.version.collectAsState()
                    androidx.compose.runtime.key(tick) { if (tab == 0) Summary(s) else HealthTab(s) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(Color(0xF0161618)).navigationBarsPadding().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("סיכום", "בריאות").forEachIndexed { i, t ->
                Text(t, color = if (tab == i) Rings.exercise else sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { tab = i }.padding(10.dp))
            }
        }
    }
}

@Composable
private fun Message(text: String, button: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        ActivityRings(0.75f, 0.5f, 0.3f, 150.dp, 20.dp)
        Spacer(Modifier.height(28.dp))
        Text(text, color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center, lineHeight = 23.sp)
        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp)).background(Rings.exercise).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Text(button, color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(card).padding(16.dp)) { content() }
}

private val dayFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM", java.util.Locale("he"))

@Composable
private fun Summary(s: Health.Summary?) {
    val p = App.instance.prefs
    var editGoals by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Text(java.time.LocalDate.now().format(dayFmt), color = sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 30.dp))
            Text("סיכום", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        }
        if (!Health.lastHadData) item {
            val ctx = LocalContext.current
            Card {
                Column {
                    Text("עדיין לא מגיעים נתונים מהשעון", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("באפליקציית Garmin Connect: ☰ / עוד ← הגדרות ← אפליקציות מחוברות ← Health Connect ← אישור שיתוף לכל הסוגים. אחרי סנכרון של השעון הנתונים יופיעו כאן.",
                        color = sub, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text("פתיחת Garmin Connect", color = Rings.exercise, fontSize = 15.sp, modifier = Modifier.clickable {
                            ctx.packageManager.getLaunchIntentForPackage("com.garmin.android.apps.connectmobile")?.let { ctx.startActivity(it) }
                        })
                        Text("הגדרות Health Connect", color = Rings.exercise, fontSize = 15.sp, modifier = Modifier.clickable { Health.openSettings(ctx) })
                    }
                }
            }
        }
        item {
            val t = s?.today
            Card {
                Column {
                    Text("פעילות", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Stat("תנועה", "${t?.moveKcal ?: 0}/${p.moveGoal}", "קק\"ל", Rings.move)
                            Stat("אימון", "${t?.exerciseMin ?: 0}/${p.exerciseGoal}", "דק'", Rings.exercise)
                            Stat("צעדים", "${t?.steps ?: 0}/${p.stepsGoal}", "", Rings.steps)
                        }
                        ActivityRings(
                            (t?.moveKcal ?: 0) / p.moveGoal.toFloat(), (t?.exerciseMin ?: 0) / p.exerciseGoal.toFloat(), (t?.steps ?: 0) / p.stepsGoal.toFloat(),
                            150.dp, 20.dp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("שינוי יעדים", color = Rings.exercise, fontSize = 14.sp, modifier = Modifier.clickable { editGoals = true })
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
                Box(Modifier.weight(1f)) { Card { Column {
                    Text("צעדים", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("%,d".format(s?.today?.steps ?: 0), color = Rings.steps, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                } } }
                Box(Modifier.weight(1f)) { Card { Column {
                    Text("מרחק", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("%.1f ק\"מ".format(s?.distanceKm ?: 0.0), color = Rings.steps, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                } } }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
                Box(Modifier.weight(1f)) { Card { Column {
                    Text("קלוריות היום", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("${s?.totalKcal ?: 0} קק\"ל", color = Rings.move, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Text("פעילות: ${s?.today?.moveKcal ?: 0}", color = sub, fontSize = 13.sp)
                } } }
                Box(Modifier.weight(1f)) { Card { Column {
                    Text("שינה", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(s?.sleepMin?.let { "${it / 60}:%02d".format(it % 60) } ?: "—", color = Color(0xFF5E5CE6), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Text("שעות בלילה", color = sub, fontSize = 13.sp)
                } } }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
                Box(Modifier.weight(1f)) { Card { Column {
                    Text("דופק", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(s?.heartAvg?.let { "$it" } ?: "—", color = Color(0xFFFF375F), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Text(s?.heartResting?.let { "במנוחה $it" } ?: "ממוצע היום", color = sub, fontSize = 13.sp)
                } } }
                Box(Modifier.weight(1f)) { Card { Column {
                    Text("קומות", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("${s?.floors ?: 0}", color = Color(0xFFBF5AF2), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Text("היום", color = sub, fontSize = 13.sp)
                } } }
            }
        }
        item {
            Card {
                Column {
                    Text("השבוע", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        (s?.week ?: emptyList()).takeLast(7).forEach { d ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                ActivityRings(d.moveKcal / p.moveGoal.toFloat(), d.exerciseMin / p.exerciseGoal.toFloat(), d.steps / p.stepsGoal.toFloat(), 38.dp, 5.dp, animate = false)
                                Spacer(Modifier.height(4.dp))
                                Text(hebShort(d.date.dayOfWeek.value), color = sub, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
        item {
            Text("אימונים", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp))
        }
        val ws = s?.workouts ?: emptyList()
        if (ws.isEmpty()) item { Card { Text("אין אימונים בשבועיים האחרונים.", color = sub, fontSize = 15.sp) } }
        ws.forEach { w ->
            item {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(Rings.exercise.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                            Text("🏃", fontSize = 22.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(w.title?.takeIf { it.isNotBlank() } ?: Health.workoutName(w.type), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text("${w.minutes} דק'", color = Rings.exercise, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text(Instant.ofEpochMilli(w.start).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d.M")), color = sub, fontSize = 14.sp)
                    }
                }
            }
        }
    }
    if (editGoals) GoalsDialog { editGoals = false }
}

private fun hebShort(dow: Int) = when (dow) { 7 -> "א'"; 1 -> "ב'"; 2 -> "ג'"; 3 -> "ד'"; 4 -> "ה'"; 5 -> "ו'"; else -> "ש'" }

@Composable
private fun Stat(name: String, value: String, unit: String, color: Color) {
    Column {
        Text(name, color = Color.White, fontSize = 14.sp)
        Text("$value $unit".trim(), color = color, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GoalsDialog(onClose: () -> Unit) {
    val p = App.instance.prefs
    var move by remember { mutableIntStateOf(p.moveGoal) }
    var ex by remember { mutableIntStateOf(p.exerciseGoal) }
    var steps by remember { mutableIntStateOf(p.stepsGoal) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(Modifier.width(300.dp).clip(RoundedCornerShape(20.dp)).background(card).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("יעדים יומיים", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Stepper("תנועה (קק\"ל)", move, 50, Rings.move) { move = it.coerceIn(50, 3000) }
            Stepper("אימון (דקות)", ex, 5, Rings.exercise) { ex = it.coerceIn(5, 300) }
            Stepper("צעדים", steps, 500, Rings.steps) { steps = it.coerceIn(500, 50000) }
            Spacer(Modifier.height(12.dp))
            Text("טבעות בווידג'ט (מבחוץ פנימה) – הקשה מחליפה", color = sub, fontSize = 13.sp)
            var order by remember { mutableStateOf(p.ringsOrder.take(3)) }
            listOf("חיצונית", "אמצעית", "פנימית").forEachIndexed { i, pos ->
                val cur = order.getOrNull(i)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(pos, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(Rings.all.firstOrNull { it.first == cur }?.second ?: "ללא", color = Rings.exercise, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            val opts = Rings.all.map { it.first } + (if (i > 0) listOf("") else emptyList())
                            val next = opts[(opts.indexOf(cur ?: "") + 1) % opts.size]
                            val m = order.toMutableList()
                            while (m.size <= i) m.add("")
                            m[i] = next
                            order = m.filter { it.isNotEmpty() }
                            p.ringsOrder = order
                        }.padding(6.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("שמירה", color = Rings.exercise, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable {
                p.moveGoal = move; p.exerciseGoal = ex; p.stepsGoal = steps; onClose()
            }.padding(8.dp))
        }
    }
}

@Composable
private fun Stepper(label: String, v: Int, step: Int, color: Color, set: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text("−", color = color, fontSize = 26.sp, modifier = Modifier.clickable { set(v - step) }.padding(horizontal = 10.dp))
        Text("$v", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text("+", color = color, fontSize = 26.sp, modifier = Modifier.clickable { set(v + step) }.padding(horizontal = 10.dp))
    }
}

@Composable
private fun HealthTab(s: Health.Summary?) {
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { Text("בריאות", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 30.dp, bottom = 6.dp)) }
        fun row(title: String, value: String, color: Color) = item {
            Card {
                Column {
                    Text(title, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(value, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        row("דופק ממוצע היום", s?.heartAvg?.let { "$it פעימות לדקה" } ?: "—", Color(0xFFFF375F))
        row("דופק במנוחה", s?.heartResting?.let { "$it פעימות לדקה" } ?: "—", Color(0xFFFF375F))
        if (s?.heartMin != null && s.heartMax != null) row("טווח דופק היום", "${s.heartMin}–${s.heartMax}", Color(0xFFFF375F))
        row("שינה בלילה", s?.sleepMin?.let { "${it / 60} שע' ${it % 60} דק'" } ?: "—", Color(0xFF5E5CE6))
        row("קומות", "${s?.floors ?: 0}", Color(0xFFFF9F0A))
        row("משקל", s?.weightKg?.let { "%.1f ק\"ג".format(it) } ?: "—", Color(0xFFBF5AF2))
        item {
            Text("הנתונים מגיעים מ-Garmin Connect דרך Health Connect. Body Battery, VO2 ו-HRV נשארים באפליקציה של Garmin – הם לא נכתבים ל-Health Connect.",
                color = sub, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        }
    }
}
