package com.example.meshtest.ui.theme
 
import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = ResQBlue,
    onPrimary = ResQTextPrimary,
    primaryContainer = ResQBlueBg,
    onPrimaryContainer = ResQBlueLight,
    secondary = ResQBlueLight,
    onSecondary = ResQDarkBackground,
    secondaryContainer = ResQDarkCard,
    onSecondaryContainer = ResQTextPrimary,
    tertiary = ResQGreen,
    onTertiary = ResQDarkBackground,
    tertiaryContainer = ResQGreenBg,
    onTertiaryContainer = ResQGreenBright,
    error = ResQRedBright,
    onError = ResQTextPrimary,
    errorContainer = ResQRedBg,
    onErrorContainer = ResQRedBright,
    background = ResQDarkBackground,
    onBackground = ResQTextPrimary,
    surface = ResQDarkSurface,
    onSurface = ResQTextPrimary,
    surfaceVariant = ResQDarkCard,
    onSurfaceVariant = ResQTextSecondary,
    outline = ResQBorder,
    outlineVariant = ResQBorderSubtle
)

@Composable
fun MeshTestTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}