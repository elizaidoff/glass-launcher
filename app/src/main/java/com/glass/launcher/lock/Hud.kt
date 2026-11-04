package com.glass.launcher.lock

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
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
import android.service.notification.StatusBarNotification
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.glass.launcher.App
import com.glass.launcher.feature.AirPods
import com.glass.launcher.feature.AirPodsState
import kotlin.math.abs

/**
 * The small iPhone touches drawn over every app (through the accessibility service, no extra permission):
 *  - the volume pill at the edge instead of Android's volume panel,
 *  - notification banners that drop from the top,
 *  - the AirPods card that slides up when the AirPods are taken out.
 * All are plain views that exist only while showing, so they cost nothing the rest of the time.
 */
class Hud(private val service: AccessibilityService) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private val audio = service.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val d = service.resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val font: Typeface = runCatching {
        val id = service.resources.getIdentifier("heebo", "font", service.packageName)
        if (id != 0) service.resources.getFont(id) else Typeface.DEFAULT
    }.getOrDefault(Typeface.DEFAULT)

    private val dark get() = (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun params(w: Int, h: Int, gravity: Int, touchable: Boolean) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        this.gravity = gravity
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    fun remove() {
        listOfNotNull(volumeView, bannerView, podsView).forEach { runCatching { wm.removeView(it) } }
        volumeView = null; bannerView = null; podsView = null
    }

    // ================================================================ volume

    private var volumeView: VolumeView? = null
    private val hideVolume = Runnable { volumeView?.animate()?.alpha(0f)?.translationX(dp(30f))?.setDuration(220)?.withEndAction { removeVolume() }?.start() }

    private fun removeVolume() { volumeView?.let { runCatching { wm.removeView(it) } }; volumeView = null }

    /** Returns true when the key was handled (and Android's own panel must not show). */
    fun onKey(e: KeyEvent): Boolean {
        if (!App.instance.prefs.iosVolume) return false
        if (e.keyCode != KeyEvent.KEYCODE_VOLUME_UP && e.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (!service.getSystemService(android.os.PowerManager::class.java).isInteractive) return false
        if (e.action == KeyEvent.ACTION_DOWN) {
            val dir = if (e.keyCode == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            val stream = activeStream()
            audio.adjustStreamVolume(stream, dir, 0)
            showVolume(stream)
        }
        return true
    }

    private fun activeStream(): Int = when {
        audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION -> AudioManager.STREAM_VOICE_CALL
        audio.isMusicActive -> AudioManager.STREAM_MUSIC
        else -> if (App.instance.prefs.volumeKeysRinger) AudioManager.STREAM_RING else AudioManager.STREAM_MUSIC
    }

    private fun showVolume(stream: Int) {
        val v = volumeView ?: VolumeView(service).also { nv ->
            val lp = params((dp(54f)).toInt(), dp(190f).toInt(), Gravity.END or Gravity.TOP, true).apply {
                x = dp(10f).toInt(); y = (service.resources.displayMetrics.heightPixels * 0.22f).toInt()
            }
            nv.alpha = 0f; nv.translationX = dp(30f)
            runCatching { wm.addView(nv, lp) }
            volumeView = nv
        }
        v.stream = stream
        v.level = audio.getStreamVolume(stream).toFloat() / audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        v.invalidate()
        v.animate().alpha(1f).translationX(0f).setDuration(160).start()
        main.removeCallbacks(hideVolume)
        main.postDelayed(hideVolume, 1600)
    }

    @SuppressLint("ViewConstructor")
    private inner class VolumeView(ctx: Context) : View(ctx) {
        var level = 0f
        var stream = AudioManager.STREAM_MUSIC
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.2f); strokeCap = Paint.Cap.ROUND }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val r = RectF(dp(4f), 0f, w - dp(4f), h)
            bg.color = if (dark) Color.argb(200, 60, 60, 64) else Color.argb(215, 200, 200, 205)
            c.drawRoundRect(r, r.width() / 2.2f, r.width() / 2.2f, bg)
            c.save()
            val clip = Path().apply { addRoundRect(r, r.width() / 2.2f, r.width() / 2.2f, Path.Direction.CW) }
            c.clipPath(clip)
            c.drawRect(r.left, h - h * level, r.right, h, fill)
            c.restore()
            // speaker glyph at the bottom, dark on the white fill
            val cx = w / 2; val cy = h - dp(22f)
            icon.color = if (level > 0.12f) Color.rgb(60, 60, 64) else Color.WHITE
            val body = Path().apply {
                moveTo(cx - dp(8f), cy - dp(3f)); lineTo(cx - dp(4f), cy - dp(3f)); lineTo(cx + dp(1f), cy - dp(8f))
                lineTo(cx + dp(1f), cy + dp(8f)); lineTo(cx - dp(4f), cy + dp(3f)); lineTo(cx - dp(8f), cy + dp(3f)); close()
            }
            icon.style = Paint.Style.FILL; c.drawPath(body, icon); icon.style = Paint.Style.STROKE
            if (level > 0f) c.drawArc(RectF(cx - dp(2f), cy - dp(6f), cx + dp(8f), cy + dp(6f)), -50f, 100f, false, icon)
            if (level > 0.5f) c.drawArc(RectF(cx - dp(2f), cy - dp(10f), cx + dp(12f), cy + dp(10f)), -50f, 100f, false, icon)
            if (level == 0f) { c.drawLine(cx + dp(4f), cy - dp(4f), cx + dp(10f), cy + dp(4f), icon); c.drawLine(cx + dp(10f), cy - dp(4f), cx + dp(4f), cy + dp(4f), icon) }
        }

        // Drag up/down on the pill to set the volume directly, like iPhone.
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
                val l = (1f - e.y / height).coerceIn(0f, 1f)
                val max = audio.getStreamMaxVolume(stream)
                audio.setStreamVolume(stream, (l * max).toInt(), 0)
                level = audio.getStreamVolume(stream).toFloat() / max.coerceAtLeast(1)
                invalidate()
                main.removeCallbacks(hideVolume); main.postDelayed(hideVolume, 1600)
            }
            return true
        }
    }

    // ================================================================ banners

    private var bannerView: BannerView? = null
    private val hideBanner = Runnable { dismissBanner() }

    private fun dismissBanner() {
        val v = bannerView ?: return
        v.animate().translationY(-v.height.toFloat() - dp(60f)).setDuration(250).withEndAction {
            runCatching { wm.removeView(v) }; if (bannerView === v) bannerView = null
        }.start()
    }

    private val seenKeys = LinkedHashMap<String, Long>()

    fun banner(sbn: StatusBarNotification) {
        val p = App.instance.prefs
        if (!p.iosBanners) return
        val n = sbn.notification
        // Updates of a notification already shown (progress, edits) don't drop a new banner.
        val again = seenKeys.containsKey(sbn.key)
        seenKeys[sbn.key] = sbn.postTime
        if (seenKeys.size > 200) seenKeys.remove(seenKeys.keys.first())
        if (again && (n.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE) != 0) return
        if (again && n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT) == null) return
        if (sbn.isOngoing || !sbn.isClearable || (n.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (n.extras.getBoolean("android.isGroupConversation") && n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT) == null) return
        val pkg = sbn.packageName
        if (pkg == service.packageName && n.channelId == "calls_incoming") return
        if (service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        val nm = service.getSystemService(NotificationManager::class.java)
        if (nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL) return
        if (!com.glass.launcher.feature.Focus.allows(pkg)) return
        if (LockService.navInstance?.fgPkg == pkg) return // you're already in that app
        if (n.`when` > 0 && System.currentTimeMillis() - sbn.postTime > 8000) return
        val hidden = pkg in p.hidden
        if (hidden || pkg in p.removed || pkg in p.disguise) return // never reveal hidden apps
        val locked = pkg in p.locked
        val ex = n.extras
        val title = if (locked) "התראה חדשה" else ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = if (locked) "" else (ex.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(android.app.Notification.EXTRA_TEXT))?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return
        val label = App.instance.repo.byPackage(pkg)?.label ?: runCatching {
            service.packageManager.getApplicationLabel(service.packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        val icon = App.instance.icons.iconForPackage(pkg, dp(38f).toInt())
        main.post { showBanner(label, title, text, icon, n.contentIntent, sbn.key) }
    }

    private fun showBanner(app: String, title: String, text: String, icon: Bitmap?, open: android.app.PendingIntent?, key: String) {
        bannerView?.let { runCatching { wm.removeView(it) } }
        val w = service.resources.displayMetrics.widthPixels
        val v = BannerView(service, app, title, text, icon, onTap = {
            runCatching { open?.send(service, 0, null, null, null, null, android.app.ActivityOptions.makeBasic().apply {
                if (android.os.Build.VERSION.SDK_INT >= 34) setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }.toBundle()) }
            com.glass.launcher.feature.NotifListener.instance?.let { l -> runCatching { if (open != null) l.cancelNotification(key) } }
            dismissBanner()
        }, onSwipeUp = { dismissBanner() })
        val lp = params((w - dp(16f) * 2).toInt(), WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL, true).apply {
            y = dp(8f).toInt() + statusBarHeight()
        }
        v.translationY = -dp(160f)
        runCatching { wm.addView(v, lp) }
        bannerView = v
        v.animate().translationY(0f).setDuration(380).setInterpolator(OvershootInterpolator(0.9f)).start()
        main.removeCallbacks(hideBanner)
        main.postDelayed(hideBanner, 5000)
    }

    private fun statusBarHeight(): Int {
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) service.resources.getDimensionPixelSize(id) else dp(24f).toInt()
    }

    @SuppressLint("ViewConstructor")
    private inner class BannerView(
        ctx: Context, val app: String, val title: String, val body: String, val icon: Bitmap?,
        val onTap: () -> Unit, val onSwipeUp: () -> Unit,
    ) : View(ctx) {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; textSize = dp(13f) }
        private val bold = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(font, Typeface.BOLD); textSize = dp(15.5f) }
        private val reg = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; textSize = dp(15.5f) }
        private var downY = 0f
        private var moved = false

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

        override fun onMeasure(wSpec: Int, hSpec: Int) {
            val w = MeasureSpec.getSize(wSpec)
            val lines = if (body.isBlank()) 0 else if (reg.measureText(body) > w - dp(80f)) 2 else 1
            setMeasuredDimension(w, (dp(18f) + dp(20f) + dp(22f) * lines + dp(26f)).toInt())
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val r = RectF(dp(4f), dp(2f), w - dp(4f), h - dp(6f))
            shadow.setShadowLayer(dp(10f), 0f, dp(3f), Color.argb(70, 0, 0, 0)); shadow.color = Color.TRANSPARENT
            bg.color = if (dark) Color.argb(240, 44, 44, 46) else Color.argb(245, 246, 246, 248)
            bg.setShadowLayer(dp(10f), 0f, dp(3f), Color.argb(60, 0, 0, 0))
            c.drawRoundRect(r, dp(22f), dp(22f), bg)
            val fg = if (dark) Color.WHITE else Color.BLACK
            val sub = if (dark) Color.argb(160, 255, 255, 255) else Color.argb(150, 60, 60, 67)
            // RTL: icon on the right, text to its left.
            val iconSize = dp(38f)
            val ix = r.right - dp(14f) - iconSize
            val iy = r.top + dp(14f)
            icon?.let { c.drawBitmap(it, null, RectF(ix, iy, ix + iconSize, iy + iconSize), null) }
            val textRight = ix - dp(12f)
            val textLeft = r.left + dp(14f)
            val avail = textRight - textLeft
            small.color = sub; small.textAlign = Paint.Align.LEFT
            c.drawText("עכשיו", textLeft, iy + dp(12f), small)
            small.textAlign = Paint.Align.RIGHT
            c.drawText(TextUtils.ellipsize(app, small, avail * 0.6f, TextUtils.TruncateAt.END).toString(), textRight, iy + dp(12f), small)
            bold.color = fg; bold.textAlign = Paint.Align.RIGHT
            c.drawText(TextUtils.ellipsize(title, bold, avail, TextUtils.TruncateAt.END).toString(), textRight, iy + dp(32f), bold)
            if (body.isNotBlank()) {
                reg.color = fg; reg.textAlign = Paint.Align.RIGHT
                val first = cut(body, avail)
                c.drawText(first, textRight, iy + dp(53f), reg)
                val rest = body.removePrefix(first).trim()
                if (rest.isNotEmpty()) c.drawText(TextUtils.ellipsize(rest, reg, avail, TextUtils.TruncateAt.END).toString(), textRight, iy + dp(74f), reg)
            }
        }

        private fun cut(s: String, avail: Float): String {
            val n = reg.breakText(s, true, avail, null)
            if (n >= s.length) return s
            val sp = s.lastIndexOf(' ', n).takeIf { it > n / 2 } ?: n
            return s.substring(0, sp)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; moved = false; main.removeCallbacks(hideBanner) }
                MotionEvent.ACTION_MOVE -> { val dy = e.rawY - downY; if (abs(dy) > dp(8f)) moved = true; translationY = dy.coerceAtMost(dp(20f)) }
                MotionEvent.ACTION_UP -> {
                    val dy = e.rawY - downY
                    if (dy < -dp(30f)) onSwipeUp()
                    else if (!moved) onTap()
                    else { animate().translationY(0f).setDuration(200).start(); main.postDelayed(hideBanner, 3000) }
                }
            }
            return true
        }
    }

    // ================================================================ AirPods card

    private var podsView: PodsView? = null
    private var lastLive = false
    private var lastCardAt = 0L

    /** Called whenever a new AirPods reading arrives. Shows the card once when they "come out of the case". */
    fun onPods(s: AirPodsState?) {
        if (!App.instance.prefs.podsCard) return
        val live = s != null && AirPods.isLive(s)
        if (live && !lastLive && System.currentTimeMillis() - lastCardAt > 15 * 60_000L &&
            !service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            lastCardAt = System.currentTimeMillis()
            main.post { showPods(s!!) }
        }
        lastLive = live
        podsView?.let { v -> if (s != null) { v.s = s; v.invalidate() } }
    }

    private fun showPods(s: AirPodsState) {
        podsView?.let { runCatching { wm.removeView(it) } }
        val w = service.resources.displayMetrics.widthPixels
        val v = PodsView(service, s)
        val lp = params((w - dp(20f)).toInt(), dp(250f).toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, true).apply { y = dp(14f).toInt() }
        v.translationY = dp(300f)
        runCatching { wm.addView(v, lp) }
        podsView = v
        v.animate().translationY(0f).setDuration(420).setInterpolator(DecelerateInterpolator(2f)).start()
        main.postDelayed({ hidePods() }, 7000)
    }

    private fun hidePods() {
        val v = podsView ?: return
        v.animate().translationY(dp(300f)).setDuration(260).withEndAction { runCatching { wm.removeView(v) }; if (podsView === v) podsView = null }.start()
    }

    @SuppressLint("ViewConstructor")
    private inner class PodsView(ctx: Context, var s: AirPodsState) : View(ctx) {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(5f); strokeCap = Paint.Cap.ROUND }
        private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; textAlign = Paint.Align.CENTER }
        private val pod = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            bg.color = if (dark) Color.argb(245, 30, 30, 32) else Color.argb(250, 255, 255, 255)
            c.drawRoundRect(RectF(0f, 0f, w, h), dp(36f), dp(36f), bg)
            val fg = if (dark) Color.WHITE else Color.BLACK
            tp.color = fg; tp.textSize = dp(20f); tp.typeface = Typeface.create(font, Typeface.BOLD)
            c.drawText("AirPods Pro", w / 2, dp(40f), tp)
            val items = listOf(Triple("שמאל", s.left, s.leftCharging), Triple("מארז", s.case, s.caseCharging), Triple("ימין", s.right, s.rightCharging))
            val cy = h * 0.55f
            items.forEachIndexed { i, (name, v, ch) ->
                val cx = w * (0.2f + 0.3f * i)
                val r = dp(34f)
                ring.color = if (dark) Color.argb(60, 255, 255, 255) else Color.argb(40, 0, 0, 0)
                c.drawCircle(cx, cy, r, ring)
                if (v != null) {
                    ring.color = when { ch -> Color.rgb(52, 199, 89); v <= 20 -> Color.rgb(255, 59, 48); else -> Color.rgb(52, 199, 89) }
                    c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 360f * v / 100f, false, ring)
                }
                // small white pod / case shape in the middle
                pod.color = if (dark) Color.argb(230, 255, 255, 255) else Color.argb(255, 220, 220, 225)
                if (i == 1) c.drawRoundRect(RectF(cx - dp(13f), cy - dp(10f), cx + dp(13f), cy + dp(10f)), dp(7f), dp(7f), pod)
                else { c.drawCircle(cx, cy - dp(6f), dp(7f), pod); c.drawRoundRect(RectF(cx - dp(2.5f), cy - dp(4f), cx + dp(2.5f), cy + dp(12f)), dp(2f), dp(2f), pod) }
                tp.typeface = font; tp.textSize = dp(15f); tp.color = fg
                c.drawText(if (v != null) "$v%" + (if (ch) " ⚡" else "") else "—", cx, cy + r + dp(24f), tp)
                tp.textSize = dp(12f); tp.color = if (dark) Color.argb(150, 255, 255, 255) else Color.argb(150, 60, 60, 67)
                c.drawText(name, cx, cy + r + dp(42f), tp)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean { if (e.action == MotionEvent.ACTION_UP) hidePods(); return true }
    }

    @Suppress("unused") private fun anim(from: Float, to: Float, f: (Float) -> Unit) =
        ValueAnimator.ofFloat(from, to).apply { addUpdateListener { f(it.animatedValue as Float) }; start() }
}
