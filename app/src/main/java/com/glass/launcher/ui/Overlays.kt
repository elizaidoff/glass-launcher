package com.glass.launcher.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.ScreenLockRotation
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glass.launcher.App
import com.glass.launcher.data.AppEntry
import com.glass.launcher.feature.AirPods
import com.glass.launcher.feature.SystemActions

// ---------------------------------------------------------------- Search

@Composable
fun SearchOverlay(apps: List<AppEntry>, onOpen: (AppEntry) -> Unit, onClose: () -> Unit) {
    val prefs = App.instance.prefs
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val results = remember(q, apps) {
        val t = q.trim()
        if (t.isEmpty()) prefs.recents.mapNotNull { r -> apps.firstOrNull { it.pkg == r } }.filter { prefs.isVisible(it.pkg) }.take(8)
        else apps.filter { prefs.isVisible(it.pkg) && it.label.contains(t, ignoreCase = true) }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f))
            .pointerInput(Unit) { detectTapGestures { onClose() } }
    ) {
        Column(Modifier.statusBarsPadding().imePadding().padding(18.dp).fillMaxSize()) {
            SearchField(q, "חיפוש", Modifier.focusRequester(focus)) { q = it }
            Spacer(Modifier.height(14.dp))
            LazyColumn(Modifier.weight(1f).pointerInput(Unit) { detectTapGestures { } }) {
                items(results, key = { it.pkg }) { e ->
                    Row(
                        Modifier.fillMaxWidth().tap { onOpen(e) }.padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconImage(e, 44.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(e.label, color = Color.White, fontSize = 17.sp)
                    }
                }
                if (q.isNotBlank()) item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp).glass(RoundedCornerShape(16.dp))
                            .tap { SystemActions.webSearch(ctx, q); onClose() }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Language, null, tint = Color.White)
                        Spacer(Modifier.width(12.dp))
                        Text("חיפוש באינטרנט: $q", color = Color.White, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Control Center

@Composable
fun ControlCenter(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    BackHandler(onBack = onClose)
    val torch by SystemActions.torchOn.collectAsState()
    var rotationLocked by remember { mutableStateOf(SystemActions.rotationLocked(ctx)) }
    var dnd by remember { mutableStateOf(SystemActions.dndOn(ctx)) }
    var bright by remember { mutableFloatStateOf(SystemActions.brightness(ctx)) }
    var vol by remember { mutableFloatStateOf(SystemActions.volume(ctx)) }
    val pods by AirPods.state.collectAsState()

    val share = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val send = Intent(Intent.ACTION_SEND).setType(ctx.contentResolver.getType(uri) ?: "*/*")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            SystemActions.start(ctx, Intent.createChooser(send, "שיתוף"))
        }
    }

    fun needsPermission() = Toast.makeText(ctx, "צריך לאשר הרשאה בהגדרות הלאנצ'ר", Toast.LENGTH_SHORT).show()

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f))
            .pointerInput(Unit) { detectTapGestures { onClose() } }
            .pointerInput(Unit) { detectVerticalDragGestures { _, d -> if (d < -25) onClose() } }
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.statusBarsPadding().padding(horizontal = 22.dp, vertical = 30.dp).fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            // iOS-like grid: 4 columns of square units.
            val gap = 14.dp
            val u = (maxWidth - gap * 3) / 4
            val block = u * 2 + gap
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    // Connectivity block
                    Box(Modifier.size(block).glass(RoundedCornerShape(30.dp)).padding(12.dp)) {
                        Column(verticalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxSize()) {
                            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                                RoundToggle(Icons.Rounded.Wifi, true, Color(0xFF0A84FF)) { SystemActions.openWifiPanel(ctx); onClose() }
                                RoundToggle(Icons.Rounded.SignalCellularAlt, true, Color(0xFF30D158)) { SystemActions.openInternetPanel(ctx); onClose() }
                            }
                            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                                RoundToggle(Icons.Rounded.Bluetooth, true, Color(0xFF0A84FF)) { SystemActions.openBluetooth(ctx); onClose() }
                                RoundToggle(Icons.Rounded.Share, false, Color(0xFF0A84FF)) { share.launch("*/*") }
                            }
                        }
                    }
                    // AirPods block
                    Box(Modifier.size(block).glass(RoundedCornerShape(30.dp)).padding(16.dp)) {
                        val p = AirPods.fresh(pods)
                        Column(verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(Icons.Rounded.Headphones, null, tint = Color.White, modifier = Modifier.size(30.dp))
                            Spacer(Modifier.height(10.dp))
                            Text("AirPods", color = Color.White, fontSize = 16.sp)
                            Text(
                                if (p == null) "לא בטווח" else listOfNotNull(
                                    p.left?.let { "ש׳ $it%" }, p.right?.let { "י׳ $it%" }, p.case?.let { "מארז $it%" },
                                ).joinToString(" · "),
                                color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp,
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        SquareTile(Icons.Rounded.ScreenLockRotation, rotationLocked, Color(0xFFFF453A), Modifier.size(u)) {
                            if (!SystemActions.canWriteSettings(ctx)) needsPermission()
                            else { SystemActions.toggleRotationLock(ctx); rotationLocked = SystemActions.rotationLocked(ctx); view.tick() }
                        }
                        SquareTile(Icons.Rounded.DoNotDisturbOn, dnd, Color(0xFF5E5CE6), Modifier.size(u)) {
                            if (!SystemActions.dndAllowed(ctx)) needsPermission()
                            else { SystemActions.toggleDnd(ctx); dnd = SystemActions.dndOn(ctx); view.tick() }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        SquareTile(Icons.Rounded.FlashOn, torch, Color.White, Modifier.size(u)) { SystemActions.toggleTorch(ctx); view.tick() }
                        SquareTile(Icons.Rounded.PhotoCamera, false, Color.White, Modifier.size(u)) { SystemActions.openCamera(ctx); onClose() }
                    }
                    VerticalSlider(bright, Icons.Rounded.WbSunny, Modifier.width(u).height(block)) {
                        if (!SystemActions.canWriteSettings(ctx)) { needsPermission(); return@VerticalSlider }
                        bright = it; SystemActions.setBrightness(ctx, it)
                    }
                    VerticalSlider(vol, Icons.Rounded.VolumeUp, Modifier.width(u).height(block)) {
                        vol = it; SystemActions.setVolume(ctx, it)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    SquareTile(Icons.Rounded.Calculate, false, Color.White, Modifier.size(u)) { SystemActions.openCalculator(ctx); onClose() }
                    SquareTile(Icons.Rounded.Alarm, false, Color.White, Modifier.size(u)) { SystemActions.openAlarms(ctx); onClose() }
                }
            }
        }
    }
}

@Composable
private fun RoundToggle(icon: ImageVector, on: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(58.dp).clip(CircleShape).background(if (on) color else Color.White.copy(alpha = 0.22f)).tap(onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp)) }
}

@Composable
private fun SquareTile(icon: ImageVector, on: Boolean, onColor: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .then(if (on) Modifier.background(onColor, RoundedCornerShape(22.dp)) else Modifier.glass(RoundedCornerShape(22.dp)))
            .tap(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (on && onColor == Color.White) Color.Black else Color.White, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun VerticalSlider(value: Float, icon: ImageVector, modifier: Modifier, onChange: (Float) -> Unit) {
    val cur by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    Box(
        modifier.glass(RoundedCornerShape(26.dp))
            .pointerInput(Unit) {
                detectVerticalDragGestures { ch, d ->
                    ch.consume()
                    change((cur - d / size.height).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { o -> change((1f - o.y / size.height).coerceIn(0f, 1f)) }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(value.coerceIn(0.02f, 1f)).background(Color.White.copy(alpha = 0.92f)))
        Icon(icon, null, tint = Color(0xFF8E8E93), modifier = Modifier.padding(bottom = 14.dp).size(24.dp))
    }
}

// ---------------------------------------------------------------- Assistant glow

/** A short coloured glow around the screen edges while the assistant opens, like Siri. */
@Composable
fun AssistantGlow() {
    val t = rememberInfiniteTransition(label = "glow")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "p")
    val colors = listOf(Color(0xFF64D2FF), Color(0xFFBF5AF2), Color(0xFFFF375F), Color(0xFFFF9F0A), Color(0xFF64D2FF))
    Canvas(Modifier.fillMaxSize()) {
        val shift = phase * size.width * 2
        val brush = Brush.linearGradient(
            colors,
            start = androidx.compose.ui.geometry.Offset(-size.width + shift, 0f),
            end = androidx.compose.ui.geometry.Offset(shift, size.height),
        )
        for (i in 0..2) {
            val w = (14 - i * 4).dp.toPx()
            drawRoundRect(
                brush = brush,
                size = Size(size.width, size.height),
                cornerRadius = CornerRadius(48.dp.toPx()),
                style = Stroke(width = w),
                alpha = 0.35f + i * 0.25f,
            )
        }
    }
}

// ---------------------------------------------------------------- Widget gallery

@Composable
fun WidgetGallery(act: LauncherActivity, onPick: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onClose)
    val providers = remember {
        android.appwidget.AppWidgetManager.getInstance(ctx).installedProviders
            .filter { App.instance.prefs.isVisible(it.provider.packageName) }
            .sortedBy { it.loadLabel(ctx.packageManager) }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
            .pointerInput(Unit) { detectTapGestures { onClose() } }
    ) {
        Column(
            Modifier.statusBarsPadding().padding(16.dp).fillMaxSize()
                .glass(RoundedCornerShape(30.dp), 1.4f)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(18.dp)
        ) {
            Text("הוספת ווידג'ט", color = Color.White, fontSize = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(BuiltinWidgets, key = { it.first }) { (key, title) ->
                    GalleryRow(title, "זכוכית") { size -> onPick(com.glass.launcher.data.HomeGrid.entry(key, size)) }
                }
                items(providers, key = { it.provider.flattenToString() }) { info ->
                    val app = remember(info) {
                        runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(info.provider.packageName, 0)).toString() }.getOrDefault("")
                    }
                    GalleryRow(info.loadLabel(ctx.packageManager), app) { size ->
                        act.bindWidget(info) { id -> onPick(com.glass.launcher.data.HomeGrid.entry("appwidget:$id", size)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryRow(title: String, app: String, onSize: (com.glass.launcher.data.HomeGrid.Size) -> Unit) {
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), 0.8f).padding(14.dp)) {
        Text(title, color = Color.White, fontSize = 16.sp)
        if (app.isNotEmpty()) Text(app, color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.glass.launcher.data.HomeGrid.Size.entries.forEach { s -> Pill(s.label) { onSize(s) } }
        }
    }
}
