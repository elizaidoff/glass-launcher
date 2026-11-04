package com.glass.launcher.phone

import android.os.Bundle
import android.telecom.Call
import android.telecom.CallAudioState
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Message
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.glass.launcher.messages.SmsStore
import com.glass.launcher.ui.KitText
import kotlinx.coroutines.delay

/** iPhone's call screen: name in large type, a 3×2 grid of round buttons, the red button below. */
class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                KitText { CallScreen(onDone = { finishAndRemoveTask() }) }
            }
        }
    }
}

@Composable
private fun CallScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val calls by CallManager.calls.collectAsState()
    val tick by CallManager.tick.collectAsState()
    val audio by CallManager.audio.collectAsState()
    val call = remember(calls, tick) { CallManager.current }
    LaunchedEffect(call) { if (call == null) { delay(900); onDone() } }
    var keypad by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val number = call?.let { CallManager.number(it) } ?: ""
    val name = if (number.isBlank()) "מספר חסוי" else SmsStore.name(ctx, number)
    val state = call?.state ?: Call.STATE_DISCONNECTED
    val connectTime = call?.details?.connectTimeMillis ?: 0L
    val status = when (state) {
        Call.STATE_RINGING -> "נייד"
        Call.STATE_DIALING, Call.STATE_CONNECTING -> "מתקשר…"
        Call.STATE_HOLDING -> "בהמתנה"
        Call.STATE_ACTIVE -> if (connectTime > 0) {
            val s = ((now - connectTime) / 1000).coerceAtLeast(0)
            "%d:%02d".format(s / 60, s % 60)
        } else "מחובר"
        Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> "השיחה הסתיימה"
        else -> ""
    }
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF3A4A5C), Color(0xFF1B2430), Color(0xFF0C1016))))
            .statusBarsPadding().navigationBarsPadding()
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (keypad && typed.isNotEmpty()) typed else name, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Normal, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            Text(status, color = Color.White.copy(alpha = 0.7f), fontSize = 18.sp)
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 50.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (state == Call.STATE_RINGING) {
                Row(horizontalArrangement = Arrangement.spacedBy(110.dp)) {
                    RoundAction(Icons.Rounded.CallEnd, "דחייה", Color(0xFFFF3B30)) { call?.let(CallManager::hangUp) }
                    RoundAction(Icons.Rounded.Call, "מענה", Color(0xFF34C759)) { call?.let(CallManager::answer) }
                }
                Spacer(Modifier.height(30.dp))
                Text("תזכורת · הודעה", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp,
                    modifier = Modifier.clickable {
                        call?.reject(true, "אני לא יכול לדבר עכשיו, אחזור אליך.")
                    })
            } else {
                if (keypad) {
                    Keypad(small = true) { d -> typed += d; call?.playDtmfTone(d[0]); call?.stopDtmfTone() }
                    Spacer(Modifier.height(16.dp))
                    Text("הסתרה", color = Color.White, fontSize = 17.sp, modifier = Modifier.clickable { keypad = false; typed = "" }.padding(8.dp))
                } else {
                    val muted = audio?.isMuted == true
                    val route = audio?.route ?: 0
                    val btAvailable = ((audio?.supportedRouteMask ?: 0) and CallAudioState.ROUTE_BLUETOOTH) != 0
                    Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                        ToggleAction(if (muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, "השתקה", muted) { CallManager.mute(!muted) }
                        ToggleAction(Icons.Rounded.Dialpad, "מקלדת", false) { keypad = true }
                        ToggleAction(Icons.Rounded.VolumeUp, "רמקול", route == CallAudioState.ROUTE_SPEAKER) { CallManager.speaker(route != CallAudioState.ROUTE_SPEAKER) }
                    }
                    Spacer(Modifier.height(22.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                        ToggleAction(Icons.Rounded.Pause, "המתנה", state == Call.STATE_HOLDING) {
                            call?.let { if (it.state == Call.STATE_HOLDING) it.unhold() else it.hold() }
                        }
                        ToggleAction(Icons.Rounded.Bluetooth, "אוזניות", route == CallAudioState.ROUTE_BLUETOOTH, enabled = btAvailable) { CallManager.bluetooth() }
                        ToggleAction(Icons.Rounded.Message, "הודעה", false) {
                            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:$number")).setPackage(ctx.packageName).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    }
                }
                Spacer(Modifier.height(40.dp))
                RoundAction(Icons.Rounded.CallEnd, null, Color(0xFFFF3B30)) { call?.let(CallManager::hangUp) ?: onDone() }
            }
        }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String?, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(76.dp).clip(CircleShape).background(color).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(36.dp))
        }
        if (label != null) { Spacer(Modifier.height(8.dp)); Text(label, color = Color.White, fontSize = 15.sp) }
    }
}

@Composable
private fun ToggleAction(icon: ImageVector, label: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(80.dp)) {
        Box(
            Modifier.size(76.dp).clip(CircleShape).background(if (on) Color.White else Color.White.copy(alpha = if (enabled) 0.18f else 0.07f))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = if (on) Color.Black else Color.White.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(32.dp)) }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}

/** The iPhone keypad (digits left-to-right even in Hebrew). */
@Composable
fun Keypad(small: Boolean = false, light: Boolean = false, onLong: (String) -> Unit = {}, onKey: (String) -> Unit) {
    val keyBg = if (light) Color(0xFFE5E5EA) else Color(0xFF8E8E93).copy(alpha = 0.32f)
    val keyFg = if (light) Color.Black else Color.White
    val rows = listOf(
        listOf("1" to "", "2" to "ABC", "3" to "DEF"),
        listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
        listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
        listOf("*" to "", "0" to "+", "#" to ""),
    )
    val size = if (small) 70.dp else 80.dp
    val view = androidx.compose.ui.platform.LocalView.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            rows.forEach { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    r.forEach { (d, sub) ->
                        Box(
                            Modifier.size(size).clip(CircleShape).background(keyBg)
                                .pointerInput(d) {
                                    detectTapGestures(
                                        onTap = { view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); onKey(d) },
                                        onLongPress = { view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); onLong(d) },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(d, color = keyFg, fontSize = if (small) 30.sp else 36.sp, fontWeight = FontWeight.Light)
                                if (sub.isNotEmpty()) Text(sub, color = keyFg, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
