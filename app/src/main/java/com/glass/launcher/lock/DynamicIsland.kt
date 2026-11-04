package com.glass.launcher.lock

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import com.glass.launcher.App
import com.glass.launcher.feature.AirPods
import com.glass.launcher.feature.Media
import com.glass.launcher.feature.MediaInfo
import com.glass.launcher.feature.SystemActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * An iPhone-style Dynamic Island drawn around the camera. Position and size are set by the user.
 * It grows for short alerts (charging, silent mode, AirPods, torch) and shows the music that is playing.
 */
class DynamicIsland(private val service: AccessibilityService, private val scope: CoroutineScope) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val d = service.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private var view: IslandView? = null
    private var params: WindowManager.LayoutParams? = null
    private var jobs = mutableListOf<Job>()
    private var receiver: BroadcastReceiver? = null
    private var lastConfig = ""

    private fun dp(v: Float) = (v * d).toInt()

    // Base (resting) geometry, in screen pixels.
    private var bx = 0
    private var by = 0
    private var bw = 0
    private var bh = 0

    private enum class Mode { IDLE, MEDIA_COMPACT, ALERT, MEDIA_EXPANDED, INFO }
    private var mode = Mode.IDLE
    private var alertIcon = ""
    private var alertText = ""
    private var alertColor = Color.WHITE
    private var media: MediaInfo? = null
    private var podsSeen = false

    fun refresh() {
        val p = App.instance.prefs
        val cfg = listOf(p.islandEnabled, p.islandEditing, p.islandX, p.islandY, p.islandW, p.islandH).joinToString()
        if (cfg == lastConfig) return
        lastConfig = cfg
        remove()
        if (!p.islandEnabled) return
        computeBase()
        if (p.islandEditing && p.islandW <= 0) {
            // Start editing from the automatic camera position.
            p.islandX = bx; p.islandY = by; p.islandW = bw; p.islandH = bh
            return
        }
        add(p.islandEditing)
    }

    private fun computeBase() {
        val p = App.instance.prefs
        val sw = service.resources.displayMetrics.widthPixels
        if (p.islandW > 0) {
            bx = p.islandX; by = p.islandY; bw = p.islandW; bh = p.islandH
            return
        }
        // First time: wrap the camera cut-out.
        val cut = runCatching { wm.currentWindowMetrics.windowInsets.displayCutout?.boundingRects?.firstOrNull() }.getOrNull()
        if (cut != null && cut.width() > 0) {
            bh = max(dp(34f), cut.height() + dp(8f))
            bw = max(dp(110f), cut.width() + dp(70f))
            bx = cut.centerX() - bw / 2
            by = cut.centerY() - bh / 2
        } else {
            bw = dp(118f); bh = dp(34f); bx = (sw - bw) / 2; by = dp(10f)
        }
        bx = bx.coerceAtLeast(0); by = by.coerceAtLeast(0)
    }

    private fun add(editing: Boolean) {
        val v = IslandView(service, editing)
        val lp = WindowManager.LayoutParams(
            bw, bh,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = bx; y = by
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        runCatching { wm.addView(v, lp) }.onFailure { return }
        view = v
        params = lp
        if (!editing) listen()
    }

    fun remove() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        receiver?.let { runCatching { service.unregisterReceiver(it) } }
        receiver = null
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        mode = Mode.IDLE
    }

    private fun listen() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_POWER_CONNECTED -> handler.postDelayed({
                        alert("bolt", "${SystemActions.batteryPercent(service)}%", Color.rgb(48, 209, 88))
                    }, 400)
                    AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                        val silent = service.getSystemService(AudioManager::class.java).ringerMode != AudioManager.RINGER_MODE_NORMAL
                        alert(if (silent) "bell_off" else "bell", if (silent) "שקט" else "צלצול", if (silent) Color.rgb(255, 69, 58) else Color.WHITE)
                    }
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) service.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else service.registerReceiver(r, f)
        receiver = r

        jobs += scope.launch {
            Media.now.collect { m ->
                media = m
                if (mode == Mode.IDLE || mode == Mode.MEDIA_COMPACT) settle()
                else view?.invalidate()
            }
        }
        jobs += scope.launch {
            SystemActions.torchOn.collect { on -> if (on) alert("torch", "פנס", Color.rgb(255, 214, 10)) }
        }
        jobs += scope.launch {
            AirPods.state.collect { s ->
                val fresh = AirPods.fresh(s)
                if (fresh != null && !podsSeen) {
                    podsSeen = true
                    val parts = listOfNotNull(fresh.left?.let { "$it%" }, fresh.right?.let { "$it%" }).joinToString(" ")
                    alert("pods", "AirPods  $parts", Color.WHITE)
                }
                if (fresh == null) podsSeen = false
            }
        }
        if (AirPods.hasPermission(service)) AirPods.start(service)
        settle()
    }

    private fun alert(icon: String, text: String, color: Int) {
        alertIcon = icon; alertText = text; alertColor = color
        mode = Mode.ALERT
        animateTo(bw + dp(150f), bh + dp(6f))
        handler.removeCallbacks(back)
        handler.postDelayed(back, 2600)
    }

    private val back = Runnable { settle() }

    /** Return to the resting state (with the music strip if something is playing). */
    private fun settle() {
        handler.removeCallbacks(back)
        val m = media
        if (m != null && m.playing) {
            mode = Mode.MEDIA_COMPACT
            animateTo(bw + dp(90f), bh)
        } else {
            mode = Mode.IDLE
            animateTo(bw, bh)
        }
    }

    private var anim: ValueAnimator? = null

    private fun animateTo(w: Int, h: Int) {
        val v = view ?: return
        val lp = params ?: return
        val sw = service.resources.displayMetrics.widthPixels
        val tw = w.coerceAtMost(sw - dp(16f))
        val cx = bx + bw / 2
        val w0 = lp.width
        val h0 = lp.height
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 380
            interpolator = PathInterpolator(0.3f, 1.35f, 0.4f, 1f)
            addUpdateListener {
                val f = it.animatedValue as Float
                lp.width = (w0 + (tw - w0) * f).toInt().coerceAtLeast(1)
                lp.height = (h0 + (h - h0) * f).toInt().coerceAtLeast(1)
                lp.x = (cx - lp.width / 2).coerceIn(0, sw - lp.width)
                lp.y = by
                runCatching { wm.updateViewLayout(v, lp) }
                v.invalidate()
            }
            start()
        }
    }

    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class IslandView(ctx: Context, private val editing: Boolean) : View(ctx) {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(10, 132, 255); style = Paint.Style.STROKE; strokeWidth = 2.5f * d
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 14f * d; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 255, 255, 255); textSize = 12.5f * d }
        private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2.2f * d; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private var downX = 0f
        private var downY = 0f
        private var downAt = 0L
        private var startX = 0
        private var startY = 0
        private var moved = false
        private var phase = 0f
        private val longPress = Runnable {
            if (!moved && App.instance.prefs.islandLongPressAssistant) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                SystemActions.openAssistant(service)
            }
        }

        init { setOnTouchListener { _, e -> touch(e) } }

        private fun touch(e: MotionEvent): Boolean {
            val lp = params ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; downAt = System.currentTimeMillis()
                    startX = lp.x; startY = lp.y; moved = false
                    if (!editing) handler.postDelayed(longPress, 500)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > 8 * d || abs(dy) > 8 * d) moved = true
                    if (editing) {
                        lp.x = (startX + dx).toInt().coerceAtLeast(0)
                        lp.y = (startY + dy).toInt().coerceAtLeast(0)
                        runCatching { wm.updateViewLayout(this, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (editing) {
                        val p = App.instance.prefs
                        p.islandX = lp.x; p.islandY = lp.y; p.islandW = lp.width; p.islandH = lp.height
                    } else if (!moved && System.currentTimeMillis() - downAt < 500) {
                        tap(e)
                    }
                }
                MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
            }
            return true
        }

        private fun tap(e: MotionEvent) {
            when (mode) {
                Mode.MEDIA_COMPACT -> { mode = Mode.MEDIA_EXPANDED; animateTo(service.resources.displayMetrics.widthPixels - dp(20f), dp(150f)) }
                Mode.MEDIA_EXPANDED -> {
                    // Buttons on the bottom row: previous / play-pause / next (left to right on screen).
                    if (e.y > height * 0.55f) {
                        val third = width / 3f
                        when {
                            e.x < third -> Media.previous()
                            e.x > third * 2 -> Media.next()
                            else -> Media.playPause()
                        }
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    } else settle()
                }
                Mode.IDLE -> {
                    val m = media
                    if (m != null) { mode = Mode.MEDIA_EXPANDED; animateTo(service.resources.displayMetrics.widthPixels - dp(20f), dp(150f)) }
                    else {
                        mode = Mode.INFO
                        alertIcon = "bolt"; alertText = "${SystemActions.batteryPercent(service)}%"; alertColor = Color.WHITE
                        animateTo(bw + dp(110f), bh)
                        handler.removeCallbacks(back); handler.postDelayed(back, 2000)
                    }
                }
                else -> settle()
            }
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = if (mode == Mode.MEDIA_EXPANDED) 38f * d else h / 2
            c.drawRoundRect(RectF(0f, 0f, w, h), r, r, bg)
            if (editing) {
                c.drawRoundRect(RectF(1.5f * d, 1.5f * d, w - 1.5f * d, h - 1.5f * d), r, r, edge)
                return
            }
            when (mode) {
                Mode.IDLE -> {}
                Mode.ALERT, Mode.INFO -> {
                    drawIcon(c, alertIcon, h / 2 + 4 * d, h / 2, h * 0.32f, alertColor)
                    text.color = alertColor
                    text.textAlign = Paint.Align.RIGHT
                    c.drawText(alertText, w - h / 2 - 4 * d, h / 2 + text.textSize * 0.36f, text)
                    text.color = Color.WHITE
                }
                Mode.MEDIA_COMPACT -> {
                    val m = media
                    drawArt(c, m?.art, RectF(h * 0.18f, h * 0.18f, h * 0.82f, h * 0.82f), 6 * d)
                    drawBars(c, w - h * 0.9f, h / 2, h * 0.5f)
                }
                Mode.MEDIA_EXPANDED -> {
                    val m = media
                    val pad = 18 * d
                    val art = 54 * d
                    drawArt(c, m?.art, RectF(w - pad - art, pad, w - pad, pad + art), 12 * d)
                    text.textAlign = Paint.Align.RIGHT
                    sub.textAlign = Paint.Align.RIGHT
                    c.drawText(ellipsize(m?.title ?: "", text, w - art - pad * 3), w - pad * 2 - art, pad + 22 * d, text)
                    c.drawText(ellipsize(m?.artist ?: "", sub, w - art - pad * 3), w - pad * 2 - art, pad + 42 * d, sub)
                    val cy = h - 34 * d
                    drawIcon(c, "prev", w / 6, cy, 12 * d, Color.WHITE)
                    drawIcon(c, if (m?.playing == true) "pause" else "play", w / 2, cy, 15 * d, Color.WHITE)
                    drawIcon(c, "next", w * 5 / 6, cy, 12 * d, Color.WHITE)
                }
            }
            if (mode == Mode.MEDIA_COMPACT) postInvalidateDelayed(60)
        }

        private fun ellipsize(s: String, p: Paint, max: Float): String {
            if (p.measureText(s) <= max) return s
            var t = s
            while (t.isNotEmpty() && p.measureText("$t…") > max) t = t.dropLast(1)
            return "$t…"
        }

        private fun drawArt(c: Canvas, b: Bitmap?, rect: RectF, rad: Float) {
            if (b == null) {
                fill.color = Color.rgb(60, 60, 67)
                c.drawRoundRect(rect, rad, rad, fill)
                return
            }
            c.save()
            c.clipPath(Path().apply { addRoundRect(rect, rad, rad, Path.Direction.CW) })
            c.drawBitmap(b, null, rect, null)
            c.restore()
        }

        private fun drawBars(c: Canvas, cx: Float, cy: Float, maxH: Float) {
            phase += 0.25f
            fill.color = Color.rgb(255, 159, 10)
            val bw = 2.6f * d
            for (i in 0 until 4) {
                val hh = maxH * (0.35f + 0.65f * abs(sin(phase + i * 1.3f)))
                val x = cx + (i - 1.5f) * bw * 2
                c.drawRoundRect(RectF(x - bw / 2, cy - hh / 2, x + bw / 2, cy + hh / 2), bw, bw, fill)
            }
        }

        private fun drawIcon(c: Canvas, name: String, cx: Float, cy: Float, s: Float, color: Int) {
            icon.color = color
            fill.color = color
            when (name) {
                "bolt" -> {
                    val p = Path().apply {
                        moveTo(cx + s * 0.2f, cy - s); lineTo(cx - s * 0.5f, cy + s * 0.1f); lineTo(cx, cy + s * 0.1f)
                        lineTo(cx - s * 0.2f, cy + s); lineTo(cx + s * 0.5f, cy - s * 0.1f); lineTo(cx, cy - s * 0.1f); close()
                    }
                    c.drawPath(p, fill)
                }
                "bell", "bell_off" -> {
                    c.drawArc(RectF(cx - s * 0.6f, cy - s, cx + s * 0.6f, cy + s * 0.4f), 180f, 180f, false, icon)
                    c.drawLine(cx - s * 0.6f, cy - s * 0.3f, cx - s * 0.6f, cy + s * 0.4f, icon)
                    c.drawLine(cx + s * 0.6f, cy - s * 0.3f, cx + s * 0.6f, cy + s * 0.4f, icon)
                    c.drawLine(cx - s * 0.85f, cy + s * 0.45f, cx + s * 0.85f, cy + s * 0.45f, icon)
                    c.drawCircle(cx, cy + s * 0.8f, s * 0.15f, fill)
                    if (name == "bell_off") c.drawLine(cx - s, cy - s, cx + s, cy + s, icon)
                }
                "torch" -> {
                    c.drawRoundRect(RectF(cx - s * 0.3f, cy - s * 0.2f, cx + s * 0.3f, cy + s), 3 * d, 3 * d, icon)
                    c.drawLine(cx - s * 0.6f, cy - s * 0.6f, cx + s * 0.6f, cy - s * 0.6f, icon)
                    c.drawLine(cx - s * 0.6f, cy - s * 0.6f, cx - s * 0.3f, cy - s * 0.2f, icon)
                    c.drawLine(cx + s * 0.6f, cy - s * 0.6f, cx + s * 0.3f, cy - s * 0.2f, icon)
                }
                "pods" -> {
                    c.drawCircle(cx - s * 0.45f, cy - s * 0.3f, s * 0.35f, fill)
                    c.drawCircle(cx + s * 0.45f, cy - s * 0.3f, s * 0.35f, fill)
                    c.drawLine(cx - s * 0.45f, cy, cx - s * 0.45f, cy + s, icon)
                    c.drawLine(cx + s * 0.45f, cy, cx + s * 0.45f, cy + s, icon)
                }
                "play" -> c.drawPath(Path().apply { moveTo(cx - s * 0.6f, cy - s); lineTo(cx + s, cy); lineTo(cx - s * 0.6f, cy + s); close() }, fill)
                "pause" -> {
                    c.drawRoundRect(RectF(cx - s * 0.7f, cy - s, cx - s * 0.2f, cy + s), 2 * d, 2 * d, fill)
                    c.drawRoundRect(RectF(cx + s * 0.2f, cy - s, cx + s * 0.7f, cy + s), 2 * d, 2 * d, fill)
                }
                "next", "prev" -> {
                    val dir = if (name == "next") 1 else -1
                    for (k in 0..1) {
                        val ox = cx + dir * (k * s * 0.9f - s * 0.45f)
                        c.drawPath(Path().apply {
                            moveTo(ox - dir * s * 0.5f, cy - s * 0.7f); lineTo(ox + dir * s * 0.5f, cy); lineTo(ox - dir * s * 0.5f, cy + s * 0.7f); close()
                        }, fill)
                    }
                }
            }
        }
    }

    /** Called from settings sliders while editing. */
    fun resize(w: Int, h: Int) {
        val v = view ?: return
        val lp = params ?: return
        val cx = lp.x + lp.width / 2
        lp.width = w; lp.height = h; lp.x = (cx - w / 2).coerceAtLeast(0)
        runCatching { wm.updateViewLayout(v, lp) }
        v.invalidate()
    }
}
