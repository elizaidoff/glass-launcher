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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.ViewModule
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Vibration
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
import com.glass.launcher.ui.IconTile
import com.glass.launcher.ui.IosSwitch
import com.glass.launcher.ui.Kit
import com.glass.launcher.ui.KitText
import com.glass.launcher.ui.hideSystemNav
import androidx.compose.ui.graphics.graphicsLayer

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
                KitText {
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
        }
        openingSystemScreen = true
        Auth.prompt(this, "הגדרות הלאנצ'ר", onSuccess = { authed.value = true }, onFail = { finish() })
    }

    override fun onResume() {
        super.onResume()
        hideSystemNav()
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

    private val backupOut = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(App.instance.prefs.exportJson().toByteArray()) }
            android.widget.Toast.makeText(this, "הגיבוי נשמר", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    private val backupIn = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val ok = runCatching { contentResolver.openInputStream(uri)?.use { App.instance.prefs.importJson(it.readBytes().decodeToString()) } ?: false }.getOrDefault(false)
            android.widget.Toast.makeText(this, if (ok) "ההגדרות שוחזרו" else "זה לא קובץ גיבוי של זכוכית", android.widget.Toast.LENGTH_SHORT).show()
            if (ok) App.instance.repo.reload()
        }
    }

    fun backup() {
        openingSystemScreen = true
        backupOut.launch("glass-backup-" + java.time.LocalDate.now() + ".json")
    }

    fun restore() {
        openingSystemScreen = true
        backupIn.launch(arrayOf("application/json", "*/*"))
    }

    /** Lets the launcher keep running in the background, so gestures and the island never stop. */
    fun requestBatteryExemption() {
        openingSystemScreen = true
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure { openSystem(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    fun requestSmsRole() {
        openingSystemScreen = true
        @Suppress("DEPRECATION")
        runCatching { startActivityForResult(getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_SMS), 12) }
            .onFailure { openSystem(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
    }

    fun requestBluetooth() {
        openingSystemScreen = true
        requestPermissions(arrayOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        ), 11)
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
    // Each screen keeps its scroll position, so coming back lands where you were.
    val state = remember(title) { ScrollMemory.states.getOrPut(title) { androidx.compose.foundation.lazy.LazyListState() } }
    // iPhone-style large title: big at the top, shrinks into the bar as you scroll.
    val collapsed by remember { androidx.compose.runtime.derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 60 } }
    val barAlpha by androidx.compose.animation.core.animateFloatAsState(if (collapsed) 1f else 0f, label = "bar")
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            if (pop != null) {
                Row(Modifier.align(Alignment.CenterStart).clickable(onClick = pop).padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, null, tint = Blue, modifier = Modifier.size(30.dp))
                    Text("חזרה", color = Blue, fontSize = 17.sp)
                }
            }
            Text(
                title, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.align(Alignment.Center).graphicsLayer { alpha = barAlpha },
            )
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(0.5.dp).background(c.sep.copy(alpha = c.sep.alpha * barAlpha)))
        }
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 40.dp)) {
            item(key = "large-title") {
                Text(title, color = c.text, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 8.dp))
            }
            content()
        }
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
private fun RowItem(
    c: IosColors, title: String, value: String? = null, chevron: Boolean = false, color: Color = c.text,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null, tile: Color = Kit.gray,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = if (icon != null) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { IconTile(icon, tile); Spacer(Modifier.width(14.dp)) }
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
private fun ToggleItem(
    c: IosColors, title: String, checked: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null, tile: Color = Kit.gray,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { IconTile(icon, tile); Spacer(Modifier.width(14.dp)) }
        Text(title, color = c.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
        IosSwitch(checked, onChange)
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
        route.startsWith("app:") -> AppModeScreen(route.removePrefix("app:"), c, push, pop)
        route.startsWith("cover:") -> CoverScreen(route.removePrefix("cover:"), c, pop)
        route == "folders" -> FoldersScreen(c, pop)
        route == "widgets" -> WidgetsScreen(c, push, pop)
        route == "addwidget" -> AddWidgetScreen(c, pop, host)
        route == "protect" -> ProtectScreen(c, pop, host)
        route == "home" -> HomeScreen(c, pop)
        route == "icons" -> IconsScreen(c, pop)
        route == "city" -> CityScreen(c, pop)
        route == "gestures" -> NavScreen(c, push, pop)
        route.startsWith("secure:") -> SecureColorScreen(route.removePrefix("secure:"), c, pop)
        route == "look" -> LookScreen(c, pop)
        route == "status" -> StatusScreen(c, pop, host)
        route == "assistantMain" -> AssistantMainScreen(c, push, pop)
        route == "pods" -> PodsScreen(c, pop, host)
        route == "swipes" -> SwipesScreen(c, pop)
        route == "cc" -> CcScreen(c, push, pop)
        route == "ccadd" -> CcAddScreen(c, pop)
        route == "assistant" -> AssistantScreen(c, pop)
        route == "zmanim" -> ZmanimScreen(c, push, pop, host)
        route == "island" -> IslandScreen(c, pop, host)
        route == "messages" -> MessagesSettingsScreen(c, pop, host)
        route == "wallpapers" -> WallpapersScreen(c, pop)
        else -> MainScreen(c, push, host)
    }
}

@Composable
private fun MainScreen(c: IosColors, push: (String) -> Unit, host: LauncherSettingsActivity) {
    val ctx = LocalContext.current
    val p = App.instance.prefs
    val tick by p.version.collectAsState()
    val checks = remember(tick) { systemChecks(ctx) }
    val missing = checks.count { !it.ok }
    val R = androidx.compose.material.icons.Icons.Rounded
    Screen("הגדרות", c, null) {
        // Header card, like the Apple ID card at the top of iPhone Settings.
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(c.card).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val logo = remember { App.instance.icons.iconForPackage(ctx.packageName, 174)?.asImageBitmap() }
                if (logo != null) androidx.compose.foundation.Image(logo, null, Modifier.size(58.dp).clip(com.glass.launcher.ui.SquircleShape))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("זכוכית", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    val v = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
                    Text("גרסה $v", color = c.sub, fontSize = 14.sp)
                }
            }
        }
        group(c) {
            RowItem(
                c, "מצב מערכת", if (missing == 0) "הכל תקין" else "$missing דורשים אישור",
                chevron = true, icon = R.CheckCircle, tile = if (missing == 0) Kit.green else Kit.orange,
            ) { push("status") }
        }
        group(c) {
            RowItem(c, "מסך הבית", "${p.columns}×${p.rows}", chevron = true, icon = R.GridView, tile = Kit.blue) { push("home") }
            RowItem(c, "תצוגה ועיצוב", chevron = true, icon = R.TextFields, tile = Kit.blue) { push("look") }
            RowItem(c, "רקעים", chevron = true, icon = R.Wallpaper, tile = Kit.teal) { push("wallpapers") }
            RowItem(c, "ערכת סמלים", p.iconPack?.let { pk -> App.instance.icons.installedPacks().firstOrNull { it.pkg == pk }?.label } ?: "אחיד", chevron = true, icon = R.Apps, tile = Kit.indigo) { push("icons") }
            RowItem(c, "ווידג'טים", "${p.widgets.size}", chevron = true, icon = R.Widgets, tile = Kit.purple) { push("widgets") }
        }
        group(c) {
            RowItem(c, "מרכז שליטה", if (p.controlCenterEnabled) "פעיל" else "כבוי", chevron = true, icon = R.ToggleOn, tile = Kit.gray) { push("cc") }
            RowItem(c, "החלקות במסך הבית", chevron = true, icon = R.Swipe, tile = Kit.indigo) { push("swipes") }
            RowItem(c, "ניווט ותנועות של אייפון", when (p.navMode) { "off" -> "כבוי"; "android" -> "של אנדרואיד"; else -> "פעיל" }, chevron = true, icon = R.TouchApp, tile = Kit.blue) { push("gestures") }
            RowItem(c, "אי דינמי", if (p.islandEnabled) "פעיל" else "כבוי", chevron = true, icon = R.Circle, tile = Color.Black) { push("island") }
            ToggleItem(c, "נקודות התראה על הסמלים", p.badges, icon = R.Notifications, tile = Kit.red) { p.badges = it }
            ToggleItem(c, "ספריית אפליקציות", p.libraryEnabled, icon = R.ViewModule, tile = Kit.blue) { p.libraryEnabled = it }
            ToggleItem(c, "מסך ווידג'טים", p.widgetsEnabled, icon = R.Dashboard, tile = Kit.purple) { p.widgetsEnabled = it }
            ToggleItem(c, "חיפוש", p.searchEnabled, icon = R.Search, tile = Kit.gray) { p.searchEnabled = it }
        }
        group(c) {
            RowItem(c, "ניהול אפליקציות", "הסתרה, נעילה, הסוואה", chevron = true, icon = R.Lock, tile = Kit.blue) { push("apps") }
            RowItem(c, "תיקיות", "${p.folders.size}", chevron = true, icon = R.Folder, tile = Kit.teal) { push("folders") }
            RowItem(c, "הגנת ההגדרות", if (p.decoyEnabled) "פעילה" else "כבויה", chevron = true, icon = R.Shield, tile = Kit.gray) { push("protect") }
        }
        group(c) {
            RowItem(c, "זמני היום ושבת", Cities.getOrElse(p.cityIndex) { Cities[0] }.name, chevron = true, icon = R.WbTwilight, tile = Kit.orange) { push("zmanim") }
            RowItem(c, "AirPods", chevron = true, icon = R.Headphones, tile = Kit.gray) { push("pods") }
            RowItem(c, "הודעות", if (com.glass.launcher.messages.SmsStore.isDefault(host)) "ברירת המחדל" else null, chevron = true, icon = R.Sms, tile = Kit.green) { push("messages") }
            RowItem(c, "עוזר קולי", p.assistantPackage?.let { pk -> App.instance.repo.byPackage(pk)?.label } ?: "Gemini", chevron = true, icon = R.Mic, tile = Kit.pink) { push("assistantMain") }
            ToggleItem(c, "רטט עדין במגע", p.haptics, icon = R.Vibration, tile = Kit.red) { p.haptics = it }
            ToggleItem(c, "כפתור צף (Assistive Touch)", p.assistiveTouch, icon = R.TouchApp, tile = Kit.gray) { p.assistiveTouch = it; LockService.instance?.assistive?.refresh() }
        }
        group(c, footer = "שומר את כל ההגדרות, הסידור והתיקיות לקובץ. הקוד לא נשמר בגיבוי.") {
            RowItem(c, "גיבוי ההגדרות לקובץ", icon = R.Backup, tile = Kit.blue) { host.backup() }
            RowItem(c, "שחזור מגיבוי", icon = R.Restore, tile = Kit.blue) { host.restore() }
            RowItem(c, "מדריך ההתחלה", icon = R.School, tile = Kit.green) { host.startActivity(Intent(host, com.glass.launcher.ui.WelcomeActivity::class.java)) }
        }
    }
}

/** One permission / service the launcher needs, with how to fix it. */
private data class Check(val title: String, val ok: Boolean, val action: String, val fix: (LauncherSettingsActivity) -> Unit)

private fun systemChecks(ctx: Context): List<Check> {
    val isHome = ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)
    return listOfNotNull(
        Check("מסך הבית שלך", isHome, "הגדרה") { it.requestHome() },
        Check("שירות נעילה (נגישות)", LockService.isEnabled(ctx), "הפעלה") { it.openSystem(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
        Check("גישה להתראות", com.glass.launcher.feature.NotifListener.isEnabled(ctx), "אישור") { it.openSystem(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
        Check("בלוטות' ומיקום (AirPods)", AirPods.hasPermission(ctx), "אישור") { it.requestBluetooth() },
        Check("בהירות וסיבוב מסך", SystemActions.canWriteSettings(ctx), "אישור") {
            it.openSystem(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
        },
        Check("נא לא להפריע", SystemActions.dndAllowed(ctx), "אישור") { it.openSystem(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
        Check("ריצה ברקע בלי הגבלת סוללה", ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName), "אישור") { it.requestBatteryExemption() },
        if (!Auth.deviceSecure(ctx)) Check("נעילת מסך בטלפון (בלעדיה הנעילות לא מגנות)", false, "הגדרה") { it.openSystem(Intent(Settings.ACTION_SECURITY_SETTINGS)) } else null,
    )
}

@Composable
private fun StatusScreen(c: IosColors, pop: () -> Unit, host: LauncherSettingsActivity) {
    val ctx = LocalContext.current
    val tick by App.instance.prefs.version.collectAsState()
    val checks = remember(tick) { systemChecks(ctx) }
    Screen("מצב מערכת", c, pop) {
        group(c, footer = "אם כפתור הנגישות או ההתראות אפור (\"הגדרה מוגבלת\"): הגדרות הטלפון ← אפליקציות ← זכוכית ← ⋮ למעלה ← \"התרת הגדרות מוגבלות\", ואז שוב כאן.") {
            checks.forEach { ch -> StatusItem(c, ch.title, ch.ok, ch.action) { ch.fix(host) } }
        }
    }
}

@Composable
private fun AssistantMainScreen(c: IosColors, push: (String) -> Unit, pop: () -> Unit) {
    val p = App.instance.prefs
    val ctx = LocalContext.current
    Screen("עוזר קולי", c, pop) {
        group(c, footer = "פותח את Gemini כמו סירי. כדי שגם לחיצה ארוכה על כפתור הצד תפתח אותו, הגדר את Gemini כעוזר ברירת המחדל בטלפון.") {
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
private fun AppModeScreen(pkg: String, c: IosColors, push: (String) -> Unit, pop: () -> Unit) {
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
        group(c, "הסוואה", footer = "האפליקציה תיראה בכל מקום עם הסמל והשם של אפליקציה אחרת. בפתיחה מופיע מסך טעינה של עד 3 שניות: טביעת האצבע שלך פותחת את האפליקציה האמיתית, וכל אחד אחר יקבל את האפליקציה שהיא מתחפשת אליה. אחרי שפתחת, היא נפתחת בלי זיהוי עד שהמסך נכבה.") {
            val cover = p.disguise[pkg]
            RowItem(c, "מתחפשת ל", cover?.let { App.instance.repo.byPackage(it)?.label ?: it } ?: "ללא", chevron = true) { push("cover:$pkg") }
            if (!Auth.silentFingerprintAvailable(LocalContext.current)) RowItem(c, "שים לב: הטלפון לא תומך בטביעת אצבע שקטה, אז תמיד תיפתח האפליקציה המחופשת", color = Color(0xFFFF9F0A))
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
    val ctx = LocalContext.current
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
        group(c, footer = if (Auth.silentFingerprintAvailable(ctx)) "הטלפון שלך תומך בקריאת טביעת אצבע שקטה – התנועה בספרייה תעבוד בלי שום חלון."
            else "הטלפון הזה לא מאפשר לקרוא טביעת אצבע בשקט, ולכן התנועה בספרייה לא תעבוד. אפשר לחשוף את התיקיות מכאן (ההגדרות כבר מוגנות בזיהוי).") {
            RowItem(c, "חשיפת התיקיות הנסתרות בספרייה עכשיו", color = Blue) {
                LockGuard.hiddenRevealed.value = true
                (ctx as? android.app.Activity)?.finish()
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
        item { PinSection(c) }
        group(c) {
            RowItem(c, "פתיחת ההגדרות האמיתיות עכשיו", color = Blue) { host.openSystem(Intent(Settings.ACTION_SETTINGS)) }
        }
    }
}

@Composable
private fun MessagesSettingsScreen(c: IosColors, pop: () -> Unit, host: LauncherSettingsActivity) {
    val p = App.instance.prefs
    val tick by p.version.collectAsState()
    val isDefault = remember(tick) { com.glass.launcher.messages.SmsStore.isDefault(host) }
    Screen("הודעות", c, pop) {
        group(c, footer = if (isDefault) "ההודעות מתקבלות ונשמרות כאן. כל ההודעות נשמרות במאגר של הטלפון, כך שאפשר לחזור לאפליקציה אחרת בלי לאבד כלום."
            else "בלי זה, 'הודעות' מציגה ושולחת, אבל ההודעות הנכנסות וההתראות נשארות באפליקציה הקודמת.") {
            if (isDefault) RowItem(c, "אפליקציית ברירת המחדל להודעות", "✓")
            else RowItem(c, "הגדרה כאפליקציית ההודעות של הטלפון", color = Blue) {
                host.requestSmsRole()
            }
        }
        group(c, footer = "באייפון הודעות SMS ירוקות ו-iMessage כחולות. כאן כולן SMS/MMS – בחר את הצבע שנעים לך.") {
            ToggleItem(c, "בועות ירוקות", p.messagesGreen) { p.messagesGreen = it }
        }
        group(c, footer = "RCS (הודעות צ'אט של גוגל) שמור רק לאפליקציה של גוגל, ולא זמין לאף אפליקציה אחרת.") {
            RowItem(c, "SMS ו-MMS (קבוצות ותמונות)", "נתמך")
        }
    }
}

/** The passcode for locked apps (instead of, or as well as, the fingerprint). */
@Composable
private fun PinSection(c: IosColors) {
    val p = App.instance.prefs
    val tick by p.version.collectAsState()
    var setting by remember { mutableStateOf(false) }
    Column {
        Text("אופן הפתיחה של אפליקציה נעולה", color = c.sub, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp, top = 18.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.card)) {
            key(tick) {
                val has = p.pinHash != null
                listOf("bio" to "טביעת אצבע / פנים", "both" to "טביעת אצבע, ואם לא – קוד", "pin" to "קוד בלבד").forEach { (k, t) ->
                    RowItem(c, t, if (p.lockMethod == k) "✓" else null) {
                        if (k != "bio" && !has) setting = true
                        p.lockMethod = k
                    }
                }
                RowItem(c, if (has) "שינוי הקוד" else "הגדרת קוד", color = Blue) { setting = true }
                if (has) RowItem(c, "מחיקת הקוד", color = Color(0xFFFF3B30)) { p.pinHash = null; p.lockMethod = "bio" }
            }
        }
        Text("הקוד נשמר רק בטלפון, מוצפן. ההגדרות האמיתיות תמיד נפתחות רק בטביעת אצבע שקטה.", color = c.sub, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
    }
    if (setting) PinSetDialog(c, onDone = { pin ->
        if (pin != null) { p.pinLength = pin.length; p.pinHash = p.hashPin(pin); if (p.lockMethod == "bio") p.lockMethod = "both" }
        else if (p.pinHash == null) p.lockMethod = "bio"
        setting = false
    })
}

@Composable
private fun PinSetDialog(c: IosColors, onDone: (String?) -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(onDismissRequest = { onDone(null) }) {
        Column(Modifier.width(300.dp).clip(RoundedCornerShape(22.dp)).background(c.card).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (first == null) "קוד חדש (4–6 ספרות)" else "שוב, לאימות", color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (error) Text("הקודים לא תואמים, נסה שוב", color = Color(0xFFFF3B30), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(14.dp))
            BasicTextField(
                text, { v -> if (v.length <= 6 && v.all { it.isDigit() }) text = v },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                textStyle = androidx.compose.ui.text.TextStyle(color = c.text, fontSize = 26.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, letterSpacing = 8.sp),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.sep.copy(alpha = 0.25f)).padding(12.dp),
            )
            Spacer(Modifier.height(16.dp))
            Row {
                Text("ביטול", color = Blue, fontSize = 17.sp, modifier = Modifier.weight(1f).clickable { onDone(null) }.padding(8.dp))
                Text("המשך", color = if (text.length >= 4) Blue else c.sub, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable {
                        if (text.length < 4) return@clickable
                        val f = first
                        if (f == null) { first = text; text = ""; error = false }
                        else if (f == text) onDone(text)
                        else { first = null; text = ""; error = true }
                    }.padding(8.dp))
            }
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
        group(c, "עמודים", footer = "מחיקת עמוד מורידה ממנו את האפליקציות והווידג'טים. האפליקציות לא נמחקות מהטלפון, והן יופיעו בספרייה.") {
            val pages = p.homePages
            pages.forEachIndexed { i, page ->
                val apps = page.count { v -> v != null && !com.glass.launcher.data.HomeGrid.isWidget(v) && App.instance.repo.byPackage(v) != null && p.isVisible(v) }
                val widgets = page.count { com.glass.launcher.data.HomeGrid.isWidget(it) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("עמוד ${i + 1}", color = c.text, fontSize = 17.sp)
                        Text(
                            if (apps + widgets == 0) "ריק" else listOfNotNull(
                                if (apps > 0) "$apps אפליקציות" else null, if (widgets > 0) "$widgets ווידג'טים" else null,
                            ).joinToString(" · "),
                            color = c.sub, fontSize = 13.sp,
                        )
                    }
                    if (pages.size > 1) Text("מחיקה", color = Color(0xFFFF3B30), fontSize = 15.sp, modifier = Modifier.clickable {
                        val ws = page.filterNotNull().filter { com.glass.launcher.data.HomeGrid.isWidget(it) }
                        p.homePages = p.homePages.filterIndexed { j, _ -> j != i }.ifEmpty { listOf(List(p.columns * p.rows) { null }) }
                        ws.forEach { w -> com.glass.launcher.ui.removeWidgetEverywhere(w, fromToday = false) }
                    }.padding(6.dp))
                }
                Sep(c)
            }
        }
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
private fun NavScreen(c: IosColors, push: (String) -> Unit, pop: () -> Unit) {
    val p = App.instance.prefs
    val ctx = LocalContext.current
    val service = LockService.isEnabled(ctx)
    val modes = listOf(
        "off" to "כבוי (הכפתורים של אנדרואיד)",
        "dark" to "א' – כיסוי כהה",
        "transparent" to "א2 – כיסוי שקוף",
        "appcolor" to "א3 – בצבע האפליקציה",
        "android" to "ב' – מצב החלקה של אנדרואיד",
    )
    Screen("ניווט", c, pop) {
        group(c, footer = when (p.navMode) {
            "android" -> "הפעל בהגדרות הטלפון: מערכת ← תנועות ← ניווט במערכת ← \"ניווט באמצעות תנועות\". התנועות של אנדרואיד זהות לאייפון; זכוכית מוסיפה רק את הקו הלבן."
            "off" -> "ההחלקות של אייפון כבויות."
            else -> "השאר בטלפון ניווט ב-3 כפתורים. במסך הבית ובאפליקציות של זכוכית הכפתורים מוסתרים לגמרי; באפליקציות של אחרים הם מכוסים. למעלה = בית, למעלה והחזקה = האחרונים, לאורך הפס = אפליקציה קודמת, מהקצה = חזרה."
        } + if (!service) "\nצריך להפעיל קודם את שירות הנעילה (מצב מערכת)." else "") {
            modes.forEach { (k, t) -> RowItem(c, t, if (p.navMode == k) "✓" else null) { p.navMode = k } }
        }
        if (p.navMode != "off" && p.navMode != "android") {
            group(c, "כפתורי אנדרואיד מתחת לכיסוי") {
                ToggleItem(c, "לחיצה רגילה עליהם עדיין עובדת", p.navButtonsTap) { p.navButtonsTap = it }
                RowItem(c, "סדר הכפתורים", if (p.navOrder == "rhb") "אחרונים · בית · חזרה" else "חזרה · בית · אחרונים", chevron = true) {
                    p.navOrder = if (p.navOrder == "rhb") "brh" else "rhb"
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("גובה הכיסוי", color = c.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    Text("−", color = Blue, fontSize = 22.sp, modifier = Modifier.clickable { p.navAdjust = p.navAdjust - 2 }.padding(horizontal = 12.dp))
                    Text("${p.navAdjust}", color = c.sub, fontSize = 15.sp)
                    Text("＋", color = Blue, fontSize = 22.sp, modifier = Modifier.clickable { p.navAdjust = p.navAdjust + 2 }.padding(horizontal = 12.dp))
                }
            }
            group(c, "חזרה בהחלקה מהקצה") {
                listOf("right" to "מהקצה הימני", "left" to "מהקצה השמאלי", "both" to "משני הקצוות", "none" to "כבוי").forEach { (k, t) ->
                    RowItem(c, t, if (p.backEdge == k) "✓" else null) { p.backEdge = k }
                }
            }
            if (p.secureApps.isNotEmpty()) group(c, "אפליקציות שלא מאפשרות צילום", footer = "בהן אי אפשר לדגום צבע. בחר לכל אחת איך הכיסוי ייראה.") {
                p.secureApps.forEach { (pkg, col) ->
                    val label = App.instance.repo.byPackage(pkg)?.label ?: pkg
                    RowItem(c, label, when (col) { 0 -> "שקוף"; 1 -> "כהה"; else -> "צבע" }, chevron = true) { push("secure:$pkg") }
                }
            }
        }
        group(c, "משיכה מלמעלה בכל מקום", footer = "משיכה מראש המסך – בכל אפליקציה – פותחת את מרכז ההתראות או מרכז השליטה של זכוכית. בנעילה ובמסך מלא נשאר הווילון של אנדרואיד. במרכז השליטה יש כפתור לווילון של המערכת.") {
            ToggleItem(c, "פעיל", p.topPull) { p.topPull = it }
            if (p.topPull) listOf("split" to "שמאל התראות · ימין מרכז שליטה", "notifications" to "כל הרוחב: התראות", "control" to "כל הרוחב: מרכז שליטה").forEach { (k, t) ->
                RowItem(c, t, if (p.topPullSplit == k) "✓" else null) { p.topPullSplit = k }
            }
        }
    }
}

@Composable
private fun SecureColorScreen(pkg: String, c: IosColors, pop: () -> Unit) {
    val p = App.instance.prefs
    val label = App.instance.repo.byPackage(pkg)?.label ?: pkg
    val colours = listOf(0xFFFFFFFF, 0xFFF2F2F7, 0xFF1C1C1E, 0xFF000000, 0xFF0A84FF, 0xFF34C759, 0xFFFF3B30, 0xFFFF9500, 0xFFAF52DE, 0xFF5856D6, 0xFF8E8E93, 0xFF00796B)
    Screen(label, c, pop) {
        group(c) {
            RowItem(c, "שקוף (הכפתורים נראים)", if (p.secureApps[pkg] == 0) "✓" else null) { p.secureApps = p.secureApps + (pkg to 0) }
            RowItem(c, "כהה", if (p.secureApps[pkg] == 1) "✓" else null) { p.secureApps = p.secureApps + (pkg to 1) }
        }
        group(c, "צבע") {
            colours.chunked(6).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly) {
                    row.forEach { col ->
                        Box(
                            Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(col))
                                .then(if (p.secureApps[pkg] == col.toInt()) Modifier.background(Color(col)) else Modifier)
                                .clickable { p.secureApps = p.secureApps + (pkg to col.toInt()) },
                            contentAlignment = Alignment.Center,
                        ) { if (p.secureApps[pkg] == col.toInt()) Text("✓", color = if (col == 0xFFFFFFFF || col == 0xFFF2F2F7) Color.Black else Color.White) }
                    }
                }
            }
        }
        group(c) { RowItem(c, "הסרה מהרשימה", color = Color(0xFFFF3B30)) { p.secureApps = p.secureApps - pkg; pop() } }
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
        group(c, "גופן", footer = "הגופן של המסכים של זכוכית. שינוי ייכנס לתוקף בפתיחה הבאה של המסך.") {
            listOf("heebo" to "היבו (כמו אייפון)", "system" to "גופן הטלפון").forEach { (k, t) ->
                RowItem(c, t, if (p.fontName == k) "✓" else null) { p.fontName = k }
            }
        }
        group(c, "מעבר בין עמודים") {
            listOf("slide" to "החלקה (כמו אייפון)", "scale" to "התרחקות", "fade" to "דהייה", "cube" to "קובייה").forEach { (k, t) ->
                RowItem(c, t, if (p.pageEffect == k) "✓" else null) { p.pageEffect = k }
            }
        }
        group(c, footer = "כבוי – פחות אנימציות וטשטוש קל יותר, לטלפון איטי או לחיסכון בסוללה.") {
            ToggleItem(c, "תנועה חלקה ואפקטים מלאים", p.smoothMode) { p.smoothMode = it }
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
        group(c, "ספריית אפליקציות") {
            ToggleItem(c, "קבוצת \"הצעות\"", p.libSuggestions) { p.libSuggestions = it }
            ToggleItem(c, "קבוצת \"נוספו לאחרונה\"", p.libRecentlyAdded) { p.libRecentlyAdded = it }
            ToggleItem(c, "קטגוריות אוטומטיות (כבוי = רק התיקיות שלך + \"שאר האפליקציות\")", p.libAutoCategories) { p.libAutoCategories = it }
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
            ToggleItem(c, "להציג גם כשהמסך לרוחב", p.islandLandscape) { p.islandLandscape = it }
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

@Composable
private fun CoverScreen(pkg: String, c: IosColors, pop: () -> Unit) {
    val p = App.instance.prefs
    val apps by App.instance.repo.apps.collectAsState()
    val me = App.instance.repo.byPackage(pkg)
    Screen("הסוואה: ${me?.label ?: ""}", c, pop) {
        group(c, footer = "כדאי לבחור אפליקציה שהסרת מהתצוגה, כדי שלא יופיעו שתיים זהות.") {
            RowItem(c, "ללא הסוואה", if (p.disguise[pkg] == null) "✓" else null) { p.disguise = p.disguise - pkg; pop() }
            apps.filter { it.pkg != pkg && it.pkg !in p.disguise }.sortedBy { if (it.pkg in p.removed) 0 else 1 }.forEach { e ->
                RowItem(c, e.label + if (e.pkg in p.removed) "  (מוסרת מהתצוגה)" else "", if (p.disguise[pkg] == e.pkg) "✓" else null) {
                    p.disguise = p.disguise + (pkg to e.pkg); App.instance.icons.clear(); pop()
                }
            }
        }
    }
}

private val swipeActions = listOf("notifications" to "מרכז התראות", "control" to "מרכז שליטה", "search" to "חיפוש", "none" to "כלום")

@Composable
private fun SwipesScreen(c: IosColors, pop: () -> Unit) {
    val p = App.instance.prefs
    Screen("החלקות למטה", c, pop) {
        group(c, "מהחצי העליון – צד שמאל", footer = "כמו באייפון: משמאל התראות, מימין מרכז שליטה.") {
            swipeActions.forEach { (k, t) -> RowItem(c, t, if (p.swipeTopLeft == k) "✓" else null) { p.swipeTopLeft = k } }
        }
        group(c, "מהחצי העליון – צד ימין") {
            swipeActions.forEach { (k, t) -> RowItem(c, t, if (p.swipeTopRight == k) "✓" else null) { p.swipeTopRight = k } }
        }
        group(c, "מהחצי התחתון") {
            swipeActions.forEach { (k, t) -> RowItem(c, t, if (p.swipeBottom == k) "✓" else null) { p.swipeBottom = k } }
        }
    }
}

val CcTileNames = linkedMapOf(
    "rotation" to "נעילת סיבוב מסך",
    "dnd" to "נא לא להפריע",
    "torch" to "פנס",
    "camera" to "מצלמה",
    "calculator" to "מחשבון",
    "alarm" to "שעון מעורר",
    "timer" to "טיימר",
    "screenshot" to "צילום מסך",
    "lock" to "נעילת מסך",
    "airplane" to "מצב טיסה",
    "hotspot" to "נקודה חמה",
    "notifications" to "התראות המערכת",
    "shade" to "הווילון של אנדרואיד",
)

private fun ccTitle(id: String) = CcTileNames[id]
    ?: id.removePrefix("app:").let { pk -> App.instance.repo.byPackage(pk)?.label ?: pk }

@Composable
private fun CcScreen(c: IosColors, push: (String) -> Unit, pop: () -> Unit) {
    val p = App.instance.prefs
    val tick by p.version.collectAsState()
    val tiles = remember(tick) { p.ccTiles }
    Screen("מרכז שליטה", c, pop) {
        group(c, "חלקים גדולים") {
            ToggleItem(c, "חיבורים (Wi-Fi, נתונים, בלוטות', שיתוף)", p.ccConnect) { p.ccConnect = it }
            ToggleItem(c, "AirPods", p.ccPods) { p.ccPods = it }
            ToggleItem(c, "בהירות ועוצמה", p.ccSliders) { p.ccSliders = it }
        }
        group(c, "כפתורים (לפי הסדר)", footer = "ארבעת הראשונים יושבים ליד הבהירות והעוצמה, והשאר בשורות מתחת.") {
            tiles.forEachIndexed { i, t ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(ccTitle(t), color = c.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    if (i > 0) Text("▲", color = Blue, modifier = Modifier.clickable {
                        val m = tiles.toMutableList(); m.add(i - 1, m.removeAt(i)); p.ccTiles = m
                    }.padding(6.dp))
                    if (i < tiles.lastIndex) Text("▼", color = Blue, modifier = Modifier.clickable {
                        val m = tiles.toMutableList(); m.add(i + 1, m.removeAt(i)); p.ccTiles = m
                    }.padding(6.dp))
                    Text("הסרה", color = Color(0xFFFF3B30), fontSize = 15.sp, modifier = Modifier.clickable { p.ccTiles = tiles - t }.padding(6.dp))
                }
                Sep(c)
            }
            RowItem(c, "＋ הוספת כפתור", color = Blue, chevron = true) { push("ccadd") }
        }
    }
}

@Composable
private fun CcAddScreen(c: IosColors, pop: () -> Unit) {
    val p = App.instance.prefs
    val apps by App.instance.repo.apps.collectAsState()
    val have = p.ccTiles
    Screen("הוספת כפתור", c, pop) {
        group(c, "כפתורים") {
            CcTileNames.filterKeys { it !in have }.forEach { (k, t) -> RowItem(c, t, color = Blue) { p.ccTiles = have + k; pop() } }
        }
        group(c, "קיצור לאפליקציה") {
            apps.filter { p.isVisible(it.pkg) && "app:${it.pkg}" !in have }.forEach { e ->
                RowItem(c, e.label, color = Blue) { p.ccTiles = have + "app:${e.pkg}"; pop() }
            }
        }
    }
}

@Composable
private fun PodsScreen(c: IosColors, pop: () -> Unit, host: LauncherSettingsActivity) {
    val ctx = LocalContext.current
    var t by remember { mutableStateOf(0) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        AirPods.start(ctx)
        onDispose { AirPods.stop(ctx) }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); t++ } }
    val st by AirPods.state.collectAsState()
    val bt = ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled == true
    val loc = ctx.getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled == true
    val mp = runCatching { ctx.packageManager.getPackageInfo("com.pryshedko.materialpods", 0); true }.getOrDefault(false)
    Screen("AirPods", c, pop) {
        group(c, "מצב", footer = "פתח את מארז האוזניות ליד הטלפון כשהמסך הזה פתוח. אם \"שידורי AirPods\" נשאר 0 – הטלפון לא קולט אותן; אם יש מספרים אבל אין אחוזים – שלח לי צילום מסך של המסך הזה.") {
            StatusItem(c, "הרשאת בלוטות' ומיקום", AirPods.hasPermission(ctx), "אישור") { host.requestBluetooth() }
            StatusItem(c, "בלוטות' דולק", bt, "הפעלה") { host.openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            StatusItem(c, "שירותי מיקום דולקים (נדרש לסריקה)", loc, "הפעלה") { host.openSystem(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            StatusItem(c, "גישה להתראות (לקריאה מ-MaterialPods)", com.glass.launcher.feature.NotifListener.isEnabled(ctx), "אישור") {
                host.openSystem(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        group(c, "מה נקלט") {
            t
            RowItem(c, "סורק", if (AirPods.isScanning()) "פעיל" else (AirPods.scanError.ifEmpty { "לא פעיל" }))
            RowItem(c, "שידורי Apple", "${AirPods.appleSeen}")
            RowItem(c, "שידורי AirPods", "${AirPods.podsSeen}")
            if (AirPods.lastRaw.isNotEmpty()) RowItem(c, "אחרון (${AirPods.lastRssi} dBm)", AirPods.lastRaw.take(30))
            val s = st
            RowItem(c, "סוללה", if (s == null) "אין עדיין" else listOfNotNull(
                s.left?.let { "שמאל $it%" }, s.right?.let { "ימין $it%" }, s.case?.let { "מארז $it%" },
            ).joinToString(" · ") + " · " + AirPods.ageText(s))
        }
        group(c, "MaterialPods", footer = "אם MaterialPods מציגה התראה עם האחוזים, הלאנצ'ר לוקח אותם ממנה. ההתראה שלה צריכה להיות מופעלת בהגדרות שלה.") {
            RowItem(c, "מותקנת", if (mp) "כן" else "לא")
            RowItem(c, "טקסט אחרון מההתראה", AirPods.lastNotifText.ifEmpty { "לא התקבל" }.take(60))
        }
    }
}
