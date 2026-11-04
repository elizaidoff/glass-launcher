package com.glass.launcher.lock

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Watches which app comes to the front. When it is a protected app that is not unlocked,
 * the lock screen (or, for Settings, the loading screen that decides between real and imitation) covers it.
 * It never reads what is on the screen.
 */
class LockService : AccessibilityService() {

    private var lastCover = 0L
    private var lastCoverPkg: String? = null
    private var ignored: Set<String> = emptySet()
    private var imePackages: Set<String> = emptySet()
    private var nav: NavSystem? = null
    var assistive: AssistiveTouch? = null
        private set
    var hud: Hud? = null
        private set
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == Intent.ACTION_SCREEN_OFF) LockGuard.relockAll()
            nav?.onScreenState()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        instance = this
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenOff, filter)
        refreshIgnored()
        val g = NavSystem(this)
        nav = g
        navInstance = g
        val isl = DynamicIsland(this, scope)
        island = isl
        val at = AssistiveTouch(this)
        assistive = at
        val h = Hud(this)
        hud = h
        scope.launch { com.glass.launcher.feature.AirPods.state.collect { h.onPods(it) } }
        scope.launch { com.glass.launcher.App.instance.prefs.version.collect { g.refresh(); isl.refresh(); at.refresh() } }
    }

    private fun refreshIgnored() {
        val imes = runCatching {
            getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }
        }.getOrDefault(emptyList())
        imePackages = imes.toSet()
        ignored = setOf(packageName, "com.android.systemui", "android") + imes
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val realPkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: ""
        // Our companion apps (Messages, …) are their own apps for locking.
        val pkg = if (realPkg == packageName) com.glass.launcher.App.instance.repo.keyFor(realPkg, cls) else realPkg
        if (realPkg != "com.android.systemui" && realPkg != "android" && realPkg !in imePackages) nav?.onForeground(realPkg)
        if (realPkg == packageName && cls.endsWith("LauncherActivity")) { LockGuard.relockSessions(); return }
        if (pkg in ignored) return
        if (realPkg == packageName && !com.glass.launcher.App.instance.repo.isCompanion(pkg)) return

        LockGuard.onForeground(pkg)
        if (!LockGuard.isProtected(pkg)) return
        if (LockGuard.isUnlocked(pkg)) return
        if (LockGuard.isAllowed(pkg, cls)) return
        // Popups and menus inside an app send events with view class names; real screens are activities.
        if (cls.startsWith("android.widget.") || cls.startsWith("android.view.")) return

        val now = System.currentTimeMillis()
        if (pkg == lastCoverPkg && now - lastCover < 700) return
        lastCover = now
        lastCoverPkg = pkg
        LockActivity.start(this, pkg, launchAfter = false)
    }

    override fun onInterrupt() {}

    /** Volume keys draw iPhone's pill instead of Android's panel (when turned on). */
    override fun onKeyEvent(event: android.view.KeyEvent): Boolean = hud?.onKey(event) ?: false

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        island?.updateVisibility()
    }

    override fun onDestroy() {
        running = false
        instance = null
        nav?.removeAll()
        navInstance = null
        island?.remove()
        island = null
        assistive?.remove()
        assistive = null
        hud?.remove()
        hud = null
        scope.cancel()
        runCatching { unregisterReceiver(screenOff) }
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false
        @Volatile var island: DynamicIsland? = null
        @Volatile var instance: LockService? = null
        @Volatile var navInstance: NavSystem? = null

        /** Screenshot, lock screen etc. through the accessibility service; false if it is off. */
        fun global(action: Int): Boolean = instance?.performGlobalAction(action) ?: false

        fun isEnabled(ctx: Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(
                ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.startsWith(ctx.packageName + "/") }
        }
    }
}
