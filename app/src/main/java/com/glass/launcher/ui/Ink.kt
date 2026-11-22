package com.glass.launcher.ui

import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asImageBitmap
import com.glass.launcher.App

/**
 * "Ink" look, like the e-ink phones (Hisense A9, Bigme HiBreak): the same home screen,
 * drawn as thin pencil lines on paper.
 *
 * How: our screens are drawn as usual (light on dark), then the whole layer is turned grey and
 * inverted in one GPU pass – dark glass becomes paper, white text becomes black ink.
 * App icons are traced into line drawings ([lineArt]); other apps' widgets are inverted back so
 * they only lose their colour.
 */
object Ink {
    val on: Boolean get() = App.instance.prefs.inkMode

    // grey = 0.299R + 0.587G + 0.114B, then inverted onto warm paper (#FAF9F4)
    private val invertGray = floatArrayOf(
        -0.299f, -0.587f, -0.114f, 0f, 250f,
        -0.299f, -0.587f, -0.114f, 0f, 249f,
        -0.299f, -0.587f, -0.114f, 0f, 244f,
        0f, 0f, 0f, 1f, 0f,
    )
    private val invert = floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    )

    fun layer(): androidx.compose.ui.graphics.RenderEffect? =
        if (Build.VERSION.SDK_INT >= 31) RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(invertGray)).asComposeRenderEffect() else null

    /** The ink pass plus a hair of blur, so strokes are soft like pencil rather than razor-sharp. */
    fun layerSoft(): androidx.compose.ui.graphics.RenderEffect? =
        if (Build.VERSION.SDK_INT >= 31) RenderEffect.createChainEffect(
            RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(invertGray)),
            RenderEffect.createBlurEffect(0.7f, 0.7f, android.graphics.Shader.TileMode.CLAMP),
        ).asComposeRenderEffect() else null

    fun undo(): androidx.compose.ui.graphics.RenderEffect? =
        if (Build.VERSION.SDK_INT >= 31) RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(invert)).asComposeRenderEffect() else null

    /**
     * Traces an icon into white pencil lines on transparent (they become black after [layer]):
     * a thin rounded-square frame plus the outlines of the icon's shapes.
     */
    fun lineArt(src0: Bitmap): Bitmap = runCatching { trace(src0) }.getOrDefault(src0)

    private fun trace(src0: Bitmap): Bitmap {
        val size = src0.width
        // Work at double size for smooth, even lines, then scale down.
        val base = if (src0.config == Bitmap.Config.HARDWARE) src0.copy(Bitmap.Config.ARGB_8888, false) else src0
        val src = Bitmap.createScaledBitmap(base, size * 2, size * 2, true)
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        // Colour channels over white, so colours of equal brightness still separate.
        val r = FloatArray(w * h); val g = FloatArray(w * h); val bl = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]; val a = (c ushr 24) / 255f
            r[i] = ((c shr 16) and 255) * a + 255f * (1 - a)
            g[i] = ((c shr 8) and 255) * a + 255f * (1 - a)
            bl[i] = (c and 255) * a + 255f * (1 - a)
        }
        fun blur(ch: FloatArray): FloatArray {
            val o = FloatArray(ch.size)
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                o[i] = (ch[i - w - 1] + 2 * ch[i - w] + ch[i - w + 1] + 2 * ch[i - 1] + 4 * ch[i] + 2 * ch[i + 1] + ch[i + w - 1] + 2 * ch[i + w] + ch[i + w + 1]) / 16f
            }
            return o
        }
        val rb = blur(r); val gb = blur(g); val bb = blur(bl)
        val mag = FloatArray(w * h)
        fun sob(ch: FloatArray, i: Int): Float {
            val gx = -ch[i - w - 1] - 2 * ch[i - 1] - ch[i + w - 1] + ch[i - w + 1] + 2 * ch[i + 1] + ch[i + w + 1]
            val gy = -ch[i - w - 1] - 2 * ch[i - w] - ch[i - w + 1] + ch[i + w - 1] + 2 * ch[i + w] + ch[i + w + 1]
            return kotlin.math.sqrt(gx * gx + gy * gy)
        }
        // Ignore the icon's own outer edge (the frame is drawn separately).
        val margin = (w * 0.09f).toInt()
        val vals = ArrayList<Float>()
        for (y in margin until h - margin) for (x in margin until w - margin) {
            val i = y * w + x
            val m = maxOf(sob(rb, i), sob(gb, i), sob(bb, i))
            mag[i] = m
            if (m > 30f) vals.add(m)
        }
        if (vals.isEmpty()) return frameOnly(size)
        vals.sort()
        // Keep the strongest outlines only (about the top fifth of the edge pixels).
        val t = maxOf(70f, vals[(vals.size * 0.8f).toInt().coerceAtMost(vals.size - 1)] * 0.6f)
        val out = IntArray(w * h)
        for (i in mag.indices) {
            if (mag[i] >= t) {
                val a = ((mag[i] - t) / t * 400f + 140f).coerceIn(0f, 255f).toInt()
                out[i] = (a shl 24) or 0xFFFFFF
            }
        }
        val big = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        big.setPixels(out, 0, w, 0, 0, w, h)
        val res = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(res)
        val pad = size * 0.06f
        c.drawBitmap(big, null, android.graphics.RectF(pad, pad, size - pad, size - pad), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG))
        drawFrame(c, size)
        return res
    }

    private fun drawFrame(c: android.graphics.Canvas, size: Int) {
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = size * 0.022f; color = android.graphics.Color.WHITE
        }
        val s = p.strokeWidth
        c.drawRoundRect(s, s, size - s, size - s, size * 0.23f, size * 0.23f, p)
    }

    private fun frameOnly(size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawFrame(android.graphics.Canvas(b), size)
        return b
    }
}

/** Wraps a whole screen in the ink look (when it's on): grey + inverted, slightly soft lines, then paper and pencil grain on top. */
@Composable
fun InkLayer(content: @Composable () -> Unit) {
    if (!Ink.on) { content(); return }
    val paper = androidx.compose.runtime.remember { InkTexture.paper() }
    val grain = androidx.compose.runtime.remember { InkTexture.grain() }
    Box(
        Modifier.fillMaxSize().drawWithContent {
            drawContent()
            // Paper fibres darken the page a touch; light specks break up the dark strokes like graphite.
            drawRect(paper, blendMode = androidx.compose.ui.graphics.BlendMode.Multiply)
            drawRect(grain, blendMode = androidx.compose.ui.graphics.BlendMode.Screen)
        }
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { renderEffect = Ink.layerSoft() }.background(Color.Black)) { content() }
    }
}

private object InkTexture {
    private fun tile(size: Int, f: (java.util.Random, Int, Int) -> Int): androidx.compose.ui.graphics.ShaderBrush {
        val rnd = java.util.Random(7)
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) px[y * size + x] = f(rnd, x, y)
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        b.setPixels(px, 0, size, 0, 0, size, size)
        return androidx.compose.ui.graphics.ShaderBrush(
            androidx.compose.ui.graphics.ImageShader(b.asImageBitmap(), androidx.compose.ui.graphics.TileMode.Repeated, androidx.compose.ui.graphics.TileMode.Repeated)
        )
    }

    /** Off-white paper with soft blotches and fine fibres (multiplied over the page). */
    fun paper() = tile(256) { r, x, y ->
        val blot = (kotlin.math.sin(x * 0.045) * kotlin.math.cos(y * 0.038) * 4).toInt()
        val fibre = if (r.nextInt(100) < 6) r.nextInt(14) else 0
        val v = (255 - 6 - r.nextInt(7) - fibre + blot).coerceIn(220, 255)
        android.graphics.Color.rgb(v, v, (v - 3).coerceAtLeast(0))
    }

    /** Sparse light specks, so black strokes look like pencil on rough paper (screened over the page). */
    fun grain() = tile(192) { r, _, _ ->
        if (r.nextInt(100) < 28) { val v = 40 + r.nextInt(70); android.graphics.Color.rgb(v, v, v) } else android.graphics.Color.BLACK
    }
}

/** For other apps' widgets inside [InkLayer]: grey, but not a photo negative. */
fun Modifier.inkUndo(): Modifier = if (!Ink.on) this else graphicsLayer { renderEffect = Ink.undo() }
