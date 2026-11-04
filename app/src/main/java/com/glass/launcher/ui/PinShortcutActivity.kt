package com.glass.launcher.ui

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.content.pm.LauncherApps
import android.os.Bundle
import android.widget.Toast
import com.glass.launcher.App
import com.glass.launcher.data.HomeGrid

/**
 * Apps ask the home screen to pin a shortcut here (e.g. "direct dial" from the Phone app).
 * It goes on the home screen as "s:<package>|<id>", like on iPhone's home screen bookmarks.
 */
class PinShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val la = getSystemService(LauncherApps::class.java)
        val req = runCatching { la.getPinItemRequest(intent) }.getOrNull()
        if (req != null && req.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT && req.isValid) {
            val info = req.shortcutInfo
            if (info != null && runCatching { req.accept() }.getOrDefault(false)) {
                val p = App.instance.prefs
                p.homePages = HomeGrid.place(p.homePages, p.columns, p.rows, Shortcuts.key(info.`package`, info.id))
                Toast.makeText(this, "נוסף למסך הבית", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}

object Shortcuts {
    fun key(pkg: String, id: String) = "s:$pkg|$id"
    fun isShortcut(v: String?) = v != null && v.startsWith("s:")
    fun parse(v: String): Pair<String, String> = v.removePrefix("s:").substringBefore('|') to v.substringAfter('|')

    private val cache = HashMap<String, android.content.pm.ShortcutInfo?>()

    fun info(v: String): android.content.pm.ShortcutInfo? = cache.getOrPut(v) {
        val (pkg, id) = parse(v)
        val ctx = App.instance
        val la = ctx.getSystemService(LauncherApps::class.java)
        runCatching {
            la.getShortcuts(LauncherApps.ShortcutQuery().setPackage(pkg).setShortcutIds(listOf(id))
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST),
                android.os.Process.myUserHandle())?.firstOrNull()
        }.getOrNull()
    }

    fun icon(v: String, sizePx: Int): android.graphics.Bitmap? {
        val i = info(v) ?: return null
        val la = App.instance.getSystemService(LauncherApps::class.java)
        val d = runCatching { la.getShortcutIconDrawable(i, App.instance.resources.displayMetrics.densityDpi) }.getOrNull() ?: return null
        val b = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val path = android.graphics.Path().apply { addRoundRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), sizePx * 0.225f, sizePx * 0.225f, android.graphics.Path.Direction.CW) }
        c.clipPath(path)
        if (d is android.graphics.drawable.AdaptiveIconDrawable) {
            val extra = sizePx / 4
            d.background?.let { it.setBounds(-extra, -extra, sizePx + extra, sizePx + extra); it.draw(c) }
            d.foreground?.let { it.setBounds(-extra, -extra, sizePx + extra, sizePx + extra); it.draw(c) }
        } else { d.setBounds(0, 0, sizePx, sizePx); d.draw(c) }
        return b
    }

    fun label(v: String) = info(v)?.shortLabel?.toString() ?: ""

    fun open(v: String) {
        val (pkg, id) = parse(v)
        val la = App.instance.getSystemService(LauncherApps::class.java)
        runCatching { la.startShortcut(pkg, id, null, null, android.os.Process.myUserHandle()) }
            .onFailure { Toast.makeText(App.instance, "הקיצור לא זמין", Toast.LENGTH_SHORT).show() }
    }

    fun remove(v: String) {
        val p = App.instance.prefs
        p.homePages = p.homePages.map { page -> page.map { if (it == v) null else it } }
        cache.remove(v)
    }
}

/** A pinned shortcut on the home screen. */
@Composable
fun ShortcutTile(v: String, size: Dp, showLabel: Boolean, modifier: Modifier, gesture: Modifier) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val img = remember(v, px) { Shortcuts.icon(v, px)?.asImageBitmap() }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(gesture.size(size)) {
            if (img != null) Image(img, null, Modifier.size(size)) else Box(Modifier.size(size).glass(SquircleShape))
        }
        if (showLabel) {
            Spacer(Modifier.height(5.dp))
            Text(Shortcuts.label(v), style = LabelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.width(size + 18.dp))
        }
    }
}
