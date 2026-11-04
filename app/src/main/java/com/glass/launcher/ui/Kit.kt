package com.glass.launcher.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.glass.launcher.App

/**
 * The shared design kit: one font, one set of colours, one switch, one icon tile.
 * Every screen in the app is built from these so everything speaks the same language.
 */
object Kit {
    val blue = Color(0xFF0A84FF)
    val green = Color(0xFF34C759)
    val red = Color(0xFFFF3B30)
    val orange = Color(0xFFFF9500)
    val yellow = Color(0xFFFFCC00)
    val purple = Color(0xFFAF52DE)
    val indigo = Color(0xFF5856D6)
    val teal = Color(0xFF30B0C7)
    val pink = Color(0xFFFF2D55)
    val gray = Color(0xFF8E8E93)

    /** Heebo (open font licence), bundled at build time. Falls back to the system font if missing. */
    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    val font: FontFamily by lazy {
        val ctx = App.instance
        val id = ctx.resources.getIdentifier("heebo", "font", ctx.packageName)
        if (id == 0) FontFamily.Default else FontFamily(
            listOf(300, 400, 500, 600, 700).map { w ->
                Font(id, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
            }
        )
    }
}

/** Wrap a screen so all text in it uses the kit font. */
@Composable
fun KitText(content: @Composable () -> Unit) {
    ProvideTextStyle(TextStyle(fontFamily = Kit.font), content)
}

/** iPhone-style switch: green track, white knob, springy. */
@Composable
fun IosSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val track by animateColorAsState(if (checked) Kit.green else Color(0x3378788C), label = "track")
    val x by animateDpAsState(if (checked) 20.dp else 0.dp, spring(dampingRatio = 0.68f, stiffness = 600f), label = "knob")
    Box(
        modifier
            .size(51.dp, 31.dp)
            .clip(RoundedCornerShape(50))
            .background(track)
            .tap { view.tick(); onChange(!checked) }
            .padding(2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = if (rtl) x else x)
                .size(27.dp)
                .shadow(2.dp, CircleShape)
                .background(Color.White, CircleShape)
        )
    }
}

/** Coloured rounded tile with a white symbol, like the icons in iPhone Settings. */
@Composable
fun IconTile(icon: ImageVector, color: Color, size: androidx.compose.ui.unit.Dp = 29.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(7.dp)).background(color),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.66f)) }
}
