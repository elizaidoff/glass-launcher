package com.glass.launcher.settings

import android.Manifest
import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glass.launcher.App
import com.glass.launcher.data.AppMode
import com.glass.launcher.data.GestureAction
import com.glass.launcher.data.GestureZone
import com.glass.launcher.feature.AirPods
import com.glass.launcher.feature.Cities
import com.glass.launcher.feature.SystemActions
import com.glass.launcher.lock.Auth
import com.glass.launcher.lock.LockGuard
import com.glass.launcher.lock.LockService
import com.glass.launcher.ui.IconImage

/** The launcher's own settings, in Hebrew. Opening it always requires fingerprint or face. */
class LauncherSettingsActivity : ComponentActivity() {

    private val stack = mutableStateListOf("main")
    private var authed = mutableStateOf(false)
    private val refresh = mutableStateOf(0)
    private var pendingWidgetId = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("page")?.let { stack.add(it) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else finish() }
        })
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = if (dark) IosColors.dark else IosColors.light
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.fillMaxSize().background(colors.bg)) {
                    if (authed.value) {
                        refresh.value
                        SettingsNav(stack.last(), colors, push = { stack.add(it) }, pop = {
                            if (stack.size > 1) stack.removeAt(stack.lastIndex) else finish()
                        }, host = this@LauncherSettingsActivity)
                    }
                }
            }
        }
        openingSystemScreen = true
        Auth.prompt(this, "הגדרות הלאנצ'ר", onSuccess = { authed.value = true }, onFail = { finish() })
    }

    override fun onResume() {
        super.onResume()
        refresh.value++
        App.instance.prefs.touch()
    }

    override fun onStop() {
        super.onStop()
        // Re-ask for fingerprint next time, unless we only stepped into a system screen we opened.
        if (!isChangingConfigurations && !openingSystemScreen) finish()
        openingSystemScreen = false
    }

    private var openingSystemScreen = false

    fun openSystem(intent: Intent) {
        openingSystemScreen = true
        LockGuard.startUnlocked(this, intent)
    }

    fun requestHome() {
        val rm = getSystemService(RoleManager::class.java)
        openingSystemScreen = true
        runCatching { startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 10) }
            .onFailure { openSystem(Intent(Settings.ACTION_HOME_SETTINGS)) }
    }

    fun requestBluetooth() {
        openingSystemScreen = true
        requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), 11)
    }

    fun addSystemWidget(info: AppWidgetProviderInfo) {
        val host = App.instance.widgetHost
        val id = host.allocateAppWidgetId()
        pendingWidgetId = id
        val mgr = AppWidgetManager.getInstance(this)
        if (mgr.bindAppWidgetIdIfAllowed(id, info.provider)) {
            configureOrAdd(id, info)
        } else {
            openingSystemScreen = true
            LockGuard.unlock(App.instance.settingsPackage)
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider),
                20,
            )
        }
    }

    private fun configureOrAdd(id: Int, info: AppWidgetProviderInfo) {
        if (info.configure != null) {
            openingSystemScreen = true
            runCatching { App.instance.widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, 21, null) }
                .onFailure { addWidget(id) }
        } else addWidget(id)
    }

    private fun addWidget(id: Int) {
        val p = App.instance.prefs
        p.widgets = p.widgets + "appwidget:$id"
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val id = pendingWidgetId
        when (requestCode) {
            20 -> if (resultCode == RESULT_OK) {
                AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.let { configureOrAdd(id, it) }
            } else App.instance.widgetHost.deleteAppWidgetId(id)
            21 -> if (resultCode == RESULT_OK) addWidget(id) else App.instance.widgetHost.deleteAppWidgetId(id)
        }
        refresh.value++
    }

    companion object {
        fun open(ctx: Context, page: String? = null) {
            ctx.startActivity(
                Intent(ctx, LauncherSettingsActivity::class.java)
                    .putExtra("page", page)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

// ------------------------------------------------------------------ iOS look

data class IosColors(val bg: Color, val card: Color, val text: Color, val sub: Color, val sep: Color) {
    companion object {
        val light = IosColors(Color(0xFFF2F2F7), Color.White, Color.Black, Color(0xFF8E8E93), Color(0x22000000))
        val dark = IosColors(Color.Black, Color(0xFF1C1C1E), Color.White, Color(0xFF8E8E93), Color(0x33FFFFFF))
    }
}

private val Blue = Color(0xFF0A84FF)

@Composable
private fun Screen(title: String, c: IosColors, pop: (() -> Unit)?, content: LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        if (pop != null) {
            Row(Modifier.clickable(onClick = pop).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, null, tint = Blue, modifier = Modifier.size(28.dp))
                Text("חזרה", color = Blue, fontSize = 17.sp)
            }
        } else Spacer(Modifier.height(40.dp))
        Text(title, color = c.text, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 40.dp), content = content)
    }
}

private fun LazyListScope.group(c: IosColors, header: String? = null, footer: String? = null, rows: @Composable () -> Unit) {
    item {
        Column {
            if (header != null) Text(header, color = c.sub, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp, top = 18.dp))
            val v by App.instance.prefs.version.collectAsState()
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.card)) { key(v) { rows() } }
            if (footer != null) Text(footer, color = c.sub, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
        }
    }
}

@Composable
private fun RowItem(c: IosColors, title: String, value: String? = null, chevron: Boolean = false, color: Color = c.text, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = color, fontSize = 17.sp, modifier = Modifier.weight(1f))
        if (value != null) Text(value, color = c.sub, fontSize = 17.sp)
        if (chevron) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.sub, modifier = Modifier.size(22.dp))
        }
    }
    Sep(c)
}

@Composable
private fun Sep(c: IosColors) = Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(c.sep))

@Composable
private fun ToggleItem(c: IosColors, title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = c.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF34C759), checkedThumbColor = Color.White, checkedBorderColor = Color.Transparent),
        )
    }
    Sep(c)
}

@Composable
private fun StatusItem(c: IosColors, title: String, ok: Boolean, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "✓" else "●", color = if (ok) Color(0xFF34C759) else Color(0xFFFF9F0A), fontSize = 17.sp)
        Spacer(Modifier.width(10.dp))
        Text(title, color = c.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
        if (!ok) Text(action, color = Blue, fontSize = 17.sp, modifier = Modifier.clickable(onClick = onClick).padding(4.dp))
    }
    Sep(c)
}

// ------------------------------------------------------------------ Screens

@Composable
private fun SettingsNav(route: String, c: IosColors, push: (String) -> Unit, pop: () -> Unit, host: LauncherSettingsActivity) {
    when {
        route == "main" -> MainScreen(c, push, host)
        route == "apps" -> AppsScreen(c, push, pop)
        route.startsWith("app:") -> AppModeScreen(route.removePrefix("app:"), c, pop)
        route == "folders" -> FoldersScreen(c, pop)
        route == "widgets" -> WidgetsScreen(c, push, pop)
        route == "addwidget" -> AddWidgetScreen(c, pop, host)
        route == "protect" -> ProtectScreen(c, pop, host)
        route == "home" -> HomeScreen(c, pop)
        route == "icons" -> IconsScreen(c, pop)
        route == "city" -> CityScreen(c, pop)
        route == "gestures" -> GesturesScreen(c, push, pop)
        route.startsWith("gzone:") -> GestureZoneScreen(GestureZone.valueOf(route.removePrefix("gzone:")), c, pop)
        else -> MainScreen(c, push, host)
    }
}

@Composable
private fun MainScreen(c: IosColors, push: (String) -> Unit, host: LauncherSettingsActivity) {
    val ctx = LocalContext.current
    val p = App.instance.prefs
    val tick by p.version.collectAsState()
    val isHome = remember(tick) { ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME) }
    val service = LockService.isEnabled(ctx)
    Screen("זכוכית", c, null) {
        group(c, "הכנה", "אם כפתור הנגישות אפור ("+"הגדרה מוגבלת"+"): בהגדרות ← אפליקציות ← זכוכית ← ⋮ למעלה ← \"התרת הגדרות מוגבלות\", ואז שוב כאן.") {
            StatusItem(c, "מסך הבית שלך", isHome, "הגדרה") { host.requestHome() }
            StatusItem(c, "שירות נעילה (נגישות)", service, "הפעלה") { host.openSystem(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            StatusItem(c, "בלוטות' ל-AirPods", AirPods.hasPermission(ctx), "אישור") { host.requestBluetooth() }
            StatusItem(c, "בהירות וסיבוב מסך", SystemActions.canWriteSettings(ctx), "אישור") {
                host.openSystem(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }
            StatusItem(c, "נא לא להפריע", SystemActions.dndAllowed(ctx), "אישור") {
                host.openSystem(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
            if (!Auth.deviceSecure(ctx)) StatusItem(c, "לא מוגדרת נעילת מסך בטלפון – הנעילות לא יגנו", false, "הגדרה") {
                host.openSystem(Intent(Settings.ACTION_SECURITY_SETTINGS))
            }
        }
        group(c, "פרטיות") {
            RowItem(c, "ניהול אפליקציות", "הסתרה, נעילה", chevron = true) { push("apps") }
            RowItem(c, "תיקיות נסתרות", "${p.hiddenFolders.size}", chevron = true) { push("folders") }
            RowItem(c, "הגנת ההגדרות", if (p.decoyEnabled) "פעילה" else "כבויה", chevron = true) { push("protect") }
        }
        group(c, "מה יופיע") {
            ToggleItem(c, "ספריית אפליקציות", p.libraryEnabled) { p.libraryEnabled = it }
            ToggleItem(c, "מרכז שליטה (החלקה מימין למעלה)", p.controlCenterEnabled) { p.controlCenterEnabled = it }
            ToggleItem(c, "מסך ווידג'טים", p.widgetsEnabled) { p.widgetsEnabled = it }
            ToggleItem(c, "חיפוש (החלקה למטה)", p.searchEnabled) { p.searchEnabled = it }
            RowItem(c, "ווידג'טים", "${p.widgets.size}", chevron = true) { push("widgets") }
            RowItem(c, "החלקות מהקצה", if (p.gesturesEnabled) "פעילות" else "כבויות", chevron = true) { push("gestures") }
        }
        group(c, "מראה") {
            RowItem(c, "מסך הבית", "${p.columns}×${p.rows}", chevron = true) { push("home") }
            RowItem(c, "ערכת סמלים", p.iconPack?.let { pk -> App.instance.icons.installedPacks().firstOrNull { it.pkg == pk }?.label } ?: "רגיל", chevron = true) { push("icons") }
            RowItem(c, "עיר לזריחה ושקיעה", Cities.getOrElse(p.cityIndex) { Cities[0] }.name, chevron = true) { push("city") }
            ToggleItem(c, "רטט עדין במגע", p.haptics) { p.haptics = it }
        }
        group(c, "עוזר קולי", "פותח את Gemini כמו סירי. כדי שגם לחיצה ארוכה על כפתור הצד תפתח אותו, הגדר את Gemini כעוזר ברירת המחדל בטלפון.") {
            ToggleItem(c, "הקשה כפולה על מקום ריק", p.assistantGesture == "double_tap") { p.assistantGesture = if (it) "double_tap" else "none" }
            RowItem(c, "בדיקה", color = Blue) { SystemActions.openAssistant(ctx) }
        }
    }
}

@Composable
private fun AppsScreen(c: IosColors, push: (String) -> Unit, pop: () -> Unit) {
    val p = App.instance.prefs
    val tick by p.version.collectAsState()
    val apps by App.instance.repo.apps.collectAsState()
    var q by remember { mutableStateOf("") }
    val filtered = apps.filter { q.isBlank() || it.label.contains(q.trim(), true) }
    Screen("ניהול אפליקציות", c, pop) {
        item {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.card).padding(12.dp)) {
                if (q.isEmpty()) Text("חיפוש", color = c.sub, fontSize = 17.sp)
                BasicTextField(q, { q = it }, singleLine = true, textStyle = TextStyle(color = c.text, fontSize = 17.sp),
                    cursorBrush = SolidColor(Blue), modifier = Modifier.fillMaxWidth())
            }
        }
        group(c, footer = "מוסרת מהתצוגה – לא מופיעה בשום מקום. בתיקייה נסתרת – רק בתיקייה הנסתרת שבתחתית הספרייה. נעולה – מופיעה, אבל דורשת זיהוי.") {
            filtered.forEach { e ->
                val mode = remember(tick, e.pkg) { p.modeOf(e.pkg) }
                Row(
                    Modifier.fillMaxWidth().clickable { push("app:" + e.pkg) }.padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconImage(e, 34.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(e.label, color = c.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    Text(if (mode == AppMode.NORMAL) "" else mode.title, color = c.sub, fontSize = 15.sp)
                }
                Sep(c)
            }
        }
    }
}

@Composable
private fun AppModeScreen(pkg: String, c: IosColors, pop: () -> Unit) {
    val p = App.instance.prefs
    val tick by p.version.collectAsState()
    val e = App.instance.repo.byPackage(pkg) ?: return
    val mode = remember(tick) { p.modeOf(pkg) }
    val folder = remember(tick) { p.hidden[pkg] }
    Screen(e.label, c, pop) {
        group(c, "מצב") {
            AppMode.entries.filter { it != AppMode.HIDDEN }.forEach { m ->
                RowItem(c, m.title, if (m == mode) "✓" else null) {
                    p.setMode(pkg, m)
                    if (m == AppMode.REMOVED) App.instance.repo.removeFromHome(pkg)
                }
            }
        }
        group(c, "תיקייה נסתרת") {
            p.hiddenFolders.forEach { f ->
                RowItem(c, f, if (mode == AppMode.HIDDEN && folder == f) "✓" else null) {
                    p.setMode(pkg, AppMode.HIDDEN, f)
                    App.instance.repo.removeFromHome(pkg)
                }
            }
        }
    }
}

@Composable
private fun FoldersScreen(c: IosColors, pop: () -> Unit) {
    val p = App.instance.prefs
    val tick by p.version.collectAsState()
    val folders = remember(tick) { p.hiddenFolders }
    var name by remember { mutableStateOf("") }
    Screen("תיקיות נסתרות", c, pop) {
        group(c, footer = "התיקיות מופיעות רק אחרי גלילה עד תחתית הספרייה, החזקה של 3 שניות ואימות. הן נעלמות בכל יציאה מהספרייה.") {
            folders.forEach { f ->
                val count = p.hidden.count { it.value == f }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f, color = c.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    Text("$count", color = c.sub, fontSize = 17.sp)
                    if (folders.size > 1) {
                        Spacer(Modifier.width(14.dp))
                        Text("מחיקה", color = Color(0xFFFF3B30), fontSize = 15.sp, modifier = Modifier.clickable {
                            val target = folders.first { it != f }
                            p.hidden = p.hidden.mapValues { if (it.value == f) target else it.value }
                            p.hiddenFolders = folders - f
                        })
                    }
                }
                Sep(c)
            }
        }
        group(c, "תיקייה חדשה") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (name.isEmpty()) Text("שם התיקייה", color = c.sub, fontSize = 17.sp)
                    BasicTextField(name, { name = it }, singleLine = true, textStyle = TextStyle(color = c.text, fontSize = 17.sp),
                        cursorBrush = SolidColor(Blue), modifier = Modifier.fillMaxWidth())
                }
                Text("הוספה", color = Blue, fontSize = 17.sp, modifier = Modifier.clickable {
                    val n = name.trim()
                    if (n.isNotEmpty() && n !in folders) p.hiddenFolders = folders + n
                    name = ""
                })
            }
        }
    }
}

private val builtinWidgets = listOf(
    "builtin:clock" to "שעון ותאריך",
    "builtin:hebdate" to "תאריך עברי, זריחה ושקיעה",
    "builtin:battery" to "סוללות (טלפון ו-AirPods)",
    "builtin:airpods" to "AirPods – גדול",
)

@Composable
private fun WidgetsScreen(c: IosColors, push: (String) -> Unit, pop: () -> Unit) {
    val ctx = LocalContext.current
    val p = App.instance.prefs
    val tick by p.version.collectAsState()
    val list = remember(tick) { p.widgets }
    val mgr = remember { AppWidgetManager.getInstance(ctx) }
    fun title(w: String) = builtinWidgets.firstOrNull { it.first == w }?.second
        ?: w.removePrefix("appwidget:").toIntOrNull()?.let { mgr.getAppWidgetInfo(it)?.loadLabel(ctx.packageManager) } ?: w
    Screen("ווידג'טים", c, pop) {
        group(c, "מופיעים כעת (לפי הסדר)") {
            list.forEachIndexed { i, w ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title(w), color = c.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    if (i > 0) Text("▲", color = Blue, modifier = Modifier.clickable {
                        val m = list.toMutableList(); m.add(i - 1, m.removeAt(i)); p.widgets = m
                    }.padding(6.dp))
                    if (i < list.lastIndex) Text("▼", color = Blue, modifier = Modifier.clickable {
                        val m = list.toMutableList(); m.add(i + 1, m.removeAt(i)); p.widgets = m
                    }.padding(6.dp))
                    Text("הסרה", color = Color(0xFFFF3B30), fontSize = 15.sp, modifier = Modifier.clickable {
                        w.removePrefix("appwidget:").toIntOrNull()?.let { App.instance.widgetHost.deleteAppWidgetId(it) }
                        p.widgets = list - w
                    }.padding(6.dp))
                }
                Sep(c)
            }
        }
        group(c, "ווידג'טים של זכוכית") {
            builtinWidgets.filter { it.first !in list }.forEach { (k, t) ->
                RowItem(c, "הוספה: $t", color = Blue) { p.widgets = list + k }
            }
        }
        group(c, "ווידג'טים של אפליקציות") {
            RowItem(c, "הוספת ווידג'ט מאפליקציה", chevron = true) { push("addwidget") }
        }
    }
}

@Composable
private fun AddWidgetScreen(c: IosColors, pop: () -> Unit, host: LauncherSettingsActivity) {
    val ctx = LocalContext.current
    val providers = remember {
        AppWidgetManager.getInstance(ctx).installedProviders
            .filter { App.instance.prefs.isVisible(it.provider.packageName) }
            .sortedBy { it.loadLabel(ctx.packageManager) }
    }
    Screen("הוספת ווידג'ט", c, pop) {
        group(c) {
            providers.forEach { info ->
                val app = runCatching {
                    ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(info.provider.packageName, 0)).toString()
                }.getOrDefault("")
                RowItem(c, info.loadLabel(ctx.packageManager), app) { host.addSystemWidget(info); pop() }
            }
        }
    }
}

@Composable
private fun ProtectScreen(c: IosColors, pop: () -> Unit, host: LauncherSettingsActivity) {
    val p = App.instance.prefs
    val ctx = LocalContext.current
    Screen("הגנת ההגדרות", c, pop) {
        group(c, footer = "כשההגדרות נפתחות מופיע מסך טעינה של עד 3 שניות. אם תיגע בחיישן טביעת האצבע בזמן הזה – ייפתחו ההגדרות האמיתיות. אחרת ייפתח חיקוי שמראה רק את האפליקציות הגלויות.") {
            ToggleItem(c, "חיקוי הגדרות", p.decoyEnabled) { p.decoyEnabled = it }
        }
        group(c, footer = "דרך כניסה נוספת להגדרות האמיתיות מתוך החיקוי: החזקה של 3 שניות על הכותרת \"הגדרות\" ואז אימות.") {
            RowItem(c, "קריאת אצבע שקטה", if (Auth.silentFingerprintAvailable(ctx)) "נתמכת בטלפון הזה" else "לא נתמכת – השתמש בכניסה מהכותרת")
        }
        group(c, footer = "כשכבוי – אפליקציות נעולות ואפליקציות מתיקייה נסתרת נפתחות בלי זיהוי.") {
            ToggleItem(c, "נעילת אפליקציות", p.lockEnabled) { p.lockEnabled = it }
        }
        group(c) {
            RowItem(c, "פתיחת ההגדרות האמיתיות עכשיו", color = Blue) { host.openSystem(Intent(Settings.ACTION_SETTINGS)) }
        }
    }
}

@Composable
private fun HomeScreen(c: IosColors, pop: () -> Unit) {
    val p = App.instance.prefs
    val tick by p.version.collectAsState()
    Screen("מסך הבית", c, pop) {
        group(c, "עמודות") { (3..5).forEach { n -> RowItem(c, "$n", if (p.columns == n) "✓" else null) { regrid(n, p.rows) } } }
        group(c, "שורות") { (5..7).forEach { n -> RowItem(c, "$n", if (p.rows == n) "✓" else null) { regrid(p.columns, n) } } }
        group(c) {
            ToggleItem(c, "הצגת שמות אפליקציות", p.showLabels) { p.showLabels = it }
            ToggleItem(c, "אפליקציות חדשות למסך הבית", p.newAppsToHome) { p.newAppsToHome = it }
        }
    }
    tick
}

/** Changing the grid keeps every app, re-flowing them into the new page size. */
private fun regrid(cols: Int, rows: Int) {
    val p = App.instance.prefs
    val apps = p.homePages.flatten().filterNotNull()
    p.columns = cols
    p.rows = rows
    val per = cols * rows
    p.homePages = apps.chunked(per).map { (it + List(per) { null }).take(per) }.ifEmpty { listOf(List(per) { null }) }
}

@Composable
private fun IconsScreen(c: IosColors, pop: () -> Unit) {
    val p = App.instance.prefs
    val tick by p.version.collectAsState()
    val packs = remember { App.instance.icons.installedPacks() }
    Screen("ערכת סמלים", c, pop) {
        group(c, footer = "אפשר להתקין מחנות Play ערכת סמלים בסגנון iOS (חפש \"iOS icon pack\"), והיא תופיע כאן.") {
            RowItem(c, "רגיל (צורה אחידה)", if (p.iconPack == null) "✓" else null) { p.iconPack = null; App.instance.icons.clear() }
            packs.forEach { pk -> RowItem(c, pk.label, if (p.iconPack == pk.pkg) "✓" else null) { p.iconPack = pk.pkg; App.instance.icons.clear() } }
        }
    }
    tick
}

@Composable
private fun CityScreen(c: IosColors, pop: () -> Unit) {
    val p = App.instance.prefs
    val tick by p.version.collectAsState()
    Screen("עיר", c, pop) {
        group(c) { Cities.forEachIndexed { i, city -> RowItem(c, city.name, if (p.cityIndex == i) "✓" else null) { p.cityIndex = i } } }
    }
    tick
}

@Composable
private fun GesturesScreen(c: IosColors, push: (String) -> Unit, pop: () -> Unit) {
    val p = App.instance.prefs
    val ctx = LocalContext.current
    val service = LockService.isEnabled(ctx)
    Screen("החלקות מהקצה", c, pop) {
        group(c, footer = if (service) "החלקה כלפי מעלה מתחתית המסך – כל שליש מבצע פעולה אחרת."
            else "צריך להפעיל קודם את שירות הנעילה במסך הראשי (חלק \"הכנה\").") {
            ToggleItem(c, "החלקות מהקצה", p.gesturesEnabled) { p.gesturesEnabled = it }
        }
        group(c, "פעולות") {
            GestureZone.entries.filter { p.sideEdges || (it != GestureZone.EDGE_LEFT && it != GestureZone.EDGE_RIGHT) }.forEach { z ->
                RowItem(c, z.title, p.gestureAction(z).title, chevron = true) { push("gzone:" + z.name) }
            }
        }
        group(c, "מראה", footer = "כיסוי הכפתורים: פס כהה מעל כפתורי המערכת, כך שהם לא נראים ולא נלחצים, וההחלקות נקלטות בו. " +
            "אפשרות נוספת: בהגדרות הטלפון ← מערכת ← תנועות ← ניווט במערכת ← \"ניווט באמצעות תנועות\" – מעלים את הכפתורים לגמרי; אז עדיף להשאיר כאן את הכיסוי כבוי ולהשתמש בקצוות הצד.") {
            ToggleItem(c, "כיסוי כפתורי המערכת", p.coverNavButtons) { p.coverNavButtons = it }
            ToggleItem(c, "פס בסגנון אייפון בתחתית", p.showHomeBar) { p.showHomeBar = it }
            ToggleItem(c, "גם החלקה מקצות הצד", p.sideEdges) { p.sideEdges = it }
        }
    }
}

@Composable
private fun GestureZoneScreen(z: GestureZone, c: IosColors, pop: () -> Unit) {
    val p = App.instance.prefs
    Screen(z.title, c, pop) {
        group(c) {
            GestureAction.entries.forEach { a ->
                RowItem(c, a.title, if (p.gestureAction(z) == a) "✓" else null) { p.setGestureAction(z, a) }
            }
        }
    }
}
