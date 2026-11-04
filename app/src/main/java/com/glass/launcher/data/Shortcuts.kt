package com.glass.launcher.data

import android.app.Activity
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Process
import com.glass.launcher.App
import java.util.concurrent.ConcurrentHashMap

/**
 * App shortcuts pinned to the home screen (e.g. "direct dial" to a contact from the Phone app).
 * Stored in a home slot as "s:<package>|<shortcut id>".
 */
object Shortcuts {
    fun key(pkg: String, id: String) = "s:$pkg|$id"
    fun isShortcut(v: String?) = v != null && v.startsWith("s:")
    private fun split(v: String) = v.removePrefix("s:").let { it.substringBefore('|') to it.substringAfter('|') }

    private val infos = ConcurrentHashMap<String, ShortcutInfo>()
    private val icons = ConcurrentHashMap<String, Bitmap>()

    fun info(ctx: Context, v: String): ShortcutInfo? {
        infos[v]?.let { return it }
        val (pkg, id) = split(v)
        val la = ctx.getSystemService(LauncherApps::class.java)
        val q = LauncherApps.ShortcutQuery().setPackage(pkg).setShortcutIds(listOf(id))
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
        val s = runCatching { la.getShortcuts(q, Process.myUserHandle())?.firstOrNull() }.getOrNull() ?: return null
        infos[v] = s
        return s
    }

    fun label(ctx: Context, v: String): String = info(ctx, v)?.shortLabel?.toString() ?: ""

    fun icon(ctx: Context, v: String, size: Int): Bitmap? {
        icons["$v:$size"]?.let { return it }
        val s = info(ctx, v) ?: return null
        val d = runCatching { ctx.getSystemService(LauncherApps::class.java).getShortcutIconDrawable(s, ctx.resources.displayMetrics.densityDpi) }.getOrNull()
            ?: return null
        val b = App.instance.icons.squircle(d, size)
        icons["$v:$size"] = b
        return b
    }

    fun open(ctx: Context, v: String) {
        val (pkg, id) = split(v)
        runCatching { ctx.getSystemService(LauncherApps::class.java).startShortcut(pkg, id, null, null, Process.myUserHandle()) }
    }

    /** Lets go of the pin (the app may show it as removed). */
    fun unpin(ctx: Context, v: String) {
        val (pkg, id) = split(v)
        val la = ctx.getSystemService(LauncherApps::class.java)
        runCatching {
            val q = LauncherApps.ShortcutQuery().setPackage(pkg).setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            val keep = la.getShortcuts(q, Process.myUserHandle())?.map { it.id }?.filter { it != id } ?: emptyList()
            la.pinShortcuts(pkg, keep, Process.myUserHandle())
        }
        infos.remove(v)
    }
}

/** Android asks the launcher to confirm "Add to home screen" for a shortcut; we accept and place it. */
class PinShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val la = getSystemService(LauncherApps::class.java)
        val req = runCatching { la.getPinItemRequest(intent) }.getOrNull()
        if (req != null && req.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT && req.isValid) {
            val s = req.shortcutInfo
            if (s != null && req.accept()) {
                App.instance.repo.addToHome(Shortcuts.key(s.`package`, s.id))
                android.widget.Toast.makeText(this, "נוסף למסך הבית: ${s.shortLabel}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}
