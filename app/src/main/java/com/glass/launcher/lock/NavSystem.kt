package com.glass.launcher.lock

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.ImageView
import com.glass.launcher.App
import com.glass.launcher.ui.PanelActivity
import kotlin.math.abs
import kotlin.math.hypot

/**
 * iPhone navigation drawn by the accessibility service on top of everything:
 *  - a bar over Android's buttons (dark / transparent / in the app's colour) with the white home pill,
 *    taking the iPhone gestures: up = home (the app shrinks into its icon), up + hold = Android's Recents,
 *    along the bar = previous / next app, plain tap = the button below (optional);
 *  - back by swiping in from the side edge;
 *  - a strip over the status bar: pulling down opens our Notification Center / Control Center in any app.
 * Everything is removed when the service stops, so Android's own navigation always comes back.
 */
class NavSystem(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val d = service.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private var bar: BarView? = null
    private var barParams: WindowManager.LayoutParams? = null
    private val edges = mutableListOf<View>()
    private var top: View? = null
    private var topParams: WindowManager.LayoutParams? = null
    private var lastConfig = ""

    /** Foreground app right now, and the order in which apps were used (newest first). */
    @Volatile var fgPkg: String = ""
    private val history = ArrayDeque<String>()
    private var switchIndex = 0
    private var lastSwitchAt = 0L
    private var lastShot = 0L
    private var lastSampleFor = ""

    private fun dp(v: Float) = (v * d).toInt()

    private fun navBarHeight(): Int {
        val id = service.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return (if (id > 0) service.resources.getDimensionPixelSize(id) else dp(48f)) + dp(App.instance.prefs.navAdjust.toFloat())
    }

    private fun statusBarHeight(): Int {
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) service.resources.getDimensionPixelSize(id) else dp(28f)
    }

    private fun ownApp(pkg: String) = pkg == service.packageName

    fun refresh() {
        val p = App.instance.prefs
        val cfg = listOf(p.navMode, p.navAdjust, p.backEdge, p.topPull, p.topPullSplit).joinToString()
        if (cfg == lastConfig) { bar?.invalidate(); return }
        lastConfig = cfg
        removeAll()
        if (p.navMode != "off") addBar(p.navMode)
        if (p.navMode != "off" && p.navMode != "android") addEdges(p.backEdge)
        if (p.topPull) addTop()
    }

    fun removeAll() {
        bar?.let { runCatching { wm.removeView(it) } }
        bar = null
        edges.forEach { runCatching { wm.removeView(it) } }
        edges.clear()
        top?.let { runCatching { wm.removeView(it) } }
        top = null
    }

    private fun params(w: Int, h: Int, gravity: Int, touchable: Boolean = true) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        this.gravity = gravity
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    // ------------------------------------------------------------------ foreground tracking

    fun onForeground(pkg: String) {
        if (pkg == fgPkg) return
        fgPkg = pkg
        if (!ownApp(pkg)) {
            history.remove(pkg)
            history.addFirst(pkg)
            while (history.size > 12) history.removeLast()
        }
        bar?.invalidate()
        // Sample the app's colour for the cover (only on app change, so no battery cost while using it).
        if (App.instance.prefs.navMode == "appcolor" && !ownApp(pkg) && pkg !in App.instance.prefs.secureApps) {
            handler.removeCallbacks(sampleRun)
            handler.postDelayed(sampleRun, 450)
            handler.postDelayed(sampleRun, 1200)
        }
    }

    private val sampleRun = Runnable { sampleColour() }

    @Volatile var barColour: Int = Color.BLACK

    private fun sampleColour() {
        val pkg = fgPkg
        val now = SystemClock.uptimeMillis()
        if (now - lastShot < 1000) return // the system allows about one screenshot per second
        lastShot = now
        screenshot { bmp, secure ->
            if (secure) {
                val p = App.instance.prefs
                if (pkg.isNotEmpty() && pkg !in p.secureApps) p.secureApps = p.secureApps + (pkg to 1)
                bar?.invalidate()
                return@screenshot
            }
            bmp ?: return@screenshot
            val y = (bmp.height - navBarHeight() - dp(3f)).coerceIn(0, bmp.height - 1)
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            val step = (bmp.width / 24).coerceAtLeast(1)
            var x = step / 2
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
                x += step
            }
            if (n > 0) barColour = Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
            lastSampleFor = pkg
            bmp.recycle()
            bar?.invalidate()
        }
    }

    /** Takes a screenshot of the screen (Android 11+). Calls back with the bitmap, or secure = true if refused. */
    private fun screenshot(cb: (Bitmap?, Boolean) -> Unit) {
        runCatching {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    result.hardwareBuffer.close()
                    cb(soft, false)
                }

                override fun onFailure(errorCode: Int) {
                    cb(null, errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)
                }
            })
        }.onFailure { cb(null, false) }
    }

    // ------------------------------------------------------------------ bottom bar

    private fun addBar(mode: String) {
        val v = BarView(service, mode)
        val h = if (mode == "android") dp(22f) else navBarHeight()
        val lp = params(WindowManager.LayoutParams.MATCH_PARENT, h, Gravity.BOTTOM, touchable = mode != "android")
        runCatching { wm.addView(v, lp); bar = v; barParams = lp }
        v.setOnApplyWindowInsetsListener { view, insets ->
            (view as BarView).navVisible = insets.isVisible(WindowInsets.Type.navigationBars())
            view.invalidate()
            insets
        }
    }

    private fun goHome(fromPkg: String) {
        val p = App.instance.prefs
        val now = SystemClock.uptimeMillis()
        if (ownApp(fromPkg) || fromPkg.isEmpty() || now - lastShot < 1000) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            return
        }
        lastShot = now
        // Picture of the app as it is now, then shrink it into its icon on the home screen.
        screenshot { bmp, secure ->
            if (secure && fromPkg !in p.secureApps) p.secureApps = p.secureApps + (fromPkg to 1)
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            if (bmp != null) shrinkIntoIcon(bmp, fromPkg) else LockGuard.closedPkg.value = fromPkg
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun shrinkIntoIcon(bmp: Bitmap, pkg: String) {
        val sw = service.resources.displayMetrics.widthPixels
        val sh = service.resources.displayMetrics.heightPixels
        val target = App.instance.repo.iconBounds[pkg]
            ?: Rect(sw / 2 - dp(30f), sh / 2 - dp(30f), sw / 2 + dp(30f), sh / 2 + dp(30f))
        val iv = ImageView(service).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_XY
            pivotX = 0f; pivotY = 0f
            clipToOutline = true
        }
        var radius = dp(40f).toFloat()
        iv.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        val lp = params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.LEFT, touchable = false)
        runCatching { wm.addView(iv, lp) }.onFailure { bmp.recycle(); return }
        LockGuard.closedPkg.value = pkg
        val endScale = target.width().toFloat() / sw
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = PathInterpolator(0.25f, 0.9f, 0.3f, 1f)
            addUpdateListener {
                val f = it.animatedValue as Float
                val sc = 1f + (endScale - 1f) * f
                iv.scaleX = sc
                iv.scaleY = 1f + (target.height().toFloat() / sh - 1f) * f
                iv.translationX = target.left * f
                iv.translationY = target.top * f
                radius = dp(40f) + (target.width() * 0.45f / sc - dp(40f)) * f
                iv.invalidateOutline()
                iv.alpha = if (f > 0.75f) 1f - (f - 0.75f) / 0.25f else 1f
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    runCatching { wm.removeView(iv) }
                    bmp.recycle()
                }
            })
            start()
        }
    }

    private fun switchApp(dir: Int) {
        val now = SystemClock.uptimeMillis()
        val list = history.filter { it != service.packageName }
        if (list.size < 2) return
        if (now - lastSwitchAt > 4000) switchIndex = 0
        lastSwitchAt = now
        switchIndex = (switchIndex + dir).coerceIn(0, list.size - 1)
        if (switchIndex == 0 && dir < 0) return
        val pkg = list.getOrNull(switchIndex) ?: return
        val i = service.packageManager.getLaunchIntentForPackage(pkg) ?: return
        val anim = if (dir > 0) android.R.anim.slide_in_left else android.R.anim.slide_out_right
        val opts = ActivityOptions.makeCustomAnimation(service, anim, android.R.anim.fade_out).toBundle()
        runCatching { service.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts) }
    }

    private fun tapButton(x: Float, width: Int) {
        val order = if (App.instance.prefs.navOrder == "rhb") listOf("recents", "home", "back") else listOf("back", "home", "recents")
        val idx = (x / (width / 3f)).toInt().coerceIn(0, 2)
        when (order[idx]) {
            "back" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> goHome(fgPkg)
            "recents" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        }
    }

    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class BarView(ctx: Context, private val mode: String) : View(ctx) {
        var navVisible = true
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var downX = 0f
        private var downY = 0f
        private var downAt = 0L
        private var lastMoveY = 0f
        private var lastMoveAt = 0L
        private var held = false
        private var done = false

        init { setOnTouchListener { _, e -> touch(e) } }

        private fun touch(e: MotionEvent): Boolean {
            val sh = resources.displayMetrics.heightPixels
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; downAt = SystemClock.uptimeMillis()
                    lastMoveY = e.rawY; lastMoveAt = downAt; held = false; done = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val now = SystemClock.uptimeMillis()
                    val up = downY - e.rawY
                    // "Up and hold": the finger stops in the middle of the screen.
                    if (!held && up > sh * 0.12f && abs(e.rawY - lastMoveY) < dp(6f) && now - lastMoveAt > 260) {
                        held = true
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    if (abs(e.rawY - lastMoveY) >= dp(6f)) { lastMoveY = e.rawY; lastMoveAt = now }
                }
                MotionEvent.ACTION_UP -> {
                    if (done) return true
                    done = true
                    val up = downY - e.rawY
                    val side = e.rawX - downX
                    val dt = SystemClock.uptimeMillis() - downAt
                    when {
                        held -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                        up > dp(40f) -> { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); goHome(fgPkg) }
                        abs(side) > dp(60f) && abs(up) < dp(40f) -> {
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            // Right-to-left phone: swiping right goes to the previous app, like iPhone in Hebrew.
                            switchApp(if (side > 0) 1 else -1)
                        }
                        hypot(side, up) < dp(10f) && dt < 400 && App.instance.prefs.navButtonsTap && !ownApp(fgPkg) -> tapButton(e.x, width)
                    }
                }
            }
            return true
        }

        override fun onDraw(c: Canvas) {
            val p = App.instance.prefs
            val mine = ownApp(fgPkg)
            val fill: Int = when {
                mode == "android" -> Color.TRANSPARENT
                mine && !navVisible -> Color.TRANSPARENT // our screens hide Android's buttons themselves
                !navVisible -> Color.TRANSPARENT          // full-screen video/game: nothing to cover
                else -> {
                    val secure = p.secureApps[fgPkg]
                    when {
                        secure != null -> when (secure) { 0 -> Color.TRANSPARENT; 1 -> Color.BLACK; else -> secure }
                        mode == "transparent" -> Color.TRANSPARENT
                        mode == "appcolor" -> barColour
                        else -> Color.BLACK
                    }
                }
            }
            c.drawColor(fill)
            // The home pill: dark on light backgrounds, white otherwise.
            val light = Color.alpha(fill) > 0 && (0.299 * Color.red(fill) + 0.587 * Color.green(fill) + 0.114 * Color.blue(fill)) > 170
            paint.color = if (light) Color.argb(220, 0, 0, 0) else Color.argb(230, 255, 255, 255)
            val pw = width * 0.36f
            val ph = dp(5f).toFloat()
            val cy = if (mode == "android") height - dp(9f).toFloat() else height / 2f
            c.drawRoundRect(RectF(width / 2f - pw / 2, cy - ph / 2, width / 2f + pw / 2, cy + ph / 2), ph, ph, paint)
        }
    }

    // ------------------------------------------------------------------ back from the side edge

    private fun addEdges(which: String) {
        val h = (service.resources.displayMetrics.heightPixels * 0.62f).toInt()
        fun add(left: Boolean) {
            val v = EdgeView(service, left)
            val g = (if (left) Gravity.LEFT else Gravity.RIGHT) or Gravity.CENTER_VERTICAL
            runCatching { wm.addView(v, params(dp(12f), h, g)); edges.add(v) }
        }
        when (which) {
            "right" -> add(false)
            "left" -> add(true)
            "both" -> { add(false); add(true) }
        }
    }

    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class EdgeView(ctx: Context, private val left: Boolean) : View(ctx) {
        private var downX = 0f
        private var downY = 0f
        init { setOnTouchListener { _, e -> touch(e) } }
        private fun touch(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY }
                MotionEvent.ACTION_UP -> {
                    val dx = if (left) e.rawX - downX else downX - e.rawX
                    if (dx > dp(36f) && dx > abs(e.rawY - downY)) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    }
                }
            }
            return true
        }
    }

    // ------------------------------------------------------------------ pull down from the top

    private fun addTop() {
        val v = TopView(service)
        val lp = params(WindowManager.LayoutParams.MATCH_PARENT, statusBarHeight(), Gravity.TOP)
        runCatching { wm.addView(v, lp); top = v; topParams = lp }
        v.setOnApplyWindowInsetsListener { view, insets ->
            // Full screen (status bar hidden): step aside so the app gets its taps at the top.
            setTopTouchable(insets.isVisible(WindowInsets.Type.statusBars()))
            insets
        }
    }

    /** Over the lock screen, Android's own shade is used (nothing private shows before unlocking). */
    fun onScreenState() {
        val locked = service.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        setTopTouchable(!locked)
    }

    private fun setTopTouchable(on: Boolean) {
        val v = top ?: return
        val lp = topParams ?: return
        val flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (flags != lp.flags) { lp.flags = flags; runCatching { wm.updateViewLayout(v, lp) } }
    }

    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class TopView(ctx: Context) : View(ctx) {
        private var downX = 0f
        private var downY = 0f
        private var fired = false
        init { setOnTouchListener { _, e -> touch(e) } }
        private fun touch(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; fired = false }
                MotionEvent.ACTION_MOVE -> if (!fired && e.rawY - downY > dp(24f)) {
                    fired = true
                    val split = App.instance.prefs.topPullSplit
                    val leftSide = downX < resources.displayMetrics.widthPixels / 2f
                    val panel = when (split) {
                        "notifications" -> "notifications"
                        "control" -> "control"
                        else -> if (leftSide) "notifications" else "control"
                    }
                    PanelActivity.open(service, panel)
                }
            }
            return true
        }
    }
}
