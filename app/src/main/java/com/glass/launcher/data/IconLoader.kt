package com.glass.launcher.data

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

data class IconPackInfo(val pkg: String, val label: String)

/**
 * Renders every app icon in one uniform rounded-square ("squircle") shape,
 * or takes the icon from an installed icon pack when one is chosen.
 */
class IconLoader(private val ctx: Context) {
    private val cache = LruCache<String, Bitmap>(400)
    private var packPkg: String? = null
    private var packMap: Map<String, String> = emptyMap()
    private var packRes: Resources? = null

    private val diskDir = java.io.File(ctx.cacheDir, "icons").apply { mkdirs() }

    fun clear() {
        cache.evictAll()
        diskDir.listFiles()?.forEach { it.delete() }
    }

    private fun diskKey(e: AppEntry, size: Int, pack: String?): java.io.File {
        val updated = runCatching { ctx.packageManager.getPackageInfo(e.pkg, 0).lastUpdateTime }.getOrDefault(0L)
        return java.io.File(diskDir, "${e.pkg}_${size}_${pack ?: "x"}_$updated.png")
    }

    fun installedPacks(): List<IconPackInfo> {
        val pm = ctx.packageManager
        return pm.queryIntentActivities(Intent("org.adw.launcher.THEMES"), 0)
            .map { IconPackInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.pkg }
    }

    private fun ensurePack(pkg: String?) {
        if (pkg == packPkg) return
        packPkg = pkg
        packMap = emptyMap()
        packRes = null
        cache.evictAll()
        if (pkg == null) return
        runCatching {
            val res = ctx.packageManager.getResourcesForApplication(pkg)
            packRes = res
            val map = HashMap<String, String>()
            val id = res.getIdentifier("appfilter", "xml", pkg)
            val parser: XmlPullParser = if (id != 0) res.getXml(id) else {
                XmlPullParserFactory.newInstance().newPullParser().also {
                    it.setInput(res.assets.open("appfilter.xml"), "utf-8")
                }
            }
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && parser.name == "item") {
                    val comp = parser.getAttributeValue(null, "component")
                    val draw = parser.getAttributeValue(null, "drawable")
                    if (comp != null && draw != null) {
                        val c = comp.removePrefix("ComponentInfo{").removeSuffix("}")
                        map[c] = draw
                        map.putIfAbsent(c.substringBefore('/'), draw)
                    }
                }
                ev = parser.next()
            }
            packMap = map
        }
    }

    private fun packIcon(e: AppEntry): Drawable? {
        val res = packRes ?: return null
        val name = packMap[e.component.flattenToString()]
            ?: packMap["${e.component.packageName}/${e.component.className}"]
            ?: packMap[e.pkg] ?: return null
        val id = res.getIdentifier(name, "drawable", packPkg)
        if (id == 0) return null
        return runCatching { res.getDrawable(id, null) }.getOrNull()
    }

    fun icon(e: AppEntry, sizePx: Int, pack: String?): Bitmap {
        ensurePack(pack)
        val key = "${e.pkg}:$sizePx:$pack"
        cache.get(key)?.let { return it }
        // Disk cache: icons survive restarts, so the home screen appears instantly.
        val file = diskKey(e, sizePx, pack)
        if (file.exists()) {
            android.graphics.BitmapFactory.decodeFile(file.path)?.let { cache.put(key, it); return it }
        }
        val fromPack = packIcon(e)
        val bmp = if (fromPack != null) drawPlain(fromPack, sizePx)
        else drawSquircle(e.info.getIcon(ctx.resources.displayMetrics.densityDpi), sizePx)
        cache.put(key, bmp)
        runCatching { file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return bmp
    }

    fun iconForPackage(pkg: String, sizePx: Int): Bitmap? {
        val d = runCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull() ?: return null
        return drawSquircle(d, sizePx)
    }

    /** Draws an icon as-is (used for icon packs, which are already shaped). */
    private fun drawPlain(d: Drawable, size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return b
    }

    private fun drawSquircle(d: Drawable, size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val path = squirclePath(size.toFloat(), size.toFloat())
        c.clipPath(path)
        if (d is AdaptiveIconDrawable) {
            val extra = size / 4
            d.background?.let { it.setBounds(-extra, -extra, size + extra, size + extra); it.draw(c) }
            d.foreground?.let { it.setBounds(-extra, -extra, size + extra, size + extra); it.draw(c) }
        } else {
            c.drawPaint(Paint().apply { color = Color.WHITE })
            val inset = (size * 0.14f).toInt()
            d.setBounds(inset, inset, size - inset, size - inset)
            d.draw(c)
        }
        return b
    }

    companion object {
        fun squirclePath(w: Float, h: Float, n: Double = 5.0): Path {
            val p = Path()
            val a = w / 2.0
            val bb = h / 2.0
            val steps = 96
            for (i in 0..steps) {
                val t = 2 * Math.PI * i / steps
                val ct = cos(t)
                val st = sin(t)
                val x = a + a * sign(ct) * abs(ct).pow(2.0 / n)
                val y = bb + bb * sign(st) * abs(st).pow(2.0 / n)
                if (i == 0) p.moveTo(x.toFloat(), y.toFloat()) else p.lineTo(x.toFloat(), y.toFloat())
            }
            p.close()
            return p
        }
    }
}
