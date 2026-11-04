package com.glass.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import com.glass.launcher.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale

class AppEntry(
    val pkg: String,
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val category: Int,
    val isSystem: Boolean,
    val installTime: Long,
    val info: LauncherActivityInfo,
)

class AppRepository(private val ctx: Context) {
    private val launcherApps = ctx.getSystemService(LauncherApps::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = reload()
        override fun onPackageAdded(packageName: String, user: UserHandle) = reload()
        override fun onPackageChanged(packageName: String, user: UserHandle) = reload()
        override fun onPackagesAvailable(p: Array<out String>, user: UserHandle, r: Boolean) = reload()
        override fun onPackagesUnavailable(p: Array<out String>, user: UserHandle, r: Boolean) = reload()
    }

    init {
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        reload()
    }

    fun byPackage(pkg: String): AppEntry? = _apps.value.firstOrNull { it.pkg == pkg }

    /**
     * Our own companion apps (Messages, Phone, …) live in this one package but are separate apps
     * on the home screen, so each gets its own key: "com.glass.launcher/MessagesActivity".
     */
    fun keyFor(pkg: String, cls: String): String =
        if (pkg == ctx.packageName && cls.startsWith("$pkg.") && !cls.endsWith("SettingsEntry") && !cls.contains(".settings."))
            "$pkg/" + cls.substringAfterLast('.')
        else pkg

    fun isCompanion(key: String) = key.startsWith(ctx.packageName + "/")

    /** The phone's default Phone, Messages and Camera apps get Apple's icon and name, like on iPhone. */
    @Volatile var appleLook: Map<String, Pair<Int, String>> = emptyMap()

    private fun computeAppleLook() {
        val pm = ctx.packageManager
        fun def(i: android.content.Intent) = runCatching { pm.resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }.getOrNull()
            ?.takeIf { it != "android" && it != ctx.packageName }
        val m = HashMap<String, Pair<Int, String>>()
        def(android.content.Intent(android.content.Intent.ACTION_DIAL))?.let { m[it] = com.glass.launcher.R.mipmap.ic_phone to "טלפון" }
        (android.provider.Telephony.Sms.getDefaultSmsPackage(ctx)?.takeIf { it != ctx.packageName }
            ?: def(android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:1"))))?.let { m[it] = com.glass.launcher.R.mipmap.ic_messages to "הודעות" }
        def(android.content.Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))?.let { m[it] = com.glass.launcher.R.mipmap.ic_camera to "מצלמה" }
        appleLook = m
    }

    fun reload() {
        scope.launch {
            val me = Process.myUserHandle()
            val pm = ctx.packageManager
            val collator = Collator.getInstance(Locale("he"))
            computeAppleLook()
            val look = if (App.instance.prefs.appleIcons) appleLook else emptyMap()
            val list = launcherApps.getActivityList(null, me)
                .distinctBy { keyFor(it.componentName.packageName, it.componentName.className) }
                .map { info ->
                    val ai = info.applicationInfo
                    val install = runCatching { pm.getPackageInfo(ai.packageName, 0).firstInstallTime }.getOrDefault(0L)
                    AppEntry(
                        pkg = keyFor(ai.packageName, info.componentName.className),
                        label = look[ai.packageName]?.second ?: info.label?.toString() ?: ai.packageName,
                        component = info.componentName,
                        user = info.user,
                        category = ai.category,
                        isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        installTime = install,
                        info = info,
                    )
                }
                .sortedWith { a, b -> collator.compare(a.label, b.label) }
            _apps.value = list
            syncHome(list)
        }
    }

    /** Puts apps on the home screen the first time, and places newly installed apps if wanted. */
    private fun syncHome(list: List<AppEntry>) {
        val prefs = App.instance.prefs
        val all = list.map { it.pkg }.toSet()
        val perPage = prefs.columns * prefs.rows
        if (!prefs.homeInitialized) {
            val pm = ctx.packageManager
            fun defaultOf(intent: android.content.Intent): String? =
                pm.resolveActivity(intent, 0)?.activityInfo?.packageName?.takeIf { it in all }
            val dial = defaultOf(android.content.Intent(android.content.Intent.ACTION_DIAL))
            val sms = defaultOf(android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:")))
            val camera = defaultOf(android.content.Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            val browser = defaultOf(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com")))
            val dock = listOf(dial, sms, browser, camera).distinct()
            prefs.dock = (dock + List(4) { null }).take(4)
            val rest = list.map { it.pkg }.filter { it !in dock }.sortedBy { if (it == ctx.packageName) 0 else 1 }
            prefs.homePages = rest.take(perPage * 2).chunked(perPage)
                .map { (it + List(perPage) { null }).take(perPage) }
                .ifEmpty { listOf(List(perPage) { null }) }
            prefs.knownPackages = all
            prefs.homeInitialized = true
            prefs.selfAdded = true
            return
        }
        if (!prefs.selfAdded && ctx.packageName in all) {
            prefs.selfAdded = true
            addToHome(ctx.packageName)
        }
        val known = prefs.knownPackages
        val fresh = all - known
        if (fresh.isNotEmpty() && prefs.newAppsToHome) fresh.forEach { addToHome(it) }
        else fresh.filter { isCompanion(it) }.forEach { addToHome(it) } // our own apps, like iPhone's built-in ones
        if (fresh.isNotEmpty() || (known - all).isNotEmpty()) prefs.knownPackages = all
    }

    fun addToHome(pkg: String) {
        val prefs = App.instance.prefs
        if (prefs.homePages.any { pkg in it } || pkg in prefs.dock) return
        prefs.homePages = HomeGrid.place(prefs.homePages, prefs.columns, prefs.rows, pkg)
    }

    fun removeFromHome(pkg: String) {
        val prefs = App.instance.prefs
        prefs.homePages = prefs.homePages.map { page -> page.map { if (it == pkg) null else it } }
        prefs.dock = prefs.dock.map { if (it == pkg) null else it }
    }

    /** Where each icon is on screen, so the app can grow out of its icon when it opens. */
    val iconBounds = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Rect>()
    @Volatile var launcherView: java.lang.ref.WeakReference<android.view.View>? = null

    fun launch(entry: AppEntry) {
        val r = iconBounds[entry.pkg]
        val v = launcherView?.get()
        val opts = if (r != null && v != null && v.isAttachedToWindow) runCatching {
            android.app.ActivityOptions.makeScaleUpAnimation(v, r.left, r.top, r.width(), r.height()).toBundle()
        }.getOrNull() else null
        runCatching { launcherApps.startMainActivity(entry.component, entry.user, r, opts) }
            .onFailure { runCatching { launcherApps.startMainActivity(entry.component, entry.user, null, null) } }
        App.instance.prefs.noteLaunched(entry.pkg)
    }

    /** Library categories, in display order. */
    fun categoryName(e: AppEntry): String = when (e.category) {
        ApplicationInfo.CATEGORY_SOCIAL -> "תקשורת"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "פרודוקטיביות"
        ApplicationInfo.CATEGORY_AUDIO -> "שמע"
        ApplicationInfo.CATEGORY_VIDEO -> "וידאו"
        ApplicationInfo.CATEGORY_IMAGE -> "תמונות"
        ApplicationInfo.CATEGORY_NEWS -> "קריאה ומידע"
        ApplicationInfo.CATEGORY_MAPS -> "ניווט ותחבורה"
        ApplicationInfo.CATEGORY_GAME -> "משחקים"
        else -> if (e.isSystem) "כלים" else "אחר"
    }
}
