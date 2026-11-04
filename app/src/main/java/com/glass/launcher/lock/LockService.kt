package com.glass.launcher.lock

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

/**
 * Watches which app comes to the front. When it is a protected app that is not unlocked,
 * the lock screen (or, for Settings, the loading screen that decides between real and imitation) covers it.
 * It never reads what is on the screen.
 */
class LockService : AccessibilityService() {

    private var lastCover = 0L
    private var lastCoverPkg: String? = null
    private var ignored: Set<String> = emptySet()

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = LockGuard.relockAll()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenOff, filter)
        refreshIgnored()
    }

    private fun refreshIgnored() {
        val imes = runCatching {
            getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }
        }.getOrDefault(emptyList())
        ignored = setOf(packageName, "com.android.systemui", "android") + imes
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: ""
        if (pkg in ignored) return

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

    override fun onDestroy() {
        running = false
        runCatching { unregisterReceiver(screenOff) }
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false

        fun isEnabled(ctx: Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(
                ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.startsWith(ctx.packageName + "/") }
        }
    }
}
