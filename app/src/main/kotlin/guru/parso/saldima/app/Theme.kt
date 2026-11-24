// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Indigo = Color(0xFF4F46E5)
private val IndigoDark = Color(0xFF7C86FF)
private val Teal = Color(0xFF0D9488)
private val Amber = Color(0xFFD97706)
private val Ink = Color(0xFF17171D)

private val LightScheme = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    secondary = Teal,
    tertiary = Amber,
    background = Color(0xFFF6F6FB),
    surface = Color.White,
    onSurface = Ink,
)

private val DarkScheme = darkColorScheme(
    primary = IndigoDark,
    onPrimary = Color(0xFF0E0F14),
    secondary = Color(0xFF34D399),
    tertiary = Color(0xFFF59E0B),
    background = Color(0xFF0E0F14),
    surface = Color(0xFF171923),
    onSurface = Color(0xFFF2F2F7),
)

/** Same indigo accent family as the web and iOS tracks. */
@Composable
fun SaldimaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
