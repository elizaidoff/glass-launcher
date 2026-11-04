@file:OptIn(ExperimentalMaterial3Api::class)

package com.glass.launcher.decoy

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.DevicesOther
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glass.launcher.App
import com.glass.launcher.data.AppEntry
import com.glass.launcher.feature.AirPods
import com.glass.launcher.feature.SystemActions
import com.glass.launcher.lock.Auth
import com.glass.launcher.lock.LockGuard
import com.glass.launcher.ui.LauncherActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Looks like the stock Android 15 Settings app, but only ever shows the apps that are visible.
 * Basic things work for real (volume, brightness, Wi-Fi panel, battery and storage figures).
 * Secret way into the real Settings: hold the big "הגדרות" title for 3 seconds, then fingerprint.
 */
class DecoySettingsActivity : ComponentActivity() {

    private val stack = mutableStateListOf("home")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (stack.size > 1) stack.removeAt(stack.lastIndex) else leave()
            }
        })
        setContent {
            val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val scheme: ColorScheme = if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            MaterialTheme(colorScheme = scheme) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.fillMaxSize().background(scheme.surfaceContainer)) {
                        DecoyNav(stack.last(), push = { stack.add(it) }, pop = { if (stack.size > 1) stack.removeAt(stack.lastIndex) else leave() }, openReal = ::openReal)
                    }
                }
            }
        }
    }

    private fun openReal() {
        Auth.prompt(this, "הגדרות", onSuccess = {
            LockGuard.unlock(App.instance.settingsPackage)
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            finish()
        })
    }

    private fun leave() {
        startActivity(Intent(this, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun onStop() {
        super.onStop()
        // Never linger in the background where it could be found in recent apps.
        if (!isChangingConfigurations) finish()
    }

    companion object {
        fun start(ctx: Context) {
            ctx.startActivity(
                Intent(ctx, DecoySettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }
    }
}

private data class Entry(val key: String, val title: String, val summary: String, val icon: ImageVector, val color: Color)

private val homeEntries = listOf(
    Entry("network", "רשת ואינטרנט", "Wi-Fi, נתונים סלולריים, נקודה לשיתוף אינטרנט", Icons.Rounded.Wifi, Color(0xFF4285F4)),
    Entry("devices", "מכשירים מחוברים", "Bluetooth, התאמה", Icons.Rounded.DevicesOther, Color(0xFF00A3A3)),
    Entry("apps", "אפליקציות", "אפליקציות אחרונות, אפליקציות ברירת מחדל", Icons.Rounded.Apps, Color(0xFFE8710A)),
    Entry("notifications", "התראות", "היסטוריית התראות, שיחות", Icons.Rounded.Notifications, Color(0xFFD93025)),
    Entry("battery", "סוללה", "", Icons.Rounded.BatteryFull, Color(0xFF1E8E3E)),
    Entry("storage", "אחסון", "", Icons.Rounded.Storage, Color(0xFF9334E6)),
    Entry("sound", "צליל ורטט", "עוצמת קול, רטט, נא לא להפריע", Icons.Rounded.VolumeUp, Color(0xFF00897B)),
    Entry("display", "תצוגה ונגיעה", "עיצוב כהה, גודל הגופן, בהירות", Icons.Rounded.Brightness6, Color(0xFFF29900)),
    Entry("wallpaper", "טפט וסגנון", "צבעים, עיצוב שמבוסס על נושא, סמלים", Icons.Rounded.Palette, Color(0xFFC2185B)),
    Entry("a11y", "נגישות", "תצוגה, אינטראקציה, אודיו", Icons.Rounded.Accessibility, Color(0xFF1A73E8)),
    Entry("security", "אבטחה ופרטיות", "אבטחת האפליקציות, נעילת המכשיר, הרשאות", Icons.Rounded.Lock, Color(0xFF34A853)),
    Entry("location", "מיקום", "מופעל", Icons.Rounded.LocationOn, Color(0xFF4285F4)),
    Entry("safety", "בטיחות ומקרי חירום", "שיתוף מיקום במקרה חירום, התראות חירום", Icons.Rounded.Warning, Color(0xFFD93025)),
    Entry("accounts", "סיסמאות, מפתחות גישה וחשבונות", "סיסמאות שמורות, מילוי אוטומטי", Icons.Rounded.VpnKey, Color(0xFF7B1FA2)),
    Entry("wellbeing", "רווחה דיגיטלית ובקרת הורים", "זמן מסך, טיימרים לאפליקציות", Icons.Rounded.Spa, Color(0xFF0F9D58)),
    Entry("system", "מערכת", "שפות, תנועות, זמן, גיבוי", Icons.Rounded.PhoneAndroid, Color(0xFF5F6368)),
    Entry("about", "מידע על הטלפון", Build.MODEL, Icons.Rounded.Info, Color(0xFF5F6368)),
)

@Composable
private fun DecoyNav(route: String, push: (String) -> Unit, pop: () -> Unit, openReal: () -> Unit) {
    when {
        route == "home" -> DecoyHome(push, openReal)
        route == "apps" -> AppsPage(push, pop)
        route == "allapps" -> AllAppsPage(push, pop)
        route.startsWith("app:") -> AppInfoPage(route.removePrefix("app:"), pop)
        route == "network" -> NetworkPage(pop)
        route == "devices" -> DevicesPage(pop)
        route == "battery" -> BatteryPage(pop)
        route == "storage" -> StoragePage(pop)
        route == "sound" -> SoundPage(pop)
        route == "display" -> DisplayPage(pop)
        route == "about" -> AboutPage(pop, openReal)
        else -> GenericPage(homeEntries.firstOrNull { it.key == route }?.title ?: "", pop)
    }
}

@Composable
private fun DecoyHome(push: (String) -> Unit, openReal: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val battery = remember { SystemActions.batteryPercent(ctx) }
    val st = remember { StatFs(Environment.getDataDirectory().path) }
    val usedPct = remember { ((st.totalBytes - st.availableBytes) * 100 / st.totalBytes.coerceAtLeast(1)).toInt() }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            Text(
                "הגדרות",
                fontSize = 36.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(start = 24.dp, top = 72.dp, bottom = 24.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            val job = scope.launch { delay(3000); openReal() }
                            tryAwaitRelease()
                            job.cancel()
                        })
                    },
            )
        }
        item {
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(14.dp))
                Text("חיפוש בהגדרות", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
            }
            Spacer(Modifier.height(18.dp))
        }
        items(homeEntries) { e ->
            val summary = when (e.key) {
                "battery" -> "$battery%"
                "storage" -> "$usedPct% בשימוש – ${Formatter.formatShortFileSize(ctx, st.availableBytes)} פנויים"
                else -> e.summary
            }
            HomeRow(e, summary) { push(e.key) }
        }
    }
}

@Composable
private fun HomeRow(e: Entry, summary: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(e.color), contentAlignment = Alignment.Center) {
            Icon(e.icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(20.dp))
        Column {
            Text(e.title, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
            if (summary.isNotEmpty()) Text(summary, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun Page(title: String, pop: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = pop), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        Text(title, fontSize = 32.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 20.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp), content = content)
    }
}

@Composable
private fun Item(title: String, summary: String? = null, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier.clickable { })
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
            if (!summary.isNullOrEmpty()) Text(summary, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
private fun Header(text: String) {
    Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 6.dp))
}

@Composable
private fun FakeSwitch(initial: Boolean) {
    var on by remember { mutableStateOf(initial) }
    Switch(checked = on, onCheckedChange = { on = it })
}

private fun visibleApps(): List<AppEntry> {
    val p = App.instance.prefs
    return App.instance.repo.apps.value.filter { p.isVisible(it.pkg) }
}

@Composable
private fun AppRow(e: AppEntry, summary: String?, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val bmp = remember(e.pkg) { App.instance.icons.iconForPackage(e.pkg, 120)?.asImageBitmap() }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (bmp != null) Image(bmp, null, Modifier.size(40.dp).clip(CircleShape))
        Spacer(Modifier.width(20.dp))
        Column {
            Text(e.label, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
            if (summary != null) Text(summary, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AppsPage(push: (String) -> Unit, pop: () -> Unit) {
    val apps = remember { visibleApps() }
    val byPkg = apps.associateBy { it.pkg }
    val recent = App.instance.prefs.recents.mapNotNull { byPkg[it] }.take(3)
    Page("אפליקציות", pop) {
        item { Header("אפליקציות שנפתחו לאחרונה") }
        items(recent, key = { "r" + it.pkg }) { e -> AppRow(e, "לאחרונה") { push("app:" + e.pkg) } }
        item { Item("הצגת כל ${apps.size} האפליקציות", onClick = { push("allapps") }) }
        item { Header("כללי") }
        item { Item("אפליקציות ברירת מחדל", "עוזר דיגיטלי, דפדפן ואפליקציית טלפון") }
        item { Item("זמן מסך", "טיימרים יומיים ושעות שינה") }
        item { Item("אפליקציות שלא נעשה בהן שימוש", "0 אפליקציות שלא נעשה בהן שימוש") }
        item { Item("שימוש בסוללה של אפליקציות") }
        item { Item("גישה מיוחדת לאפליקציות", "2 אפליקציות יכולות להשתמש בנתונים בלי הגבלה") }
    }
}

@Composable
private fun AllAppsPage(push: (String) -> Unit, pop: () -> Unit) {
    val apps = remember { visibleApps() }
    Page("כל האפליקציות", pop) {
        items(apps, key = { it.pkg }) { e ->
            val size = remember(e.pkg) { runCatching { File(e.info.applicationInfo.sourceDir).length() }.getOrDefault(0L) }
            val ctx = LocalContext.current
            AppRow(e, Formatter.formatShortFileSize(ctx, size)) { push("app:" + e.pkg) }
        }
    }
}

@Composable
private fun AppInfoPage(pkg: String, pop: () -> Unit) {
    val ctx = LocalContext.current
    val e = remember(pkg) { App.instance.repo.byPackage(pkg) } ?: return
    val bmp = remember(pkg) { App.instance.icons.iconForPackage(pkg, 220)?.asImageBitmap() }
    val version = remember(pkg) { runCatching { ctx.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull() ?: "" }
    val size = remember(pkg) { runCatching { File(e.info.applicationInfo.sourceDir).length() }.getOrDefault(0L) }
    var stopped by remember { mutableStateOf(false) }
    Page("פרטי האפליקציה", pop) {
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (bmp != null) Image(bmp, null, Modifier.size(72.dp).clip(CircleShape))
                Spacer(Modifier.height(12.dp))
                Text(e.label, fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton("פתיחה") { App.instance.repo.launch(e) }
                    if (!e.isSystem) ActionButton("הסרת התקנה") {
                        SystemActions.start(ctx, Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
                    }
                    ActionButton(if (stopped) "נעצרה" else "עצירה ידנית") { stopped = true }
                }
            }
        }
        item { Item("התראות", "כ-0 התראות בשבוע") }
        item { Item("הרשאות", "לא נדרשות הרשאות") }
        item { Item("אחסון ומטמון", "${Formatter.formatShortFileSize(ctx, size)} בשימוש באחסון הפנימי") }
        item { Item("נתונים ניידים ו-Wi-Fi", "לא נעשה שימוש בנתונים") }
        item { Item("זמן מסך") }
        item { Item("שימוש בסוללה של האפליקציה", "לא נעשה שימוש מאז הטעינה המלאה האחרונה") }
        item { Item("פתיחה כברירת מחדל", "לא הוגדרו ברירות מחדל") }
        item { Header("גרסה") }
        item { Item("גרסה $version") }
    }
}

@Composable
private fun ActionButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp)
    ) { Text(text, color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 14.sp) }
}

@Composable
private fun NetworkPage(pop: () -> Unit) {
    val ctx = LocalContext.current
    Page("רשת ואינטרנט", pop) {
        item { Item("אינטרנט", "Wi-Fi ונתונים", onClick = { SystemActions.openInternetPanel(ctx) }) }
        item { Item("כרטיסי SIM") }
        item { Item("מצב טיסה", trailing = { FakeSwitch(false) }) }
        item { Item("נקודה לשיתוף אינטרנט ושיתוף אינטרנט בין מכשירים", "מושבת") }
        item { Item("חוסך הנתונים", "מושבת") }
        item { Item("VPN", "ללא") }
        item { Item("DNS פרטי", "אוטומטי") }
    }
}

@Composable
private fun DevicesPage(pop: () -> Unit) {
    val ctx = LocalContext.current
    val pods = AirPods.fresh(AirPods.state.value)
    Page("מכשירים מחוברים", pop) {
        if (pods != null) item { Item("AirPods", "מחובר") }
        item { Item("התאמה של מכשיר חדש", onClick = { SystemActions.openBluetooth(ctx) }) }
        item { Item("מכשירים שמורים", "הצגת הכול") }
        item { Item("העדפות חיבור", "Bluetooth, ‏Android Auto") }
    }
}

@Composable
private fun BatteryPage(pop: () -> Unit) {
    val ctx = LocalContext.current
    val v = remember { SystemActions.batteryPercent(ctx) }
    val charging = remember { SystemActions.batteryCharging(ctx) }
    Page("סוללה", pop) {
        item {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text("$v%", fontSize = 44.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(if (charging) "בטעינה" else "הסוללה תספיק עד הלילה", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Item("שימוש בסוללה") }
        item { Item("חיסכון בסוללה", "מושבת", trailing = { FakeSwitch(false) }) }
        item { Item("סוללה דינמית", trailing = { FakeSwitch(true) }) }
        item { Item("אחוז הטעינה בסוללה", "הצגת אחוז הטעינה בשורת הסטטוס", trailing = { FakeSwitch(true) }) }
    }
}

@Composable
private fun StoragePage(pop: () -> Unit) {
    val ctx = LocalContext.current
    val st = remember { StatFs(Environment.getDataDirectory().path) }
    val used = st.totalBytes - st.availableBytes
    Page("אחסון", pop) {
        item {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(Formatter.formatShortFileSize(ctx, used), fontSize = 40.sp, color = MaterialTheme.colorScheme.onSurface)
                Text("בשימוש מתוך ${Formatter.formatShortFileSize(ctx, st.totalBytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Item("מערכת") }
        item { Item("אפליקציות") }
        item { Item("תמונות וסרטונים") }
        item { Item("אודיו") }
        item { Item("מסמכים ואחר") }
        item { Item("קבצים זמניים") }
    }
}

@Composable
private fun SoundPage(pop: () -> Unit) {
    val ctx = LocalContext.current
    var media by remember { mutableFloatStateOf(SystemActions.volume(ctx, AudioManager.STREAM_MUSIC)) }
    var ring by remember { mutableFloatStateOf(SystemActions.volume(ctx, AudioManager.STREAM_RING)) }
    var alarm by remember { mutableFloatStateOf(SystemActions.volume(ctx, AudioManager.STREAM_ALARM)) }
    Page("צליל ורטט", pop) {
        item { VolumeRow("עוצמת המדיה", media) { media = it; SystemActions.setVolume(ctx, it, AudioManager.STREAM_MUSIC) } }
        item { VolumeRow("עוצמת השיחה", ring) { ring = it; SystemActions.setVolume(ctx, it, AudioManager.STREAM_RING) } }
        item { VolumeRow("עוצמת השעון המעורר", alarm) { alarm = it; SystemActions.setVolume(ctx, it, AudioManager.STREAM_ALARM) } }
        item { Item("נא לא להפריע", "מושבת") }
        item { Item("רטט ומשוב פיזי") }
        item { Item("רינגטון לטלפון") }
    }
}

@Composable
private fun VolumeRow(title: String, v: Float, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(title, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
        Slider(value = v, onValueChange = onChange)
    }
}

@Composable
private fun DisplayPage(pop: () -> Unit) {
    val ctx = LocalContext.current
    var b by remember { mutableFloatStateOf(SystemActions.brightness(ctx)) }
    val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    Page("תצוגה ונגיעה", pop) {
        item {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text("רמת הבהירות", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                Slider(value = b, onValueChange = { b = it; SystemActions.setBrightness(ctx, it) })
            }
        }
        item { Item("בהירות מותאמת", trailing = { FakeSwitch(false) }) }
        item { Item("עיצוב כהה", if (dark) "מופעל" else "מושבת") }
        item { Item("גודל התצוגה והטקסט") }
        item { Item("זמן קצוב לכיבוי המסך", "אחרי 30 שניות של חוסר פעילות") }
        item { Item("סיבוב אוטומטי של המסך", if (SystemActions.rotationLocked(ctx)) "מושבת" else "מופעל") }
    }
}

@Composable
private fun AboutPage(pop: () -> Unit, openReal: () -> Unit) {
    val ctx = LocalContext.current
    val name = remember { Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL }
    Page("מידע על הטלפון", pop) {
        item { Item("שם המכשיר", name) }
        item { Item("דגם", Build.MODEL) }
        item { Header("פרטי המכשיר") }
        item { Item("גרסת Android", Build.VERSION.RELEASE) }
        item { Item("מספר Build", Build.DISPLAY) }
        item { Item("תיקון האבטחה", Build.VERSION.SECURITY_PATCH) }
    }
}

@Composable
private fun GenericPage(title: String, pop: () -> Unit) {
    Page(title, pop) {
        item { Item("כללי") }
        item { Item("מתקדם") }
        item { Item("עזרה ומשוב") }
    }
}
