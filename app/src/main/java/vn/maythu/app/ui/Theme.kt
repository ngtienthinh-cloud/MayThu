package vn.maythu.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Bảng màu: xanh than như mặt máy thu, vàng hổ phách của đèn mặt số, kim đỏ. */
object Pal {
    val Ink = Color(0xFF13202A)
    val Stage = Color(0xFF0E1820)
    val Panel = Color(0xFF1A2B37)
    val Panel2 = Color(0xFF223746)
    val Line = Color(0xFF2E4757)
    val LineSoft = Color(0xFF233946)
    val Text = Color(0xFFE8EEF1)
    val Muted = Color(0xFF91A7B4)
    val Amber = Color(0xFFF2B441)
    val AmberInk = Color(0xFF2A1D04)
    val AmberDim = Color(0x22F2B441)
    val Red = Color(0xFFEF5350)
    val Ok = Color(0xFF62C99C)
    val LogoBg = Color(0xFFE3EAED)
}

@Composable
fun MayThuTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Pal.Amber,
        onPrimary = Pal.AmberInk,
        primaryContainer = Pal.Amber,
        onPrimaryContainer = Pal.AmberInk,
        secondary = Pal.Amber,
        onSecondary = Pal.AmberInk,
        secondaryContainer = Pal.Panel2,
        onSecondaryContainer = Pal.Text,
        background = Pal.Ink,
        onBackground = Pal.Text,
        surface = Pal.Panel,
        onSurface = Pal.Text,
        surfaceVariant = Pal.Panel2,
        onSurfaceVariant = Pal.Muted,
        surfaceTint = Pal.Panel,
        outline = Pal.Line,
        outlineVariant = Pal.LineSoft,
        error = Pal.Red,
        onError = Color.White,
        surfaceBright = Pal.Panel2,
        surfaceDim = Pal.Ink,
        surfaceContainerLowest = Pal.Ink,
        surfaceContainerLow = Pal.Panel,
        surfaceContainer = Pal.Panel,
        surfaceContainerHigh = Pal.Panel2,
        surfaceContainerHighest = Pal.Panel2,
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
