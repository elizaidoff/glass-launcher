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
        val src = if (src0.config == Bitmap.Config.HARDWARE) src0.copy(Bitmap.Config.ARGB_8888, false) else src0
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val lum = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val a = (c ushr 24) / 255f
            val l = (0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)) * a
            lum[i] = l
        }
        val mag = FloatArray(w * h)
        var max = 1f
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val gx = -lum[i - w - 1] - 2 * lum[i - 1] - lum[i + w - 1] + lum[i - w + 1] + 2 * lum[i + 1] + lum[i + w + 1]
            val gy = -lum[i - w - 1] - 2 * lum[i - w] - lum[i - w + 1] + lum[i + w - 1] + 2 * lum[i + w] + lum[i + w + 1]
            val m = kotlin.math.sqrt(gx * gx + gy * gy)
            mag[i] = m
            if (m > max) max = m
        }
        val t = maxOf(60f, max * 0.22f)
        val out = IntArray(w * h)
        // skip a margin so the icon's own squircle edge doesn't double the frame
        val margin = (w * 0.06f).toInt()
        for (y in margin until h - margin) for (x in margin until w - margin) {
            val i = y * w + x
            val a = ((mag[i] - t) / t * 255f).coerceIn(0f, 255f).toInt()
            if (a > 0) out[i] = (a shl 24) or 0xFFFFFF
        }
        // Must be a mutable bitmap: we draw the frame onto it next.
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.setPixels(out, 0, w, 0, 0, w, h)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = w * 0.022f; color = android.graphics.Color.WHITE
        }
        val s = p.strokeWidth
        c.drawRoundRect(s, s, w - s, h - s, w * 0.23f, w * 0.23f, p)
        return b
    }
}

/** Wraps a whole screen in the ink look (when it's on): grey + inverted, slightly soft lines, then paper and pencil grain on top. */
@Composable
fun InkLayer(content: @Composable () -> Unit) {
    if (!Ink.on) { content(); return }
    if (!App.instance.prefs.inkPaper) {
        Box(Modifier.fillMaxSize().graphicsLayer { renderEffect = Ink.layer() }.background(Color.Black)) { content() }
        return
    }
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
