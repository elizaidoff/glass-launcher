package com.glass.launcher.lock

import android.content.Context
import android.content.Intent
import com.glass.launcher.App
import com.glass.launcher.data.AppEntry
import kotlinx.coroutines.flow.MutableStateFlow

/** Shared lock state between the launcher, the lock screen and the accessibility service. */
object LockGuard {
    /** The app currently unlocked; relocks as soon as another app comes to the front. */
    @Volatile var sessionPkg: String? = null

    /** Lets one specific screen of a protected app open (e.g. the Wi-Fi panel from Control Center). */
    private class Allowance(val pkg: String, val classMatch: (String) -> Boolean, val until: Long)
    @Volatile private var allowance: Allowance? = null

    /** Whether the hidden folders are showing in the App Library right now. */
    val hiddenRevealed = MutableStateFlow(false)

    fun unlock(pkg: String) { sessionPkg = pkg }

    fun isUnlocked(pkg: String) = sessionPkg == pkg

    fun relockAll() {
        sessionPkg = null
        allowance = null
        hiddenRevealed.value = false
    }

    fun allowScreen(pkg: String, ms: Long = 4000, classMatch: (String) -> Boolean) {
        allowance = Allowance(pkg, classMatch, System.currentTimeMillis() + ms)
    }

    fun isAllowed(pkg: String, cls: String): Boolean {
        val a = allowance ?: return false
        if (a.pkg != pkg) return false
        if (System.currentTimeMillis() > a.until) { allowance = null; return false }
        return a.classMatch(cls)
    }

    fun onForeground(pkg: String) {
        if (sessionPkg != null && sessionPkg != pkg) sessionPkg = null
        allowance?.let { if (it.pkg != pkg && System.currentTimeMillis() > it.until - 3000) allowance = null }
    }

    fun isProtected(pkg: String): Boolean {
        val p = App.instance.prefs
        return isDecoyTarget(pkg) || (p.lockEnabled && (pkg in p.locked || pkg in p.hidden))
    }

    fun isDecoyTarget(pkg: String) = App.instance.prefs.decoyEnabled && pkg == App.instance.settingsPackage

    /** Opens an app from the launcher, going through the lock when needed. */
    fun open(ctx: Context, e: AppEntry, alreadyAuthorized: Boolean = false) {
        if (alreadyAuthorized) unlock(e.pkg)
        if (!alreadyAuthorized && isProtected(e.pkg) && !isUnlocked(e.pkg)) {
            LockActivity.start(ctx, e.pkg, launchAfter = true)
        } else {
            App.instance.repo.launch(e)
        }
    }

    /** Starts a protected system screen ourselves (e.g. a settings panel) without the lock kicking in. */
    fun startTrusted(ctx: Context, intent: Intent, classMatch: (String) -> Boolean = { true }) {
        val pkg = intent.resolveActivity(ctx.packageManager)?.packageName
        if (pkg != null) allowScreen(pkg, classMatch = classMatch)
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Starts a system screen with the protected app fully unlocked (used from the launcher's own settings). */
    fun startUnlocked(ctx: Context, intent: Intent) {
        val pkg = intent.resolveActivity(ctx.packageManager)?.packageName
        if (pkg != null) unlock(pkg)
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
