package com.glass.launcher.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat
import com.glass.launcher.App
import com.glass.launcher.feature.AirPods
import com.glass.launcher.feature.SystemActions
import com.glass.launcher.lock.LockGuard
import kotlinx.coroutines.flow.MutableSharedFlow

class LauncherActivity : ComponentActivity() {

    /** Fires when the Home button is pressed while the launcher is already open. */
    val homePressed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { homePressed.tryEmit(Unit) }
        })
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                LauncherRoot(this)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        homePressed.tryEmit(Unit)
    }

    override fun onStart() {
        super.onStart()
        runCatching { App.instance.widgetHost.startListening() }
        SystemActions.watchTorch(this)
    }

    override fun onResume() {
        super.onResume()
        App.instance.repo.reload()
        val w = App.instance.prefs.widgets
        if (w.contains("builtin:airpods") || w.contains("builtin:battery")) AirPods.start(this)
    }

    override fun onPause() {
        super.onPause()
        AirPods.stop(this)
        // The hidden folders disappear every time you leave.
        LockGuard.hiddenRevealed.value = false
    }

    override fun onStop() {
        super.onStop()
        runCatching { App.instance.widgetHost.stopListening() }
    }
}
