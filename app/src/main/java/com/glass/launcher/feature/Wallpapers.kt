package com.glass.launcher.feature

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader

/** Original soft-gradient wallpapers in an iOS-like style, drawn on the phone (no image files). */
object Wallpapers {
    data class Blob(val x: Float, val y: Float, val r: Float, val color: Int)
    data class Preset(val name: String, val top: Int, val bottom: Int, val blobs: List<Blob>)

    private fun c(hex: Long) = hex.toInt()

    val presets = listOf(
        Preset("זריחה", c(0xFFFFB38A), c(0xFF6A3093), listOf(
            Blob(0.2f, 0.25f, 0.7f, c(0xCCFF6B6B)), Blob(0.85f, 0.45f, 0.6f, c(0xAAFFD166)), Blob(0.4f, 0.85f, 0.8f, c(0xAA8E44AD)))),
        Preset("ים", c(0xFF0B3D91), c(0xFF00C6FF), listOf(
            Blob(0.15f, 0.2f, 0.7f, c(0xAA00E0FF)), Blob(0.8f, 0.6f, 0.7f, c(0xAA3A1C71)), Blob(0.4f, 0.95f, 0.6f, c(0x9900FFC6)))),
        Preset("שקיעה", c(0xFF1A1A40), c(0xFFFF5F6D), listOf(
            Blob(0.75f, 0.3f, 0.65f, c(0xBBFFC371)), Blob(0.2f, 0.6f, 0.7f, c(0xAAFF5F6D)), Blob(0.6f, 0.95f, 0.6f, c(0x995B247A)))),
        Preset("יער", c(0xFF0F2027), c(0xFF2C7744), listOf(
            Blob(0.2f, 0.3f, 0.6f, c(0xAA56AB2F)), Blob(0.85f, 0.55f, 0.7f, c(0x99A8E063)), Blob(0.45f, 0.9f, 0.7f, c(0xAA134E5E)))),
        Preset("לבנדר", c(0xFFE0C3FC), c(0xFF8EC5FC), listOf(
            Blob(0.25f, 0.25f, 0.6f, c(0xAAF9A8D4)), Blob(0.8f, 0.5f, 0.65f, c(0xAAA78BFA)), Blob(0.3f, 0.9f, 0.7f, c(0x9967E8F9)))),
        Preset("לילה", c(0xFF000000), c(0xFF141E30), listOf(
            Blob(0.7f, 0.25f, 0.6f, c(0x883A47D5)), Blob(0.2f, 0.65f, 0.7f, c(0x66C471ED)), Blob(0.6f, 0.95f, 0.6f, c(0x5512C2E9)))),
        Preset("חול", c(0xFFF7E8D0), c(0xFFD9A066), listOf(
            Blob(0.25f, 0.3f, 0.6f, c(0xAAFFE3B3)), Blob(0.85f, 0.6f, 0.6f, c(0x99E6A15C)), Blob(0.35f, 0.9f, 0.7f, c(0x88C77D4A)))),
        Preset("גרפיט", c(0xFF232526), c(0xFF414345), listOf(
            Blob(0.3f, 0.3f, 0.6f, c(0x66FFFFFF)), Blob(0.8f, 0.7f, 0.7f, c(0x44000000)))),
    )

    fun render(p: Preset, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        paint.shader = LinearGradient(0f, 0f, w * 0.3f, h.toFloat(), p.top, p.bottom, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        val size = maxOf(w, h).toFloat()
        p.blobs.forEach { bl ->
            val cx = bl.x * w
            val cy = bl.y * h
            val r = bl.r * size
            paint.shader = RadialGradient(cx, cy, r, bl.color, bl.color and 0x00FFFFFF, Shader.TileMode.CLAMP)
            cv.drawCircle(cx, cy, r, paint)
        }
        return b
    }

    fun apply(ctx: Context, p: Preset): Boolean = runCatching {
        val dm = ctx.resources.displayMetrics
        val w = minOf(dm.widthPixels, dm.heightPixels)
        val h = maxOf(dm.widthPixels, dm.heightPixels)
        WallpaperManager.getInstance(ctx).setBitmap(render(p, w, h), null, true,
            WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
        true
    }.getOrDefault(false)
}
