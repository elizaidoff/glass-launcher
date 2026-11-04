package com.glass.launcher.lock

import androidx.compose.runtime.setValue

import androidx.compose.foundation.border

import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.material.icons.rounded.Fingerprint

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glass.launcher.App
import com.glass.launcher.decoy.DecoySettingsActivity
import com.glass.launcher.ui.LauncherActivity
import com.glass.launcher.ui.glass
import com.glass.launcher.ui.setBackdropBlur
import com.glass.launcher.ui.tap

/**
 * Two faces:
 *  - Settings (when the imitation is on): looks exactly like an app loading screen.
 *    Up to 3 seconds it silently waits for the owner's fingerprint. Owner -> real Settings. Anyone else -> imitation.
 *  - Any other locked app: an open lock screen asking for face or fingerprint.
 */
class LockActivity : ComponentActivity() {

    private var pkg: String = ""
    private var launchAfter = false
    private var cancel: CancellationSignal? = null
    private val handler = Handler(Looper.getMainLooper())
    private var finished = false
    private val promptFailed = mutableStateOf(false)
    private var prompting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
        readIntent(intent)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
        render()
    }

    private fun readIntent(i: Intent) {
        pkg = i.getStringExtra(EXTRA_PKG) ?: ""
        launchAfter = i.getBooleanExtra(EXTRA_LAUNCH, false)
        finished = false
        promptFailed.value = false
    }

    /** Package whose look this app wears, when it is disguised (and opened from the launcher). */
    private val coverPkg: String? get() = if (launchAfter) App.instance.prefs.disguise[pkg] else null

    private fun render() {
        val cover = coverPkg
        val decoy = LockGuard.isDecoyTarget(pkg) || cover != null
        val icon = App.instance.icons.iconForPackage(cover ?: pkg, 288)?.asImageBitmap()
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault("")
        if (decoy) {
            val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            setContent { SplashLook(icon, dark) }
            startSilentCheck()
        } else {
            setBackdropBlur(90)
            val method = App.instance.prefs.lockMethod.let { if (App.instance.prefs.pinHash == null) "bio" else it }
            setContent {
                val failed by promptFailed
                if (method == "bio") LockLook(icon, label, failed, onRetry = { askOpenly(label) }, onClose = { goHome() })
                else PinLook(icon, label, showBio = method == "both", onBio = { askOpenly(label) }, onPin = { pin ->
                    val ok = App.instance.prefs.checkPin(pin)
                    if (ok) success()
                    ok
                }, onClose = { goHome() })
            }
            if (method != "pin") handler.postDelayed({ askOpenly(label) }, 250)
        }
    }

    private fun startSilentCheck() {
        cancel?.cancel()
        val c = CancellationSignal()
        cancel = c
        val listening = Auth.silentFingerprint(this, c) { ok ->
            handler.post { if (ok) success() else showImitation() }
        }
        // Owner did not touch the sensor in time (or the phone cannot read silently): show the imitation.
        handler.postDelayed({ if (!finished) showImitation() }, if (listening) 3000 else 1200)
    }

    private fun askOpenly(label: String) {
        promptFailed.value = false
        prompting = true
        Auth.prompt(
            this,
            title = if (label.isNotEmpty()) "פתיחת $label" else "פתיחה",
            onSuccess = { prompting = false; success() },
            onFail = { prompting = false; promptFailed.value = true },
        )
    }

    private fun success() {
        if (finished) return
        finished = true
        cancel?.cancel()
        LockGuard.unlock(pkg)
        if (coverPkg != null) LockGuard.disguiseUnlocked.add(pkg)
        if (launchAfter) {
            if (pkg == App.instance.settingsPackage) {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            } else {
                App.instance.repo.byPackage(pkg)?.let { App.instance.repo.launch(it) }
                    ?: runCatching { startActivity(packageManager.getLaunchIntentForPackage(pkg)) }
            }
        }
        finishQuietly()
    }

    private fun showImitation() {
        if (finished) return
        finished = true
        cancel?.cancel()
        val cover = coverPkg
        if (cover != null) {
            // Not the owner: open the app this one is dressed up as.
            packageManager.getLaunchIntentForPackage(cover)?.let { runCatching { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        } else DecoySettingsActivity.start(this)
        finishQuietly()
    }

    private fun goHome() {
        finished = true
        cancel?.cancel()
        startActivity(Intent(this, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finishQuietly()
    }

    @Suppress("DEPRECATION")
    private fun finishQuietly() {
        handler.removeCallbacksAndMessages(null)
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onStop() {
        super.onStop()
        // Leaving the lock screen without unlocking must never reveal the app underneath.
        if (!finished && !prompting) {
            cancel?.cancel()
            if (LockGuard.isDecoyTarget(pkg) || coverPkg != null) showImitation() else goHome()
        }
    }

    companion object {
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_LAUNCH = "launch"

        fun start(ctx: Context, pkg: String, launchAfter: Boolean) {
            ctx.startActivity(
                Intent(ctx, LockActivity::class.java)
                    .putExtra(EXTRA_PKG, pkg)
                    .putExtra(EXTRA_LAUNCH, launchAfter)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }
    }
}

/** iPhone-style passcode keypad. onPin returns whether the code was right. */
@Composable
private fun PinLook(
    icon: androidx.compose.ui.graphics.ImageBitmap?,
    label: String,
    showBio: Boolean,
    onBio: () -> Unit,
    onPin: (String) -> Boolean,
    onClose: () -> Unit,
) {
    var code by androidx.compose.runtime.remember { mutableStateOf("") }
    var wrong by androidx.compose.runtime.remember { mutableStateOf(false) }
    val shake = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    val view = androidx.compose.ui.platform.LocalView.current
    val len = App.instance.prefs.pinLength
    androidx.compose.runtime.LaunchedEffect(wrong) {
        if (wrong) {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.REJECT)
            for (x in listOf(18f, -16f, 12f, -8f, 4f, 0f)) shake.animateTo(x, tween(45))
            code = ""; wrong = false
        }
    }
    fun press(d: String) {
        if (code.length >= len) return
        view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        code += d
        if (code.length == len && !onPin(code)) wrong = true
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) Image(icon, null, Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            Text(if (wrong) "קוד שגוי" else "הזנת קוד – $label", color = Color.White, fontSize = 19.sp)
            Spacer(Modifier.height(20.dp))
            androidx.compose.foundation.layout.Row(
                Modifier.graphicsLayer { translationX = shake.value * density },
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                repeat(len) { i ->
                    Box(
                        Modifier.size(13.dp).clip(CircleShape)
                            .background(if (i < code.length) Color.White else Color.Transparent)
                            .border(1.4.dp, Color.White, CircleShape)
                    )
                }
            }
            Spacer(Modifier.height(44.dp))
            val rows = listOf(
                listOf("1" to "", "2" to "ABC", "3" to "DEF"),
                listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
                listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
            )
            // Digits keep the phone's left-to-right order even on a Hebrew screen, like iPhone.
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    rows.forEach { r ->
                        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            r.forEach { (d, sub) -> Key(d, sub) { press(d) } }
                        }
                    }
                    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(78.dp).tap { if (showBio) onBio() }, contentAlignment = Alignment.Center) {
                            if (showBio) Icon(Icons.Rounded.Fingerprint, null, tint = Color.White, modifier = Modifier.size(36.dp))
                        }
                        Key("0", "") { press("0") }
                        Box(Modifier.size(78.dp).tap { if (code.isEmpty()) onClose() else code = code.dropLast(1) }, contentAlignment = Alignment.Center) {
                            Text(if (code.isEmpty()) "ביטול" else "מחיקה", color = Color.White, fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Key(d: String, sub: String, onClick: () -> Unit) {
    Box(
        Modifier.size(78.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)).tap(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(d, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Light)
            if (sub.isNotEmpty()) Text(sub, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
        }
    }
}

/** Plain Android 12+ style app splash: the app icon centred on the system background. */
@Composable
private fun SplashLook(icon: androidx.compose.ui.graphics.ImageBitmap?, dark: Boolean) {
    Box(
        Modifier.fillMaxSize().background(if (dark) Color(0xFF121212) else Color(0xFFF8F9FA)),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Image(icon, null, Modifier.size(96.dp).clip(CircleShape))
    }
}

@Composable
private fun LockLook(
    icon: androidx.compose.ui.graphics.ImageBitmap?,
    label: String,
    failed: Boolean,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val a by pulse.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (icon != null) Image(icon, null, Modifier.size(84.dp))
            Spacer(Modifier.height(18.dp))
            Text(label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(if (failed) "האימות לא הצליח" else "נדרש אימות", color = Color.White.copy(alpha = 0.75f), fontSize = 15.sp)
            Spacer(Modifier.height(36.dp))
            Icon(Icons.Rounded.Face, null, tint = Color.White, modifier = Modifier.size(54.dp).alpha(if (failed) 1f else a))
            Spacer(Modifier.height(36.dp))
            if (failed) {
                Box(
                    Modifier.glass(RoundedCornerShape(50)).tap(onRetry).padding(horizontal = 34.dp, vertical = 12.dp)
                ) { Text("ניסיון נוסף", color = Color.White, fontSize = 16.sp) }
                Spacer(Modifier.height(12.dp))
            }
            Text("ביטול", color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp, modifier = Modifier.tap(onClose).padding(12.dp))
        }
    }
}
