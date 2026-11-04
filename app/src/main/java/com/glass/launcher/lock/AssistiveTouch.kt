package com.glass.launcher.lock

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.glass.launcher.App
import com.glass.launcher.ui.PanelActivity
import kotlin.math.abs

/**
 * iPhone's Assistive Touch: a floating translucent button you can drag to any edge.
 * Tap → a small menu (Home, Back, Recents, Control Center, Notifications, Lock, Screenshot).
 * Costs nothing while idle: plain views, no timers.
 */
class AssistiveTouch(private val service: AccessibilityService) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private var button: Ball? = null
    private var menu: View? = null
    private val d = service.resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()

    fun refresh() {
        val on = App.instance.prefs.assistiveTouch
        if (on && button == null) show() else if (!on) remove()
    }

    private fun params(w: Int, h: Int, touchable: Boolean = true) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun show() {
        val p = App.instance.prefs
        val size = dp(58f)
        val dm = service.resources.displayMetrics
        val lp = params(size, size).apply {
            x = if (p.assistiveX >= 0) p.assistiveX else dm.widthPixels - size - dp(6f)
            y = if (p.assistiveY >= 0) p.assistiveY else dm.heightPixels / 2
        }
        val b = Ball(service, lp)
        button = b
        runCatching { wm.addView(b, lp) }
        b.alpha = 0.55f
    }

    fun remove() {
        closeMenu()
        button?.let { runCatching { wm.removeView(it) } }
        button = null
    }

    private fun closeMenu() {
        menu?.let { runCatching { wm.removeView(it) } }
        menu = null
        button?.visibility = View.VISIBLE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun openMenu() {
        if (menu != null) return
        button?.visibility = View.INVISIBLE
        val root = android.widget.FrameLayout(service)
        root.setBackgroundColor(Color.argb(60, 0, 0, 0))
        root.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN) closeMenu(); true }
        val box = GridLayout(service).apply {
            columnCount = 3
            background = GradientDrawable().apply { cornerRadius = dp(30f).toFloat(); setColor(Color.argb(225, 28, 28, 30)) }
            setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setOnTouchListener { _, _ -> true }
        }
        val items = listOf<Triple<String, String, () -> Unit>>(
            Triple("⌂", "בית") { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) },
            Triple("‹", "חזרה") { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) },
            Triple("❐", "אחרונות") { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) },
            Triple("◐", "מרכז שליטה") { PanelActivity.open(service, "control") },
            Triple("☰", "התראות") { PanelActivity.open(service, "notifications") },
            Triple("⌁", "צילום מסך") { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) },
            Triple("🔒", "נעילה") { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) },
            Triple("⇵", "סיבוב") { com.glass.launcher.feature.SystemActions.toggleRotationLock(service) },
            Triple("✕", "סגירה") { },
        )
        items.forEach { (sym, label, act) ->
            val cell = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4f), dp(8f), dp(4f), dp(8f))
                addView(TextView(service).apply { text = sym; textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
                addView(TextView(service).apply { text = label; textSize = 12f; setTextColor(Color.argb(220, 255, 255, 255)); gravity = Gravity.CENTER })
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    closeMenu()
                    // Let the menu vanish first, so screenshots don't include it.
                    it.postDelayed({ act() }, 120)
                }
            }
            box.addView(cell, GridLayout.LayoutParams().apply { width = dp(88f); height = dp(84f) })
        }
        root.addView(box, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
        ))
        box.scaleX = 0.6f; box.scaleY = 0.6f; box.alpha = 0f
        box.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).setInterpolator(android.view.animation.OvershootInterpolator(1.1f)).start()
        val lp = params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        menu = root
        runCatching { wm.addView(root, lp) }
    }

    /** The floating button: rounded square with three soft rings, like iPhone's. */
    @SuppressLint("ViewConstructor")
    private inner class Ball(ctx: Context, val lp: WindowManager.LayoutParams) : View(ctx) {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 30, 30, 30) }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false
        private val fade = Runnable { animate().alpha(0.55f).setDuration(300).start() }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            c.drawRoundRect(0f, 0f, w, w, w * 0.28f, w * 0.28f, bg)
            listOf(0.36f to 70, 0.27f to 120, 0.18f to 255).forEach { (r, a) ->
                ring.alpha = a
                c.drawCircle(w / 2, w / 2, w * r, ring)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    removeCallbacks(fade); alpha = 1f
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && abs(dx) + abs(dy) > dp(8f)) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(this, lp) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved) { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); openMenu() }
                    else snap()
                    postDelayed(fade, 2500)
                }
            }
            return true
        }

        private fun snap() {
            val dm = resources.displayMetrics
            val target = if (lp.x + width / 2 < dm.widthPixels / 2) dp(6f) else dm.widthPixels - width - dp(6f)
            lp.y = lp.y.coerceIn(dp(40f), dm.heightPixels - height - dp(40f))
            ValueAnimator.ofInt(lp.x, target).apply {
                duration = 260
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { lp.x = it.animatedValue as Int; runCatching { wm.updateViewLayout(this@Ball, lp) } }
                doOnEnd {
                    App.instance.prefs.assistiveX = lp.x
                    App.instance.prefs.assistiveY = lp.y
                }
                start()
            }
        }
    }

    private fun ValueAnimator.doOnEnd(f: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) = f()
    })
}
