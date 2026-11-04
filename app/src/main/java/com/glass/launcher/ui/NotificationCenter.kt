package com.glass.launcher.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glass.launcher.App
import com.glass.launcher.feature.NotifItem
import com.glass.launcher.feature.NotifListener
import com.glass.launcher.lock.LockGuard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** iPhone-style Notification Center: time on top, notifications grouped by app. */
@Composable
fun NotificationCenter(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val items by NotifListener.items.collectAsState()
    val prefs = App.instance.prefs
    // Never show notifications of hidden apps here; locked apps show no content.
    val visible = items.filter { it.pkg !in prefs.hidden && it.pkg !in prefs.disguise }.map {
        if (prefs.lockEnabled && it.pkg in prefs.locked) it.copy(title = "", text = "התראה חדשה") else it
    }
    val groups = visible.groupBy { it.pkg }.toList().sortedByDescending { it.second.maxOf { n -> n.time } }
    var expanded by remember { mutableStateOf<String?>(null) }
    val now = rememberNow()
    BackHandler(onBack = onClose)

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
            .pointerInput(Unit) { detectVerticalDragGestures { _, d -> if (d < -30) onClose() } }
            .pointerInput(Unit) { detectTapGestures { onClose() } }
    ) {
        Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp).fillMaxSize().springIn(0.97f, (-60).dp)) {
            Spacer(Modifier.height(30.dp))
            Text(
                now.format(DateTimeFormatter.ofPattern("HH:mm")), color = Color.White, fontSize = 72.sp,
                fontWeight = FontWeight.Light, modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Text(
                now.format(DateTimeFormatter.ofPattern("EEEE, d 'ב'MMMM", Locale("he", "IL"))), color = Color.White.copy(alpha = 0.85f),
                fontSize = 18.sp, modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(24.dp))
            if (!NotifListener.isEnabled(ctx)) {
                Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp)).padding(16.dp)) {
                    Text("כדי לראות כאן התראות, צריך לאשר לזכוכית \"גישה להתראות\".", color = Color.White, fontSize = 15.sp)
                    Spacer(Modifier.height(10.dp))
                    Pill("אישור") { LockGuard.startUnlocked(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); onClose() }
                }
                return@Column
            }
            if (groups.isEmpty()) {
                Text("אין התראות", color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                return@Column
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("התראות", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Pill("ניקוי הכול") { NotifListener.dismissAll(visible.map { it.key }) }
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 40.dp),
                modifier = Modifier.pointerInput(Unit) { detectTapGestures { } },
            ) {
                groups.forEach { (pkg, list) ->
                    val open = expanded == pkg || list.size == 1
                    if (open) {
                        items(list, key = { it.key }) { n ->
                            NotifCard(n, extra = 0, onOpen = { NotifListener.open(ctx, n); onClose() }, onDismiss = { NotifListener.dismiss(n.key) })
                        }
                        if (list.size > 1) item(key = "less:$pkg") {
                            Text("פחות", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp,
                                modifier = Modifier.tap { expanded = null }.padding(horizontal = 12.dp, vertical = 4.dp))
                        }
                    } else {
                        item(key = "group:$pkg") {
                            NotifCard(list.first(), extra = list.size - 1, onOpen = { expanded = pkg },
                                onDismiss = { NotifListener.dismissAll(list.map { it.key }) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotifCard(n: NotifItem, extra: Int, onOpen: () -> Unit, onDismiss: () -> Unit) {
    var dx by remember { mutableFloatStateOf(0f) }
    val shown by animateFloatAsState(dx, label = "dx")
    val app = remember(n.pkg) { App.instance.repo.byPackage(n.pkg) }
    val time = remember(n.time) {
        val m = (System.currentTimeMillis() - n.time) / 60_000
        when {
            m < 1 -> "עכשיו"
            m < 60 -> "לפני $m דק׳"
            else -> Instant.ofEpochMilli(n.time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
        }
    }
    Box {
        // Stacked look for a group, like on iPhone.
        if (extra > 0) Box(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(top = 10.dp).height(70.dp)
                .glass(RoundedCornerShape(22.dp), 0.6f)
        )
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = shown; alpha = 1f - (abs(shown) / 900f).coerceIn(0f, 0.8f) }
                .background(Color(0xB31C1C1E), RoundedCornerShape(22.dp))
                .glass(RoundedCornerShape(22.dp), 0.8f)
                .pointerInput(n.key) {
                    detectHorizontalDragGestures(
                        onDragEnd = { if (abs(dx) > size.width * 0.35f) onDismiss() else dx = 0f },
                        onHorizontalDrag = { ch, d -> ch.consume(); dx += d },
                    )
                }
                .tap(onOpen)
                .padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (app != null) IconImage(app, 38.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(app?.label ?: n.pkg, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(time, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                }
                if (n.title.isNotEmpty()) Text(n.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (n.text.isNotEmpty()) Text(n.text, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (extra > 0) Text("ועוד $extra התראות", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            }
        }
    }
}
