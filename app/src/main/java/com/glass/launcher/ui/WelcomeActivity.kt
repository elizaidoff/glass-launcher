package com.glass.launcher.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.glass.launcher.App
import com.glass.launcher.feature.AirPods
import com.glass.launcher.feature.NotifListener
import com.glass.launcher.lock.LockGuard
import com.glass.launcher.lock.LockService

/**
 * First-run welcome, one step per screen like setting up a new iPhone.
 * Every step can be skipped; each one shows a green tick once it's done.
 */
class WelcomeActivity : ComponentActivity() {

    private data class Step(val icon: ImageVector, val color: Color, val title: String, val text: String, val button: String, val done: () -> Boolean, val action: () -> Unit)

    private val tick = mutableIntStateOf(0)

    private val steps by lazy {
        listOf(
            Step(Icons.Rounded.AutoAwesome, Kit.blue, "ברוך הבא לזכוכית", "מסך בית בעיצוב אייפון, בעברית מלאה. כמה צעדים קצרים ואתה מוכן.", "בוא נתחיל", { false }, {}),
            Step(Icons.Rounded.Home, Kit.blue, "מסך הבית", "כדי שכפתור הבית יחזיר לזכוכית, צריך לבחור בה כמסך הבית של הטלפון.", "בחירה כמסך הבית", {
                getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)
            }, {
                val rm = getSystemService(RoleManager::class.java)
                @Suppress("DEPRECATION")
                runCatching { startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 1) }
                    .onFailure { LockGuard.startUnlocked(this, Intent(Settings.ACTION_HOME_SETTINGS)) }
            }),
            Step(Icons.Rounded.TouchApp, Kit.indigo, "תנועות, נעילה ואי דינמי", "השירות הזה מפעיל את התנועות של אייפון, את נעילת האפליקציות ואת האי הדינמי. הוא לא קורא את מה שכתוב במסך.\n\nבמסך שייפתח: \"אפליקציות שהורדו\" ← זכוכית ← הפעלה.", "פתיחת הנגישות", {
                LockService.isEnabled(this)
            }, { LockGuard.startUnlocked(this, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }),
            Step(Icons.Rounded.Notifications, Kit.red, "התראות ומוזיקה", "לנקודות על הסמלים, למרכז ההתראות ולנגן באי הדינמי.", "אישור גישה להתראות", {
                NotifListener.isEnabled(this)
            }, { LockGuard.startUnlocked(this, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }),
            Step(Icons.Rounded.BatteryChargingFull, Kit.green, "שהכל ימשיך לעבוד", "כדי שהטלפון לא יעצור את התנועות ואת האי ברקע. זכוכית בנויה לחסוך בסוללה.", "אישור", {
                getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
            }, {
                runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
                    .onFailure { LockGuard.startUnlocked(this, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }),
            Step(Icons.Rounded.Bluetooth, Kit.blue, "AirPods (לא חובה)", "להצגת הסוללה של האוזניות בווידג'ט ובאי. צריך בלוטות' ומיקום – רק כדי לשמוע את האוזניות, שום מיקום לא נשמר.", "אישור", {
                AirPods.hasPermission(this)
            }, {
                requestPermissions(arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                ), 2)
            }),
            Step(Icons.Rounded.CheckCircle, Kit.green, "הכל מוכן", "את כל השאר – תיקיות נסתרות, נעילות, ניווט, ווידג'טים – אפשר לשנות ב\"הגדרות זכוכית\".", "סיום", { false }, {}),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishWelcome()
        })
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                KitText { Content() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        tick.intValue++
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        tick.intValue++
    }

    private fun finishWelcome() {
        App.instance.prefs.welcomeDone = true
        App.instance.prefs.onboarded = true
        finish()
    }

    @Composable
    private fun Content() {
        var index by androidx.compose.runtime.remember { mutableIntStateOf(0) }
        @Suppress("UNUSED_VARIABLE") val t = tick.intValue
        val dark = androidx.compose.foundation.isSystemInDarkTheme()
        val bg = if (dark) Color.Black else Color.White
        val fg = if (dark) Color.White else Color.Black
        val sub = if (dark) Color(0xFF98989F) else Color(0xFF6C6C70)
        Box(Modifier.fillMaxSize().background(bg).statusBarsPadding().navigationBarsPadding()) {
            AnimatedContent(index, transitionSpec = {
                (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
            }, label = "step") { i ->
                val s = steps[i]
                val done = s.done()
                Column(Modifier.fillMaxSize().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(
                        Modifier.size(96.dp).clip(RoundedCornerShape(26.dp))
                            .background(Brush.verticalGradient(listOf(s.color.copy(alpha = 0.85f), s.color))),
                        contentAlignment = Alignment.Center,
                    ) { Icon(s.icon, null, tint = Color.White, modifier = Modifier.size(54.dp)) }
                    Spacer(Modifier.height(28.dp))
                    Text(s.title, color = fg, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(14.dp))
                    Text(s.text, color = sub, fontSize = 17.sp, textAlign = TextAlign.Center, lineHeight = 24.sp)
                    if (done) {
                        Spacer(Modifier.height(18.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = Kit.green, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("מאושר", color = Kit.green, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // Page dots
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    steps.indices.forEach { i ->
                        Box(Modifier.size(7.dp).clip(CircleShape).background(if (i == index) fg else sub.copy(alpha = 0.4f)))
                    }
                }
                Spacer(Modifier.height(18.dp))
                val s = steps[index]
                val done = s.done()
                val last = index == steps.lastIndex
                Box(
                    Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(14.dp)).background(Kit.blue)
                        .tap {
                            when {
                                last -> finishWelcome()
                                index == 0 || done -> index++
                                else -> s.action()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { Text(if (done && !last) "המשך" else s.button, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(6.dp))
                if (index in 1 until steps.lastIndex && !done) {
                    Text("אחר כך", color = Kit.blue, fontSize = 17.sp, modifier = Modifier.tap { index++ }.padding(12.dp))
                } else Spacer(Modifier.height(44.dp))
            }
        }
    }
}
