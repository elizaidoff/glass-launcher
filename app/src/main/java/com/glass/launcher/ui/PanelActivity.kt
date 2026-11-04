package com.glass.launcher.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.glass.launcher.App

/**
 * Shows our Notification Center or Control Center on top of any app
 * (opened by pulling down from the top of the screen).
 */
class PanelActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setBackdropBlur(80)
        val panel = intent.getStringExtra("panel") ?: "control"
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                KitText {
                    if (panel == "notifications") NotificationCenter(onClose = { close() })
                    else ControlCenter(onClose = { close() })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemNav()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish()
    }

    @Suppress("DEPRECATION")
    private fun close() {
        finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    companion object {
        fun open(ctx: Context, panel: String) {
            ctx.startActivity(
                Intent(ctx, PanelActivity::class.java)
                    .putExtra("panel", panel)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }
    }
}

/** On our own screens Android's buttons are hidden (the iPhone gestures drive navigation instead). */
fun Activity.hideSystemNav() {
    val mode = App.instance.prefs.navMode
    val c = WindowCompat.getInsetsController(window, window.decorView)
    if (mode != "off" && mode != "android") {
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.navigationBars())
    } else {
        c.show(WindowInsetsCompat.Type.navigationBars())
    }
}
