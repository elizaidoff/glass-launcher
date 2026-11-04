package com.glass.launcher

import android.app.Application
import android.appwidget.AppWidgetHost
import android.content.Intent
import android.provider.Settings
import com.glass.launcher.data.AppRepository
import com.glass.launcher.data.IconLoader
import com.glass.launcher.data.Prefs

class App : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var repo: AppRepository
        private set
    lateinit var icons: IconLoader
        private set
    lateinit var widgetHost: AppWidgetHost
        private set

    /** Package of the system Settings app on this device. */
    val settingsPackage: String by lazy {
        packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)
            ?.activityInfo?.packageName ?: "com.android.settings"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        repo = AppRepository(this)
        icons = IconLoader(this)
        widgetHost = AppWidgetHost(this, WIDGET_HOST_ID)
    }

    companion object {
        const val WIDGET_HOST_ID = 1024
        lateinit var instance: App
            private set
    }
}
