package com.glass.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * Arcticons line icons bundled inside the app (free, CC BY-SA 4.0 – github.com/Arcticons-Team/Arcticons).
 * White lines on transparent; in ink mode they turn into black ink, framed like the e-ink phones' icons.
 */
object Arcticons {
    @Volatile private var map: Map<String, String>? = null

    private fun load(ctx: Context): Map<String, String> {
        map?.let { return it }
        val m = HashMap<String, String>()
        runCatching {
            val xml = ctx.assets.open("arcticons/appfilter.xml").bufferedReader().readText()
            // <item component="ComponentInfo{pkg/cls}" drawable="name" />
            Regex("component=\"ComponentInfo\\{([^/}]+)/[^}]*\\}\"\\s+drawable=\"([^\"]+)\"").findAll(xml).forEach { r ->
                m.putIfAbsent(r.groupValues[1], r.groupValues[2])
            }
        }
        map = m
        return m
    }

    fun available(ctx: Context) = load(ctx).isNotEmpty()

    fun icon(ctx: Context, pkg: String, size: Int): Bitmap? {
        val name = load(ctx)[pkg.substringBefore('/')] ?: return null
        val src = runCatching { ctx.assets.open("arcticons/$name.png").use { BitmapFactory.decodeStream(it) } }.getOrNull() ?: return null
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val pad = size * 0.17f
        c.drawBitmap(src, null, RectF(pad, pad, size - pad, size - pad), p)
        val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = size * 0.022f; color = android.graphics.Color.WHITE }
        val s = frame.strokeWidth
        c.drawRoundRect(s, s, size - s, size - s, size * 0.23f, size * 0.23f, frame)
        return out
    }
}
