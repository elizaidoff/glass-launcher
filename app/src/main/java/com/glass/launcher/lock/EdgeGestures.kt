package com.glass.launcher.lock

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.glass.launcher.App
import com.glass.launcher.data.GestureAction
import com.glass.launcher.data.GestureZone
import com.glass.launcher.feature.SystemActions
import kotlin.math.abs

/**
 * Thin touch strips drawn by the accessibility service on top of everything:
 * the bottom edge (split into three thirds) and, optionally, the side edges.
 * Each swipe runs the action chosen in the launcher settings.
 */
class EdgeGestures(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val views = mutableListOf<View>()
    private var lastConfig = ""
    private val density = service.resources.displayMetrics.density

    private fun dp(v: Int) = (v * density).toInt()

    private fun navBarHeight(): Int {
        val id = service.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) service.resources.getDimensionPixelSize(id) else dp(48)
    }

    fun refresh() {
        val p = App.instance.prefs
        val cfg = listOf(p.gesturesEnabled, p.coverNavButtons, p.showHomeBar, p.sideEdges).joinToString()
        if (cfg == lastConfig) return
        lastConfig = cfg
        removeAll()
        if (!p.gesturesEnabled) return
        addBottom(p.coverNavButtons, p.showHomeBar)
        if (p.sideEdges) {
            addSide(left = false)
            addSide(left = true)
        }
    }

    fun removeAll() {
        views.forEach { runCatching { wm.removeView(it) } }
        views.clear()
    }

    private fun baseParams(w: Int, h: Int, gravity: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        this.gravity = gravity
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private fun addBottom(cover: Boolean, pill: Boolean) {
        val h = if (cover) navBarHeight() else dp(20)
        val v = BottomBar(service, cover, pill)
        runCatching { wm.addView(v, baseParams(WindowManager.LayoutParams.MATCH_PARENT, h, Gravity.BOTTOM)); views.add(v) }
    }

    private fun addSide(left: Boolean) {
        val v = SideEdge(service, left)
        val h = (service.resources.displayMetrics.heightPixels * 0.55f).toInt()
        val g = (if (left) Gravity.LEFT else Gravity.RIGHT) or Gravity.CENTER_VERTICAL
        runCatching { wm.addView(v, baseParams(dp(14), h, g)); views.add(v) }
    }

    private fun run(view: View, zone: GestureZone) {
        val a = App.instance.prefs.gestureAction(zone)
        if (a == GestureAction.NONE) return
        if (App.instance.prefs.haptics) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        when (a) {
            GestureAction.NONE -> {}
            GestureAction.BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            GestureAction.HOME -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            GestureAction.RECENTS -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            GestureAction.NOTIFICATIONS -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            GestureAction.QUICK_SETTINGS -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            GestureAction.SCREENSHOT -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            GestureAction.LOCK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            GestureAction.ASSISTANT -> SystemActions.openAssistant(service)
            GestureAction.TORCH -> SystemActions.toggleTorch(service)
        }
    }

    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class BottomBar(ctx: Context, private val cover: Boolean, private val pill: Boolean) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var downX = 0f
        private var downY = 0f

        init { setOnTouchListener { _, e -> onTouch(e) } }

        private fun onTouch(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY }
                MotionEvent.ACTION_UP -> {
                    val dy = downY - e.rawY
                    if (dy > dp(28) && dy > abs(e.rawX - downX)) {
                        val w = resources.displayMetrics.widthPixels
                        val zone = when {
                            downX < w / 3f -> GestureZone.BOTTOM_LEFT
                            downX > w * 2 / 3f -> GestureZone.BOTTOM_RIGHT
                            else -> GestureZone.BOTTOM_CENTER
                        }
                        run(this, zone)
                    }
                }
            }
            return true
        }

        override fun onDraw(c: Canvas) {
            if (cover) c.drawColor(Color.BLACK)
            if (!pill) return
            val pw = width * 0.36f
            val ph = dp(5).toFloat()
            val cy = if (cover) height / 2f else height - dp(8).toFloat()
            paint.color = if (cover) Color.WHITE else Color.argb(170, 255, 255, 255)
            c.drawRoundRect(RectF(width / 2f - pw / 2, cy - ph / 2, width / 2f + pw / 2, cy + ph / 2), ph, ph, paint)
        }
    }

    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class SideEdge(ctx: Context, private val left: Boolean) : View(ctx) {
        private var downX = 0f
        private var downY = 0f

        init { setOnTouchListener { _, e -> onTouch(e) } }

        private fun onTouch(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY }
                MotionEvent.ACTION_UP -> {
                    val dx = if (left) e.rawX - downX else downX - e.rawX
                    if (dx > dp(40) && dx > abs(e.rawY - downY)) run(this, if (left) GestureZone.EDGE_LEFT else GestureZone.EDGE_RIGHT)
                }
            }
            return true
        }
    }
}
