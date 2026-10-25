package work.miaola.kf.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import work.miaola.kf.ThemeMode

private val Light = lightColorScheme(
    primary = Color(0xFF8D4355), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E0), onPrimaryContainer = Color(0xFF3B071A),
    secondary = Color(0xFF74565E), secondaryContainer = Color(0xFFFFD9E1),
    tertiary = Color(0xFF775A2F), tertiaryContainer = Color(0xFFFFDDB0),
    background = Color(0xFFFFF8F7), surface = Color(0xFFFFF8F7),
    surfaceContainer = Color(0xFFF8ECEE), surfaceContainerLow = Color(0xFFFFF0F2),
    surfaceContainerHigh = Color(0xFFF2E5E7), surfaceContainerHighest = Color(0xFFEBDDE0), surfaceVariant = Color(0xFFF2DDE2),
    onSurface = Color(0xFF24191D), onSurfaceVariant = Color(0xFF524348),
    outline = Color(0xFF847278), outlineVariant = Color(0xFFE4C9D0),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFFFB1C4), onPrimary = Color(0xFF541D2D),
    primaryContainer = Color(0xFF713042), onPrimaryContainer = Color(0xFFFFD9E0),
    secondary = Color(0xFFE3BDC5), secondaryContainer = Color(0xFF593E46),
    tertiary = Color(0xFFE7C18D), tertiaryContainer = Color(0xFF5D421C),
    background = Color(0xFF191114), surface = Color(0xFF191114),
    surfaceContainer = Color(0xFF281D21), surfaceContainerLow = Color(0xFF21181C),
    surfaceContainerHigh = Color(0xFF34282C), surfaceContainerHighest = Color(0xFF3F3236), surfaceVariant = Color(0xFF524348),
    onSurface = Color(0xFFF4DDE2), onSurfaceVariant = Color(0xFFD8C1C7),
    outline = Color(0xFFA18A91), outlineVariant = Color(0xFF524348),
)

@Composable
fun KfTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}

fun kfBackground(dark: Boolean): Color = if (dark) Color(0xFF191114) else Color(0xFFFFF8F7)
