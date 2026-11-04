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
import androidx.compose.ui.graphics.asImageBitmap
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
        // Each screen keeps its scroll position, so coming back lands where you were.
        val state = remember(title) { ScrollMemory.states.getOrPut(title) { androidx.compose.foundation.lazy.LazyListState() } }
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 40.dp), content = content)
    }
}

private object ScrollMemory {
    val states = HashMap<String, androidx.compose.foundation.lazy.LazyListState>()
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
        route == "look" -> LookScreen(c, pop)
        route == "assistant" -> AssistantScreen(c, pop)
        route == "zmanim" -> ZmanimScreen(c, push, pop, host)
        route == "island" -> IslandScreen(c, pop, host)
        route == "wallpapers" -> WallpapersScreen(c, pop)
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
            StatusItem(c, "גישה להתראות (אי דינמי ונקודות)", com.glass.launcher.feature.NotifListener.isEnabled(ctx), "אישור") {
                host.openSystem(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
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
            RowItem(c, "תיקיות", "${p.folders.size}", chevron = true) { push("folders") }
            RowItem(c, "הגנת ההגדרות", if (p.decoyEnabled) "פעילה" else "כבויה", chevron = true) { push("protect") }
        }
        group(c, "מה יופיע") {
            ToggleItem(c, "ספריית אפליקציות", p.libraryEnabled) { p.libraryEnabled = it }
            ToggleItem(c, "מרכז שליטה (החלקה למטה מהחצי העליון)", p.controlCenterEnabled) { p.controlCenterEnabled = it }
            ToggleItem(c, "מסך ווידג'טים", p.widgetsEnabled) { p.widgetsEnabled = it }
            ToggleItem(c, "חיפוש (החלקה למטה מהחצי התחתון)", p.searchEnabled) { p.searchEnabled = it }
            RowItem(c, "ווידג'טים", "${p.widgets.size}", chevron = true) { push("widgets") }
            RowItem(c, "החלקות מהקצה", if (p.gesturesEnabled) "פעילות" else "כבויות", chevron = true) { push("gestures") }
            RowItem(c, "אי דינמי", if (p.islandEnabled) "פעיל" else "כבוי", chevron = true) { push("island") }
            ToggleItem(c, "נקודות התראה על הסמלים", p.badges) { p.badges = it }
        }
        group(c, "מראה") {
            RowItem(c, "תצוגה ועיצוב", chevron = true) { push("look") }
            RowItem(c, "רקעים", chevron = true) { push("wallpapers") }
            RowItem(c, "מסך הבית", "${p.columns}×${p.rows}", chevron = true) { push("home") }
            RowItem(c, "ערכת סמלים", p.iconPack?.let { pk -> App.instance.icons.installedPacks().firstOrNull { it.pkg == pk }?.label } ?: "רגיל", chevron = true) { push("icons") }
            ToggleItem(c, "רטט עדין במגע", p.haptics) { p.haptics = it }
        }
        group(c, "זמני היום ושבת") {
            RowItem(c, "עיר", Cities.getOrElse(p.cityIndex) { Cities[0] }.name, chevron = true) { push("city") }
            RowItem(c, "זמנים ושבת", chevron = true) { push("zmanim") }
        }
        group(c, "עוזר קולי", "פותח את Gemini כמו סירי. כדי שגם לחיצה ארוכה על כפתור הצד תפתח אותו, הגדר את Gemini כעוזר ברירת המחדל בטלפון.") {
            ToggleItem(c, "הקשה כפולה על מקום ריק", p.assistantGesture == "double_tap") { p.assistantGesture = if (it) "double_tap" else "none" }
            RowItem(c, "האפליקציה שנפתחת", p.assistantPackage?.let { pk -> App.instance.repo.byPackage(pk)?.label } ?: "Gemini (אוטומטי)", chevron = true) { push("assistant") }
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
        group(c, footer = "מוסרת מהתצוגה – לא מופיעה בשום מקום. בתיקייה נסתרת – רק אחרי התנועה הסודית בספרייה. נעולה – מופיעה, אבל דורשת זיהוי.") {
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
    val folder = remember(tick) { p.folderOf[pkg] }
    Screen(e.label, c, pop) {
        group(c, "מצב") {
            listOf(AppMode.NORMAL, AppMode.LOCKED, AppMode.REMOVED).forEach { m ->
                RowItem(c, m.title, if (m == mode) "✓" else null) {
                    p.setMode(pkg, m)
                    if (m == AppMode.REMOVED) App.instance.repo.removeFromHome(pkg)
                }
            }
        }
        group(c, "תיקייה בספרייה", footer = "תיקייה שמסומנת 🔒 היא נסתרת: היא מופיעה רק אחרי התנועה הסודית בתחתית הספרייה, והאפליקציה לא מופיעה בשום מקום אחר.") {
            RowItem(c, "ללא תיקייה (לפי קטגוריה)", if (folder == null) "✓" else null) { p.setFolder(pkg, null) }
            p.folders.forEach { f ->
                RowItem(c, (if (f.hidden) "🔒 " else "") + f.name, if (folder == f.name) "✓" else null) {
                    if (f.hidden) { p.setMode(pkg, AppMode.HIDDEN, f.name); App.instance.repo.removeFromHome(pkg) }
                    else { p.setFolder(pkg, f.name); if (mode == AppMode.HIDDEN) p.setMode(pkg, AppMode.NORMAL); p.setFolder(pkg, f.name) }
                }
            }
        }
    }
}

@Composable
private fun FoldersScreen(c: IosColors, pop: () -> Unit) {
    val p = App.instance.prefs
    val tick by p.version.collectAsState()
    val folders = remember(tick) { p.folders }
    var name by remember { mutableStateOf("") }
    var newHidden by remember { mutableStateOf(true) }
    Screen("תיקיות", c, pop) {
        group(c, footer = "תיקייה נסתרת מופיעה רק כשגוררים למעלה בתחתית הספרייה, ממשיכים להחזיק ובמקביל נוגעים בחיישן טביעת האצבע – היא נפתחת מיד כשהאצבע מזוהה. כל התיקיות הנסתרות נחשפות יחד, ונעלמות ביציאה מהספרייה.") {
            folders.forEachIndexed { i, f ->
                val count = p.folderOf.count { it.value == f.name }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, color = c.text, fontSize = 17.sp)
                        Text("$count אפליקציות", color = c.sub, fontSize = 13.sp)
                    }
                    Text("נסתרת", color = c.sub, fontSize = 14.sp)
                    Switch(
                        checked = f.hidden,
                        onCheckedChange = { h -> p.folders = folders.mapIndexed { j, x -> if (j == i) x.copy(hidden = h) else x } },
                        colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF34C759), checkedThumbColor = Color.White, checkedBorderColor = Color.Transparent),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("מחיקה", color = Color(0xFFFF3B30), fontSize = 15.sp, modifier = Modifier.clickable {
                        p.folderOf = p.folderOf.filterValues { it != f.name }
                        p.folders = folders.filterIndexed { j, _ -> j != i }
                    })
                }
                Sep(c)
            }
        }
        group(c, footer = "בטלפון שלא מאפשר לקרוא טביעת אצבע בשקט, אחרי שנייה של החזקה יופיע חלון אימות. אפשר להוסיף גם כרטיס תיקייה ריק שמחזיקים אותו כדי לפתוח.") {
            ToggleItem(c, "כרטיס תיקייה ריק בתחתית הספרייה", p.decoyFolderCard) { p.decoyFolderCard = it }
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
                    if (n.isNotEmpty() && folders.none { it.name == n }) p.folders = folders + com.glass.launcher.data.FolderDef(n, newHidden)
                    name = ""
                })
            }
            Sep(c)
            ToggleItem(c, "התיקייה החדשה תהיה נסתרת", newHidden) { newHidden = it }
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

/** Changing the grid keeps every app and widget, re-flowing them into the new page size. */
private fun regrid(cols: Int, rows: Int) {
    val p = App.instance.prefs
    val items = p.homePages.flatten().filterNotNull()
    p.columns = cols
    p.rows = rows
    var pages: List<List<String?>> = listOf(List(cols * rows) { null })
    items.sortedBy { if (com.glass.launcher.data.HomeGrid.isWidget(it)) 0 else 1 }
        .forEach { pages = com.glass.launcher.data.HomeGrid.place(pages, cols, rows, it) }
    p.homePages = pages
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

@Composable
private fun LookScreen(c: IosColors, pop: () -> Unit) {
    val p = App.instance.prefs
    val tick by p.version.collectAsState()
    Screen("תצוגה ועיצוב", c, pop) {
        group(c, "שמות") {
            ToggleItem(c, "הצגת שמות מתחת לסמלים (בכל מקום)", p.showLabels) { p.showLabels = it }
        }
        group(c, "גודל הסמלים") {
            listOf("קטן", "בינוני", "גדול").forEachIndexed { i, t -> RowItem(c, t, if (p.iconSize == i) "✓" else null) { p.iconSize = i } }
        }
        group(c, "סגנון הסמלים", footer = "כמו באייפון: בהיר, כהה או צבוע בצבע אחד.") {
            listOf("light" to "בהיר", "dark" to "כהה", "tinted" to "צבוע").forEach { (k, t) ->
                RowItem(c, t, if (p.iconStyle == k) "✓" else null) { p.iconStyle = k }
            }
        }
        if (tick >= 0 && p.iconStyle == "tinted") group(c, "צבע") {
            listOf(0xFF7FB6FF to "כחול", 0xFFFFB36B to "כתום", 0xFF8BE38B to "ירוק", 0xFFD7A6FF to "סגול", 0xFFFF8FA3 to "ורוד", 0xFFE5E5EA to "אפור")
                .forEach { (col, t) -> RowItem(c, t, if (p.tintColor == col.toInt()) "✓" else null, color = Color(col)) { p.tintColor = col.toInt() } }
        }
        group(c, "Dock") {
            ToggleItem(c, "רקע זכוכית ל-Dock", p.dockBackground) { p.dockBackground = it }
            (3..5).forEach { n -> RowItem(c, "$n סמלים", if (p.dockCount == n) "✓" else null) { p.dockCount = n } }
        }
        group(c, "זכוכית", footer = "כמה בולט יהיה אפקט הזכוכית בכרטיסים, ב-Dock ובמרכז השליטה.") {
            listOf(0.5f to "עדין", 1f to "רגיל", 1.5f to "חזק").forEach { (v, t) -> RowItem(c, t, if (p.glassStrength == v) "✓" else null) { p.glassStrength = v } }
            ToggleItem(c, "הכהיית הרקע במסך הבית", p.dimWallpaper) { p.dimWallpaper = it }
        }
        group(c, "דפים", footer = "כמו באייפון באנגלית: ווידג'טים משמאל וספרייה מימין. אפשר להפוך.") {
            ToggleItem(c, "ספרייה משמאל וווידג'טים מימין", p.pagesRtl) { p.pagesRtl = it }
        }
        group(c, "מעל ה-Dock") {
            listOf("search" to "כפתור חיפוש", "dots" to "נקודות דפים", "none" to "כלום").forEach { (k, t) ->
                RowItem(c, t, if (p.aboveDock == k) "✓" else null) { p.aboveDock = k }
            }
        }
    }
}

@Composable
private fun WallpapersScreen(c: IosColors, pop: () -> Unit) {
    val ctx = LocalContext.current
    var done by remember { mutableStateOf<String?>(null) }
    val thumbs = remember {
        com.glass.launcher.feature.Wallpapers.presets.map { it to com.glass.launcher.feature.Wallpapers.render(it, 180, 380).asImageBitmap() }
    }
    Screen("רקעים", c, pop) {
        item {
            if (done != null) Text("הרקע \"$done\" הוגדר", color = Color(0xFF34C759), fontSize = 15.sp, modifier = Modifier.padding(8.dp))
        }
        thumbs.chunked(3).forEach { row ->
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    row.forEach { (preset, img) ->
                        Column(Modifier.weight(1f).clickable {
                            if (com.glass.launcher.feature.Wallpapers.apply(ctx, preset)) done = preset.name
                        }, horizontalAlignment = Alignment.CenterHorizontally) {
                            androidx.compose.foundation.Image(img, null, Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(14.dp)),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                            Text(preset.name, color = c.text, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun AssistantScreen(c: IosColors, pop: () -> Unit) {
    val p = App.instance.prefs
    val apps by App.instance.repo.apps.collectAsState()
    Screen("עוזר קולי", c, pop) {
        group(c) {
            RowItem(c, "Gemini (אוטומטי)", if (p.assistantPackage == null) "✓" else null) { p.assistantPackage = null }
            apps.forEach { e -> RowItem(c, e.label, if (p.assistantPackage == e.pkg) "✓" else null) { p.assistantPackage = e.pkg } }
        }
    }
}

@Composable
private fun IslandScreen(c: IosColors, pop: () -> Unit, host: LauncherSettingsActivity) {
    val p = App.instance.prefs
    val ctx = LocalContext.current
    Screen("אי דינמי", c, pop) {
        group(c, footer = if (LockService.isEnabled(ctx)) null else "צריך להפעיל קודם את שירות הנעילה במסך הראשי.") {
            ToggleItem(c, "אי דינמי", p.islandEnabled) { p.islandEnabled = it; if (!it) p.islandEditing = false }
            ToggleItem(c, "לחיצה ארוכה פותחת את Gemini", p.islandLongPressAssistant) { p.islandLongPressAssistant = it }
        }
        group(c, "מיקום וגודל", footer = "הפעל את ההתאמה: האי יוקף בכחול. אפשר לגרור אותו באצבע, ולכוונן בדיוק עם החיצים. ＋/− משנים את הגודל ושומרים על היחס בין רוחב לגובה. בסיום כבה את ההתאמה.") {
            ToggleItem(c, "התאמת מיקום", p.islandEditing) { on ->
                if (on) p.islandEnabled = true
                p.islandEditing = on
            }
            IslandPad(c)
            RowItem(c, "החזרה למיקום המצלמה האוטומטי", color = Blue) {
                p.islandW = -1; p.islandH = -1; p.islandX = -1; p.islandY = -1
            }
        }
        group(c, "מה הוא מציג", footer = "מוזיקה מתנגנת (לחיצה פותחת שליטה), חיבור מטען, מצב שקט/צלצול, פנס, AirPods שנפתחו ליד הטלפון. למוזיקה צריך \"גישה להתראות\".") {
            StatusItem(c, "גישה להתראות", com.glass.launcher.feature.NotifListener.isEnabled(ctx), "אישור") {
                host.openSystem(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            StatusItem(c, "בלוטות' ל-AirPods", AirPods.hasPermission(ctx), "אישור") { host.requestBluetooth() }
        }
    }
}

/** Fine controls: arrows move the island, +/- scale it keeping its proportions. */
@Composable
private fun IslandPad(c: IosColors) {
    val p = App.instance.prefs
    val tick by p.version.collectAsState()
    var step by remember { mutableStateOf(1) }
    fun move(dx: Int, dy: Int) {
        if (p.islandW <= 0) return
        p.islandX = (p.islandX + dx * step).coerceAtLeast(0)
        p.islandY = (p.islandY + dy * step).coerceAtLeast(0)
    }
    fun scale(sign: Int) {
        if (p.islandW <= 0) return
        val w = p.islandW
        val h = p.islandH
        val cx = p.islandX + w / 2
        val cy = p.islandY + h / 2
        val nw = (w + sign * step * 2).coerceIn(20, 1400)
        val nh = (h.toFloat() * nw / w).toInt().coerceAtLeast(10)
        p.islandW = nw; p.islandH = nh
        p.islandX = (cx - nw / 2).coerceAtLeast(0)
        p.islandY = (cy - nh / 2).coerceAtLeast(0)
    }
    @Composable
    fun Key(t: String, onClick: () -> Unit) = Box(
        Modifier.size(54.dp).clip(RoundedCornerShape(14.dp)).background(Blue.copy(alpha = 0.14f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(t, color = Blue, fontSize = 22.sp) }
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (p.islandW > 0) "מיקום ${p.islandX},${p.islandY} · גודל ${p.islandW}×${p.islandH} (פיקסלים)" else "הפעל את ההתאמה כדי להתחיל",
            color = c.sub, fontSize = 13.sp,
        )
        Spacer(Modifier.height(10.dp))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Key("▲") { move(0, -1) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Key("◀") { move(-1, 0) }
                    Spacer(Modifier.width(64.dp))
                    Key("▶") { move(1, 0) }
                }
                Key("▼") { move(0, 1) }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Key("−") { scale(-1) }
                    Spacer(Modifier.width(16.dp))
                    Text("גודל", color = c.text, fontSize = 16.sp)
                    Spacer(Modifier.width(16.dp))
                    Key("＋") { scale(1) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("צעד:", color = c.text, fontSize = 15.sp)
            listOf(1, 3, 10).forEach { s ->
                Spacer(Modifier.width(10.dp))
                Text("$s px", color = if (step == s) Color.White else Blue, fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (step == s) Blue else Color.Transparent)
                        .clickable { step = s }.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
    tick
}

@Composable
private fun ZmanimScreen(c: IosColors, push: (String) -> Unit, pop: () -> Unit, host: LauncherSettingsActivity) {
    val p = App.instance.prefs
    val ctx = LocalContext.current
    val day by com.glass.launcher.feature.Zmanim.day.collectAsState()
    val sh by com.glass.launcher.feature.Zmanim.shabbat.collectAsState()
    androidx.compose.runtime.LaunchedEffect(p.cityIndex, p.candleMinutes, p.havdalahMode) { com.glass.launcher.feature.Zmanim.refresh(force = true) }
    val f = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    Screen("זמני היום ושבת", c, pop) {
        group(c, footer = "הזמנים מגיעים מ-Hebcal לפי מיקום העיר (בגובה פני הים). הזמנים לידיעה בלבד – לפסיקה ולמנהג המקום יש לבדוק בלוח מוסמך או אצל רב.") {
            RowItem(c, "עיר", Cities.getOrElse(p.cityIndex) { Cities[0] }.name, chevron = true) { push("city") }
            RowItem(c, "פתיחת לוח הזמנים באתר ישיבה", color = Blue) {
                SystemActions.start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(com.glass.launcher.feature.Zmanim.YESHIVA_URL)))
            }
        }
        group(c, "הדלקת נרות – דקות לפני השקיעה") {
            RowItem(c, "ברירת המחדל של העיר (ירושלים 40, חיפה 30, אחרות 18)", if (p.candleMinutes == 0) "✓" else null) { p.candleMinutes = 0 }
            listOf(18, 20, 22, 30, 40).forEach { m -> RowItem(c, "$m דקות", if (p.candleMinutes == m) "✓" else null) { p.candleMinutes = m } }
        }
        group(c, "יציאת שבת") {
            RowItem(c, "צאת הכוכבים (8.5°)", if (p.havdalahMode == 0) "✓" else null) { p.havdalahMode = 0 }
            listOf(42, 50, 72).forEach { m -> RowItem(c, "$m דקות אחרי השקיעה", if (p.havdalahMode == m) "✓" else null) { p.havdalahMode = m } }
        }
        group(c, "היום" + (day?.place?.let { " · $it" } ?: "")) {
            val d = day
            if (d == null) RowItem(c, "אין חיבור לאינטרנט עדיין")
            else d.list.forEach { z -> RowItem(c, z.title, z.time.format(f)) }
        }
        group(c, "השבת הקרובה") {
            val s = sh
            if (s == null) RowItem(c, "אין חיבור לאינטרנט עדיין") else {
                s.parasha?.let { RowItem(c, "פרשה", it) }
                s.candles?.let { RowItem(c, "הדלקת נרות", it.format(f)) }
                s.havdalah?.let { RowItem(c, "יציאת שבת", it.format(f)) }
            }
        }
    }
}
