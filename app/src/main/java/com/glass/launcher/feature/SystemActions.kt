package com.glass.launcher.feature

import android.app.NotificationManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import com.glass.launcher.App
import com.glass.launcher.lock.LockGuard
import kotlinx.coroutines.flow.MutableStateFlow

object SystemActions {
    val torchOn = MutableStateFlow(false)
    private var torchCallbackSet = false

    private fun torchId(ctx: Context): String? {
        val cm = ctx.getSystemService(CameraManager::class.java) ?: return null
        return runCatching {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        }.getOrNull()
    }

    fun watchTorch(ctx: Context) {
        if (torchCallbackSet) return
        val cm = ctx.getSystemService(CameraManager::class.java) ?: return
        cm.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { torchOn.value = enabled }
        }, null)
        torchCallbackSet = true
    }

    fun toggleTorch(ctx: Context) {
        val id = torchId(ctx) ?: return
        runCatching { ctx.getSystemService(CameraManager::class.java).setTorchMode(id, !torchOn.value) }
    }

    fun canWriteSettings(ctx: Context) = Settings.System.canWrite(ctx)

    /** 0..1 */
    fun brightness(ctx: Context): Float =
        runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)

    fun setBrightness(ctx: Context, v: Float) {
        if (!canWriteSettings(ctx)) return
        runCatching {
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (v.coerceIn(0.02f, 1f) * 255).toInt())
        }
    }

    fun rotationLocked(ctx: Context): Boolean =
        runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION) == 0 }.getOrDefault(false)

    fun toggleRotationLock(ctx: Context) {
        if (!canWriteSettings(ctx)) return
        runCatching {
            Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (rotationLocked(ctx)) 1 else 0)
        }
    }

    fun volume(ctx: Context, stream: Int = AudioManager.STREAM_MUSIC): Float {
        val am = ctx.getSystemService(AudioManager::class.java)
        return am.getStreamVolume(stream).toFloat() / am.getStreamMaxVolume(stream).coerceAtLeast(1)
    }

    fun setVolume(ctx: Context, v: Float, stream: Int = AudioManager.STREAM_MUSIC) {
        val am = ctx.getSystemService(AudioManager::class.java)
        runCatching { am.setStreamVolume(stream, (v * am.getStreamMaxVolume(stream)).toInt(), 0) }
    }

    fun dndAllowed(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    fun dndOn(ctx: Context) =
        ctx.getSystemService(NotificationManager::class.java).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL

    fun toggleDnd(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return
        nm.setInterruptionFilter(if (dndOn(ctx)) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY)
    }

    fun batteryPercent(ctx: Context): Int =
        ctx.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    fun batteryCharging(ctx: Context): Boolean = ctx.getSystemService(BatteryManager::class.java).isCharging

    private val panelClass: (String) -> Boolean = { it.contains("Panel", ignoreCase = true) }

    /** Small system panels that stay on top of the current screen. */
    fun openInternetPanel(ctx: Context) =
        LockGuard.startTrusted(ctx, Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY), panelClass)

    fun openWifiPanel(ctx: Context) =
        LockGuard.startTrusted(ctx, Intent(Settings.Panel.ACTION_WIFI), panelClass)

    fun openVolumePanel(ctx: Context) =
        LockGuard.startTrusted(ctx, Intent(Settings.Panel.ACTION_VOLUME), panelClass)

    fun openBluetooth(ctx: Context) =
        LockGuard.startTrusted(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) { it.contains("Bluetooth", ignoreCase = true) }

    fun openCamera(ctx: Context) = start(ctx, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))

    fun openAlarms(ctx: Context) = start(ctx, Intent(AlarmClock.ACTION_SHOW_ALARMS))

    fun openCalculator(ctx: Context) =
        start(ctx, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR))

    fun webSearch(ctx: Context, q: String) =
        start(ctx, Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q))

    const val GEMINI = "com.google.android.apps.bard"
    const val GOOGLE = "com.google.android.googlequicksearchbox"

    /** Gemini (or the app chosen in settings), opened the way Siri opens on an iPhone. */
    fun openAssistant(ctx: Context): Boolean {
        val pm = ctx.packageManager
        val chosen = App.instance.prefs.assistantPackage
        val tries = buildList {
            if (chosen != null) pm.getLaunchIntentForPackage(chosen)?.let { add(it) }
            // Gemini's own app first: the generic voice intents open a voice dialer on some phones.
            pm.getLaunchIntentForPackage(GEMINI)?.let { add(it) }
            add(Intent("android.intent.action.VOICE_ASSIST"))
            add(Intent(Intent.ACTION_ASSIST))
            pm.getLaunchIntentForPackage(GOOGLE)?.let { add(it) }
            add(Intent(Intent.ACTION_VOICE_COMMAND))
        }
        for (i in tries) {
            val target = i.resolveActivity(pm) ?: continue
            if (target.packageName.contains("dialer", true) || target.packageName.contains("voicedial", true)) continue
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true
            } catch (_: Exception) {
            }
        }
        android.widget.Toast.makeText(ctx, "לא נמצאה אפליקציית Gemini. אפשר לבחור עוזר בהגדרות זכוכית", android.widget.Toast.LENGTH_LONG).show()
        return false
    }

    fun start(ctx: Context, i: Intent): Boolean = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: Exception) { false }

    fun settingsPkg() = App.instance.settingsPackage
}
