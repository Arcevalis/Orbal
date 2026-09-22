package me.fss.orbal.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode(val label: String) {
    SYSTEM("System Default"),
    LIGHT("Light"),
    DARK("Dark"),
    AMOLED("AMOLED Black"),
    CATPPUCCIN("Catppuccin"),
    DRACULA("Dracula"),
    PTYXIS("Ptyxis Terminal"),
}

data class AccentColor(
    val key: String,
    val label: String,
    val seed: Color,
)

val accentColors = listOf(
    AccentColor("dynamic", "Device Default", Color(0xFF6750A4)),
    AccentColor("blue", "Blue", Color(0xFF1976D2)),
    AccentColor("green", "Green", Color(0xFF388E3C)),
    AccentColor("purple", "Purple", Color(0xFF7B1FA2)),
    AccentColor("orange", "Orange", Color(0xFFE65100)),
    AccentColor("red", "Red", Color(0xFFD32F2F)),
    AccentColor("teal", "Teal", Color(0xFF00796B)),
    AccentColor("pink", "Pink", Color(0xFFC2185B)),
    AccentColor("amber", "Amber", Color(0xFFFFA000)),
)

private fun buildColorScheme(seed: Color, isDark: Boolean): ColorScheme {
    val primary = seed
    val onPrimary = if (isDark) Color.Black else Color.White
    val primaryContainer = if (isDark) seed.copy(alpha = 0.3f) else seed.copy(alpha = 0.15f)
    val onPrimaryContainer = if (isDark) seed.copy(alpha = 0.9f) else seed.copy(alpha = 0.85f)

    return if (isDark) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
        )
    }
}

@Composable
fun OrbalTheme(
    themeMode: ThemeMode = ThemeMode.AMOLED,
    accentColorKey: String = "blue",
    catppuccinFlavorKey: String = "mocha",
    catppuccinAccentKey: String = "mauve",
    draculaAccentKey: String = "purple",
    ptyxisPaletteKey: String = "cobalt_neon",
    monochromeAccents: Boolean = false,
    appFontKey: String = "quicksand",
    fontScale: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED, ThemeMode.CATPPUCCIN, ThemeMode.DRACULA, ThemeMode.PTYXIS -> true
    }

    val accent = accentColors.find { it.key == accentColorKey }

    val colorScheme = when {
        themeMode == ThemeMode.CATPPUCCIN -> buildCatppuccinColorScheme(
            flavor = catppuccinFlavorFromKey(catppuccinFlavorKey),
            accent = catppuccinAccentFromKey(catppuccinAccentKey),
            monochrome = monochromeAccents,
        )
        themeMode == ThemeMode.DRACULA -> buildDraculaColorScheme(
            accent = draculaAccentFromKey(draculaAccentKey),
            dark = true,
            monochrome = monochromeAccents,
        )
        themeMode == ThemeMode.PTYXIS -> buildPtyxisColorScheme(ptyxisPaletteFromKey(ptyxisPaletteKey))
        // AMOLED with custom accent — keep text/icons white for readability on black
        themeMode == ThemeMode.AMOLED && accent != null && accent.key != "dynamic" -> {
            buildColorScheme(accent.seed, true).copy(
                background = Color.Black,
                surface = Color.Black,
                surfaceVariant = Color(0xFF1A1A1A),
                surfaceContainer = Color(0xFF0D0D0D),
                surfaceContainerHigh = Color(0xFF1A1A1A),
                surfaceContainerHighest = Color(0xFF222222),
                onPrimary = Color.White,
                onPrimaryContainer = Color.White,
                onSurface = Color.White,
                onSurfaceVariant = Color.White,
            )
        }
        // AMOLED with device dynamic — force white text/icons for readability
        themeMode == ThemeMode.AMOLED -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicDarkColorScheme(LocalContext.current).copy(
                    background = Color.Black,
                    surface = Color.Black,
                    surfaceVariant = Color(0xFF1A1A1A),
                    onPrimary = Color.White,
                    onPrimaryContainer = Color.White,
                    onSurface = Color.White,
                    onSurfaceVariant = Color.White,
                )
            } else {
                darkColorScheme(
                    background = Color.Black,
                    surface = Color.Black,
                    surfaceVariant = Color(0xFF1A1A1A),
                    onPrimary = Color.White,
                    onSurface = Color.White,
                    onSurfaceVariant = Color.White,
                )
            }
        }
        // Custom accent colour
        accent != null && accent.key != "dynamic" -> {
            buildColorScheme(accent.seed, isDark)
        }
        // Device dynamic colour (Android 12+)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        // Fallback
        isDark -> darkColorScheme()
        else -> lightColorScheme()
    }

    // Apply the user-selected font everywhere: the Typography roles cover Texts that pass an
    // explicit typography style; the root ProvideTextStyle covers Texts without one.
    val selectedFontFamily = appFontFromKey(appFontKey).family
    val typography = buildAppTypography(selectedFontFamily, fontScale)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
    ) {
        ProvideTextStyle(LocalTextStyle.current.copy(fontFamily = selectedFontFamily)) {
            content()
        }
    }
}
