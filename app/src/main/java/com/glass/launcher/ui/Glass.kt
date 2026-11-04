package com.glass.launcher.ui

import android.app.Activity
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.glass.launcher.App
import com.glass.launcher.data.IconLoader

object SquircleShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(IconLoader.squirclePath(size.width, size.height).asComposePath())
}

val GlassCorner = RoundedCornerShape(28.dp)

/** Translucent "liquid glass" surface: soft fill, bright top rim, fading edge. */
fun Modifier.glass(shape: Shape = GlassCorner, strength0: Float = 1f): Modifier = glassImpl(shape, strength0 * App.instance.prefs.glassStrength)

private fun Modifier.glassImpl(shape: Shape, strength: Float): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.26f * strength),
                Color.White.copy(alpha = 0.12f * strength),
            )
        )
    )
    .border(
        width = 0.8.dp,
        brush = Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.65f), Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.28f))
        ),
        shape = shape,
    )

/** Gentle iOS-like shrink while pressed. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (pressed) 0.9f else 1f,
        spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "press",
    )
    return this.scale(s)
}

/** Click without the Android ripple. */
@Composable
fun Modifier.tap(onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = src, indication = null, onClick = onClick)
}

val LabelStyle = TextStyle(
    color = Color.White,
    fontSize = 12.sp,
    fontWeight = FontWeight.Medium,
    shadow = Shadow(Color.Black.copy(alpha = 0.45f), blurRadius = 6f),
)

fun View.tick() {
    if (!App.instance.prefs.haptics) return
    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}

fun View.thud() {
    if (!App.instance.prefs.haptics) return
    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
}

/** Blur everything behind the activity window (the wallpaper). Works on Android 12+ when the device allows it. */
fun Activity.setBackdropBlur(radiusPx: Int) {
    if (Build.VERSION.SDK_INT < 31) return
    runCatching {
        if (windowManager.isCrossWindowBlurEnabled) window.setBackgroundBlurRadius(radiusPx)
    }
}

data class MenuItem(val title: String, val danger: Boolean = false, val onClick: () -> Unit)

/** iOS-style context menu shown as a glass popup. */
@Composable
fun GlassMenu(items: List<MenuItem>, onDismiss: () -> Unit) {
    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .springIn(0.85f)
                .width(250.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xF2F2F2F7))
        ) {
            items.forEachIndexed { i, item ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .tap { item.onClick(); onDismiss() }
                        .padding(horizontal = 18.dp, vertical = 13.dp)
                ) {
                    Text(item.title, color = if (item.danger) Color(0xFFFF3B30) else Color.Black, fontSize = 16.sp)
                }
                if (i < items.lastIndex) {
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x33000000)))
                }
            }
        }
    }
}

/** Small glass dialog with a text field (and an optional "hidden" switch). */
@Composable
fun TextPrompt(
    title: String,
    hint: String,
    hiddenOption: Boolean = false,
    startHidden: Boolean = false,
    onDone: (String, Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var hidden by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(startHidden) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier.width(300.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xF2F2F2F7)).padding(18.dp)
        ) {
            Text(title, color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White).padding(12.dp)) {
                if (text.isEmpty()) Text(hint, color = Color(0xFF8E8E93), fontSize = 16.sp)
                androidx.compose.foundation.text.BasicTextField(
                    text, { text = it }, singleLine = true,
                    textStyle = TextStyle(color = Color.Black, fontSize = 16.sp), modifier = Modifier.fillMaxWidth(),
                )
            }
            if (hiddenOption) {
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("תיקייה נסתרת", color = Color.Black, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(hidden, { hidden = it })
                }
            }
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            ) {
                Text("ביטול", color = Color(0xFF0A84FF), fontSize = 17.sp, modifier = Modifier.tap(onCancel).padding(6.dp))
                Text("אישור", color = Color(0xFF0A84FF), fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.tap { onDone(text, hidden) }.padding(6.dp))
            }
        }
    }
}

/** iOS-like entrance: a soft spring from slightly smaller (and offset) to full size. */
@Composable
fun Modifier.springIn(fromScale: Float = 0.92f, fromY: androidx.compose.ui.unit.Dp = 0.dp): Modifier {
    val a = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (App.instance.prefs.smoothMode) a.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 420f))
        else a.animateTo(1f, androidx.compose.animation.core.tween(140))
    }
    val dy = with(androidx.compose.ui.platform.LocalDensity.current) { fromY.toPx() }
    return this.then(Modifier.graphicsLayer {
        val v = a.value
        alpha = v.coerceIn(0f, 1f)
        val sc = fromScale + (1f - fromScale) * v
        scaleX = sc; scaleY = sc
        translationY = (1f - v) * dy
    })
}
