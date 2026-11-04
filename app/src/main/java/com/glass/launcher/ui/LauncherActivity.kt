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
    val isDefaultHome = androidx.compose.runtime.mutableStateOf(true)

    private fun checkHome() {
        isDefaultHome.value = getSystemService(android.app.role.RoleManager::class.java)
            .isRoleHeld(android.app.role.RoleManager.ROLE_HOME)
    }

    fun requestHome() {
        val rm = getSystemService(android.app.role.RoleManager::class.java)
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), 1)
        }.onFailure {
            com.glass.launcher.lock.LockGuard.startUnlocked(this, android.content.Intent(android.provider.Settings.ACTION_HOME_SETTINGS))
        }
    }

    private var pendingWidgetId = -1
    private var pendingWidgetInfo: android.appwidget.AppWidgetProviderInfo? = null
    private var pendingWidgetDone: ((Int) -> Unit)? = null

    /** Asks Android for permission to show another app's widget, runs its setup screen if it has one. */
    fun bindWidget(info: android.appwidget.AppWidgetProviderInfo, done: (Int) -> Unit) {
        val host = App.instance.widgetHost
        val id = host.allocateAppWidgetId()
        pendingWidgetId = id
        pendingWidgetInfo = info
        pendingWidgetDone = done
        if (android.appwidget.AppWidgetManager.getInstance(this).bindAppWidgetIdIfAllowed(id, info.provider)) {
            configureWidget()
        } else {
            LockGuard.unlock(App.instance.settingsPackage)
            @Suppress("DEPRECATION")
            startActivityForResult(
                android.content.Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider),
                20,
            )
        }
    }

    private fun configureWidget() {
        val info = pendingWidgetInfo ?: return
        if (info.configure != null) {
            runCatching { App.instance.widgetHost.startAppWidgetConfigureActivityForResult(this, pendingWidgetId, 0, 21, null) }
                .onFailure { finishWidget(true) }
        } else finishWidget(true)
    }

    private fun finishWidget(ok: Boolean) {
        val id = pendingWidgetId
        if (ok) pendingWidgetDone?.invoke(id) else if (id >= 0) App.instance.widgetHost.deleteAppWidgetId(id)
        pendingWidgetId = -1
        pendingWidgetInfo = null
        pendingWidgetDone = null
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            20 -> if (resultCode == RESULT_OK) configureWidget() else finishWidget(false)
            21 -> finishWidget(resultCode == RESULT_OK)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        App.instance.repo.launcherView = java.lang.ref.WeakReference(window.decorView)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { homePressed.tryEmit(Unit) }
        })
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                KitText { LauncherRoot(this) }
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
        hideSystemNav()
        // Back on the home screen: everything that was unlocked (real Settings included) locks again,
        // so returning to it from Recent apps asks again.
        LockGuard.relockSessions()
        checkHome()
        val p = App.instance.prefs
        if (!p.onboarded) {
            p.onboarded = true
            if (!isDefaultHome.value) requestHome()
        }
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
