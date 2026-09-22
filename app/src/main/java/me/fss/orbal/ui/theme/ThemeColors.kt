package me.fss.orbal.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

// ── Catppuccin ──────────────────────────────────────────────────────────────

enum class CatppuccinFlavor(val displayName: String, val key: String) {
  LATTE("Latte", "latte"),
  FRAPPE("Frappé", "frappe"),
  MACCHIATO("Macchiato", "macchiato"),
  MOCHA("Mocha", "mocha"),
}

enum class CatppuccinAccent(val displayName: String, val key: String) {
  ROSEWATER("Rosewater", "rosewater"),
  FLAMINGO("Flamingo", "flamingo"),
  PINK("Pink", "pink"),
  MAUVE("Mauve", "mauve"),
  RED("Red", "red"),
  MAROON("Maroon", "maroon"),
  PEACH("Peach", "peach"),
  YELLOW("Yellow", "yellow"),
  GREEN("Green", "green"),
  TEAL("Teal", "teal"),
  SKY("Sky", "sky"),
  SAPPHIRE("Sapphire", "sapphire"),
  BLUE("Blue", "blue"),
  LAVENDER("Lavender", "lavender"),
}

// Official Catppuccin palette hex values for all 4 flavors × 14 accent colors.
private val CATPPUCCIN_PALETTE: Map<CatppuccinFlavor, Map<CatppuccinAccent, Color>> = mapOf(
  CatppuccinFlavor.LATTE to mapOf(
    CatppuccinAccent.ROSEWATER to Color(0xFFdc8a78),
    CatppuccinAccent.FLAMINGO  to Color(0xFFdd7878),
    CatppuccinAccent.PINK      to Color(0xFFea76cb),
    CatppuccinAccent.MAUVE     to Color(0xFF8839ef),
    CatppuccinAccent.RED       to Color(0xFFd20f39),
    CatppuccinAccent.MAROON    to Color(0xFFe64553),
    CatppuccinAccent.PEACH     to Color(0xFFfe640b),
    CatppuccinAccent.YELLOW    to Color(0xFFdf8e1d),
    CatppuccinAccent.GREEN     to Color(0xFF40a02b),
    CatppuccinAccent.TEAL      to Color(0xFF179299),
    CatppuccinAccent.SKY       to Color(0xFF04a5e5),
    CatppuccinAccent.SAPPHIRE  to Color(0xFF209fb5),
    CatppuccinAccent.BLUE      to Color(0xFF1e66f5),
    CatppuccinAccent.LAVENDER  to Color(0xFF7287fd),
  ),
  CatppuccinFlavor.FRAPPE to mapOf(
    CatppuccinAccent.ROSEWATER to Color(0xFFf2d5cf),
    CatppuccinAccent.FLAMINGO  to Color(0xFFeebebe),
    CatppuccinAccent.PINK      to Color(0xFFf4b8e4),
    CatppuccinAccent.MAUVE     to Color(0xFFca9ee6),
    CatppuccinAccent.RED       to Color(0xFFe78284),
    CatppuccinAccent.MAROON    to Color(0xFFea999c),
    CatppuccinAccent.PEACH     to Color(0xFFef9f76),
    CatppuccinAccent.YELLOW    to Color(0xFFe5c890),
    CatppuccinAccent.GREEN     to Color(0xFFa6d189),
    CatppuccinAccent.TEAL      to Color(0xFF81c8be),
    CatppuccinAccent.SKY       to Color(0xFF99d1db),
    CatppuccinAccent.SAPPHIRE  to Color(0xFF85c1dc),
    CatppuccinAccent.BLUE      to Color(0xFF8caaee),
    CatppuccinAccent.LAVENDER  to Color(0xFFbabbf1),
  ),
  CatppuccinFlavor.MACCHIATO to mapOf(
    CatppuccinAccent.ROSEWATER to Color(0xFFf4dbd6),
    CatppuccinAccent.FLAMINGO  to Color(0xFFf0c6c6),
    CatppuccinAccent.PINK      to Color(0xFFf5bde6),
    CatppuccinAccent.MAUVE     to Color(0xFFc6a0f6),
    CatppuccinAccent.RED       to Color(0xFFed8796),
    CatppuccinAccent.MAROON    to Color(0xFFee99a0),
    CatppuccinAccent.PEACH     to Color(0xFFf5a97f),
    CatppuccinAccent.YELLOW    to Color(0xFFeed49f),
    CatppuccinAccent.GREEN     to Color(0xFFa6da95),
    CatppuccinAccent.TEAL      to Color(0xFF8bd5ca),
    CatppuccinAccent.SKY       to Color(0xFF91d7e3),
    CatppuccinAccent.SAPPHIRE  to Color(0xFF7dc4e4),
    CatppuccinAccent.BLUE      to Color(0xFF8aadf4),
    CatppuccinAccent.LAVENDER  to Color(0xFFb7bdf8),
  ),
  CatppuccinFlavor.MOCHA to mapOf(
    CatppuccinAccent.ROSEWATER to Color(0xFFf5e0dc),
    CatppuccinAccent.FLAMINGO  to Color(0xFFf2cdcd),
    CatppuccinAccent.PINK      to Color(0xFFf5c2e7),
    CatppuccinAccent.MAUVE     to Color(0xFFcba6f7),
    CatppuccinAccent.RED       to Color(0xFFf38ba8),
    CatppuccinAccent.MAROON    to Color(0xFFeba0ac),
    CatppuccinAccent.PEACH     to Color(0xFFfab387),
    CatppuccinAccent.YELLOW    to Color(0xFFf9e2af),
    CatppuccinAccent.GREEN     to Color(0xFFa6e3a1),
    CatppuccinAccent.TEAL      to Color(0xFF94e2d5),
    CatppuccinAccent.SKY       to Color(0xFF89dceb),
    CatppuccinAccent.SAPPHIRE  to Color(0xFF74c7ec),
    CatppuccinAccent.BLUE      to Color(0xFF89b4fa),
    CatppuccinAccent.LAVENDER  to Color(0xFFb4befe),
  ),
)

// Base/neutral colors per flavor — surfaces, overlays, text.
data class CatppuccinBaseColors(
  val text: Color,
  val subtext1: Color,
  val overlay1: Color,
  val overlay0: Color,
  val surface2: Color,
  val surface1: Color,
  val surface0: Color,
  val base: Color,
  val mantle: Color,
  val crust: Color,
)

private val CATPPUCCIN_BASE: Map<CatppuccinFlavor, CatppuccinBaseColors> = mapOf(
  CatppuccinFlavor.LATTE to CatppuccinBaseColors(
    text     = Color(0xFF4c4f69),
    subtext1 = Color(0xFF5c5f77),
    overlay1 = Color(0xFF8c8fa1),
    overlay0 = Color(0xFF9ca0b0),
    surface2 = Color(0xFFacb0be),
    surface1 = Color(0xFFbcc0cc),
    surface0 = Color(0xFFccd0da),
    base     = Color(0xFFeff1f5),
    mantle   = Color(0xFFe6e9ef),
    crust    = Color(0xFFdce0e8),
  ),
  CatppuccinFlavor.FRAPPE to CatppuccinBaseColors(
    text     = Color(0xFFc6d0f5),
    subtext1 = Color(0xFFb5bfe2),
    overlay1 = Color(0xFF838ba7),
    overlay0 = Color(0xFF737994),
    surface2 = Color(0xFF626880),
    surface1 = Color(0xFF51576d),
    surface0 = Color(0xFF414559),
    base     = Color(0xFF303446),
    mantle   = Color(0xFF292c3c),
    crust    = Color(0xFF232634),
  ),
  CatppuccinFlavor.MACCHIATO to CatppuccinBaseColors(
    text     = Color(0xFFcad3f5),
    subtext1 = Color(0xFFb8c0e0),
    overlay1 = Color(0xFF8087a2),
    overlay0 = Color(0xFF6e738d),
    surface2 = Color(0xFF5b6078),
    surface1 = Color(0xFF494d64),
    surface0 = Color(0xFF363a4f),
    base     = Color(0xFF24273a),
    mantle   = Color(0xFF1e2030),
    crust    = Color(0xFF181926),
  ),
  CatppuccinFlavor.MOCHA to CatppuccinBaseColors(
    text     = Color(0xFFcdd6f4),
    subtext1 = Color(0xFFbac2de),
    overlay1 = Color(0xFF7f849c),
    overlay0 = Color(0xFF6c7086),
    surface2 = Color(0xFF585b70),
    surface1 = Color(0xFF45475a),
    surface0 = Color(0xFF313244),
    base     = Color(0xFF1e1e2e),
    mantle   = Color(0xFF181825),
    crust    = Color(0xFF11111b),
  ),
)

// Hue-adjacent secondary and tertiary accent pairings on the Catppuccin color wheel.
private val CATPPUCCIN_SECONDARY_ACCENT: Map<CatppuccinAccent, CatppuccinAccent> = mapOf(
  CatppuccinAccent.ROSEWATER to CatppuccinAccent.FLAMINGO,
  CatppuccinAccent.FLAMINGO  to CatppuccinAccent.PINK,
  CatppuccinAccent.PINK      to CatppuccinAccent.MAUVE,
  CatppuccinAccent.MAUVE     to CatppuccinAccent.LAVENDER,
  CatppuccinAccent.RED       to CatppuccinAccent.MAROON,
  CatppuccinAccent.MAROON    to CatppuccinAccent.RED,
  CatppuccinAccent.PEACH     to CatppuccinAccent.YELLOW,
  CatppuccinAccent.YELLOW    to CatppuccinAccent.PEACH,
  CatppuccinAccent.GREEN     to CatppuccinAccent.TEAL,
  CatppuccinAccent.TEAL      to CatppuccinAccent.SKY,
  CatppuccinAccent.SKY       to CatppuccinAccent.SAPPHIRE,
  CatppuccinAccent.SAPPHIRE  to CatppuccinAccent.BLUE,
  CatppuccinAccent.BLUE      to CatppuccinAccent.LAVENDER,
  CatppuccinAccent.LAVENDER  to CatppuccinAccent.BLUE,
)

private val CATPPUCCIN_TERTIARY_ACCENT: Map<CatppuccinAccent, CatppuccinAccent> = mapOf(
  CatppuccinAccent.ROSEWATER to CatppuccinAccent.PINK,
  CatppuccinAccent.FLAMINGO  to CatppuccinAccent.MAUVE,
  CatppuccinAccent.PINK      to CatppuccinAccent.LAVENDER,
  CatppuccinAccent.MAUVE     to CatppuccinAccent.PINK,
  CatppuccinAccent.RED       to CatppuccinAccent.PEACH,
  CatppuccinAccent.MAROON    to CatppuccinAccent.PEACH,
  CatppuccinAccent.PEACH     to CatppuccinAccent.GREEN,
  CatppuccinAccent.YELLOW    to CatppuccinAccent.GREEN,
  CatppuccinAccent.GREEN     to CatppuccinAccent.SKY,
  CatppuccinAccent.TEAL      to CatppuccinAccent.GREEN,
  CatppuccinAccent.SKY       to CatppuccinAccent.TEAL,
  CatppuccinAccent.SAPPHIRE  to CatppuccinAccent.SKY,
  CatppuccinAccent.BLUE      to CatppuccinAccent.SAPPHIRE,
  CatppuccinAccent.LAVENDER  to CatppuccinAccent.MAUVE,
)

fun catppuccinAccentColor(flavor: CatppuccinFlavor, accent: CatppuccinAccent): Color =
  CATPPUCCIN_PALETTE[flavor]?.get(accent) ?: Color(0xFFcba6f7)

fun catppuccinBaseColors(flavor: CatppuccinFlavor): CatppuccinBaseColors =
  CATPPUCCIN_BASE[flavor] ?: CATPPUCCIN_BASE.getValue(CatppuccinFlavor.MOCHA)

fun catppuccinSecondaryAccent(accent: CatppuccinAccent): CatppuccinAccent =
  CATPPUCCIN_SECONDARY_ACCENT[accent] ?: CatppuccinAccent.LAVENDER

fun catppuccinTertiaryAccent(accent: CatppuccinAccent): CatppuccinAccent =
  CATPPUCCIN_TERTIARY_ACCENT[accent] ?: CatppuccinAccent.PINK

fun catppuccinFlavorFromKey(key: String): CatppuccinFlavor =
  CatppuccinFlavor.entries.firstOrNull { it.key == key } ?: CatppuccinFlavor.MOCHA

fun catppuccinAccentFromKey(key: String): CatppuccinAccent =
  CatppuccinAccent.entries.firstOrNull { it.key == key } ?: CatppuccinAccent.MAUVE

// Builds a complete Material 3 ColorScheme entirely from the Catppuccin palette.
// Dark mode uses the flavor's own surfaces; Latte is the only light flavor.
// Primary / secondary / tertiary are three distinct Catppuccin accent colors chosen by hue
// proximity so each role is visually differentiated, unless [monochrome] collapses them.
fun buildCatppuccinColorScheme(
  flavor: CatppuccinFlavor,
  accent: CatppuccinAccent,
  monochrome: Boolean,
): ColorScheme {
  val dark = flavor != CatppuccinFlavor.LATTE
  val inverseFlavor = if (dark) CatppuccinFlavor.LATTE else CatppuccinFlavor.MOCHA

  val b = catppuccinBaseColors(flavor)
  val bi = catppuccinBaseColors(inverseFlavor)

  val primary   = catppuccinAccentColor(flavor, accent)
  val secondary = if (monochrome) primary
                  else catppuccinAccentColor(flavor, catppuccinSecondaryAccent(accent))
  val tertiary  = if (monochrome) primary
                  else catppuccinAccentColor(flavor, catppuccinTertiaryAccent(accent))

  fun onColor(c: Color) = if (c.luminance() < 0.35f) Color.White else b.base
  fun container(c: Color) = lerp(b.base, c, 0.22f)
  fun onContainer(c: Color) = lerp(b.text, c, 0.20f)

  val errorColor = catppuccinAccentColor(flavor, CatppuccinAccent.RED)

  return if (dark) darkColorScheme(
    primary                = primary,
    onPrimary              = onColor(primary),
    primaryContainer       = container(primary),
    onPrimaryContainer     = onContainer(primary),
    secondary              = secondary,
    onSecondary            = onColor(secondary),
    secondaryContainer     = container(secondary),
    onSecondaryContainer   = onContainer(secondary),
    tertiary               = tertiary,
    onTertiary             = onColor(tertiary),
    tertiaryContainer      = container(tertiary),
    onTertiaryContainer    = onContainer(tertiary),
    error                  = errorColor,
    onError                = onColor(errorColor),
    errorContainer         = container(errorColor),
    onErrorContainer       = onContainer(errorColor),
    background             = b.base,
    onBackground           = b.text,
    surface                = b.base,
    onSurface              = b.text,
    surfaceVariant         = b.surface0,
    onSurfaceVariant       = b.subtext1,
    outline                = b.overlay1,
    outlineVariant         = b.surface2,
    scrim                  = b.crust,
    inverseSurface         = bi.base,
    inverseOnSurface       = bi.text,
    inversePrimary         = catppuccinAccentColor(inverseFlavor, accent),
    surfaceDim             = b.mantle,
    surfaceBright          = b.surface1,
    surfaceContainerLowest = b.crust,
    surfaceContainerLow    = b.mantle,
    surfaceContainer       = b.surface0,
    surfaceContainerHigh   = b.surface1,
    surfaceContainerHighest= b.surface2,
  ) else lightColorScheme(
    primary                = primary,
    onPrimary              = onColor(primary),
    primaryContainer       = container(primary),
    onPrimaryContainer     = onContainer(primary),
    secondary              = secondary,
    onSecondary            = onColor(secondary),
    secondaryContainer     = container(secondary),
    onSecondaryContainer   = onContainer(secondary),
    tertiary               = tertiary,
    onTertiary             = onColor(tertiary),
    tertiaryContainer      = container(tertiary),
    onTertiaryContainer    = onContainer(tertiary),
    error                  = errorColor,
    onError                = onColor(errorColor),
    errorContainer         = container(errorColor),
    onErrorContainer       = onContainer(errorColor),
    background             = b.base,
    onBackground           = b.text,
    surface                = b.base,
    onSurface              = b.text,
    surfaceVariant         = b.surface0,
    onSurfaceVariant       = b.subtext1,
    outline                = b.overlay1,
    outlineVariant         = b.surface2,
    scrim                  = b.crust,
    inverseSurface         = bi.base,
    inverseOnSurface       = bi.text,
    inversePrimary         = catppuccinAccentColor(inverseFlavor, accent),
    surfaceDim             = b.mantle,
    surfaceBright          = b.surface1,
    surfaceContainerLowest = b.crust,
    surfaceContainerLow    = b.mantle,
    surfaceContainer       = b.surface0,
    surfaceContainerHigh   = b.surface1,
    surfaceContainerHighest= b.surface2,
  )
}

// ── Dracula ────────────────────────────────────────────────────────────────

enum class DraculaAccent(val displayName: String, val key: String) {
  PURPLE("Purple", "purple"),
  PINK("Pink", "pink"),
  CYAN("Cyan", "cyan"),
  GREEN("Green", "green"),
  ORANGE("Orange", "orange"),
  RED("Red", "red"),
  YELLOW("Yellow", "yellow"),
}

data class DraculaBaseColors(
  val text: Color,
  val subtext1: Color,
  val overlay1: Color,
  val overlay0: Color,
  val surface2: Color,
  val surface1: Color,
  val surface0: Color,
  val base: Color,
  val mantle: Color,
  val crust: Color,
)

// Official Dracula dark accent colors; darker adaptations for legibility on a light background.
private val DRACULA_DARK_ACCENT: Map<DraculaAccent, Color> = mapOf(
  DraculaAccent.PURPLE to Color(0xFFbd93f9),
  DraculaAccent.PINK   to Color(0xFFff79c6),
  DraculaAccent.CYAN   to Color(0xFF8be9fd),
  DraculaAccent.GREEN  to Color(0xFF50fa7b),
  DraculaAccent.ORANGE to Color(0xFFffb86c),
  DraculaAccent.RED    to Color(0xFFff5555),
  DraculaAccent.YELLOW to Color(0xFFf1fa8c),
)

private val DRACULA_LIGHT_ACCENT: Map<DraculaAccent, Color> = mapOf(
  DraculaAccent.PURPLE to Color(0xFF7b4fef),
  DraculaAccent.PINK   to Color(0xFFd9337a),
  DraculaAccent.CYAN   to Color(0xFF0090c0),
  DraculaAccent.GREEN  to Color(0xFF1c7a3d),
  DraculaAccent.ORANGE to Color(0xFFc55a00),
  DraculaAccent.RED    to Color(0xFFcc2222),
  DraculaAccent.YELLOW to Color(0xFFa07800),
)

private val DRACULA_DARK_BASE = DraculaBaseColors(
  text     = Color(0xFFf8f8f2),
  subtext1 = Color(0xFFb8bac8),
  overlay1 = Color(0xFF7b8db0),
  overlay0 = Color(0xFF6272a4),
  surface2 = Color(0xFF565761),
  surface1 = Color(0xFF44475a),
  surface0 = Color(0xFF383a48),
  base     = Color(0xFF282a36),
  mantle   = Color(0xFF21222c),
  crust    = Color(0xFF191a21),
)

private val DRACULA_LIGHT_BASE = DraculaBaseColors(
  text     = Color(0xFF282a36),
  subtext1 = Color(0xFF414559),
  overlay1 = Color(0xFF6272a4),
  overlay0 = Color(0xFF7b8db0),
  surface2 = Color(0xFFd6d8e7),
  surface1 = Color(0xFFe2e3ed),
  surface0 = Color(0xFFecedfa),
  base     = Color(0xFFf8f8f2),
  mantle   = Color(0xFFf0f0f9),
  crust    = Color(0xFFe8e8f5),
)

// Hue-adjacent secondary and tertiary accent pairings.
private val DRACULA_SECONDARY_ACCENT: Map<DraculaAccent, DraculaAccent> = mapOf(
  DraculaAccent.PURPLE to DraculaAccent.PINK,
  DraculaAccent.PINK   to DraculaAccent.PURPLE,
  DraculaAccent.CYAN   to DraculaAccent.GREEN,
  DraculaAccent.GREEN  to DraculaAccent.CYAN,
  DraculaAccent.ORANGE to DraculaAccent.YELLOW,
  DraculaAccent.RED    to DraculaAccent.ORANGE,
  DraculaAccent.YELLOW to DraculaAccent.ORANGE,
)

private val DRACULA_TERTIARY_ACCENT: Map<DraculaAccent, DraculaAccent> = mapOf(
  DraculaAccent.PURPLE to DraculaAccent.CYAN,
  DraculaAccent.PINK   to DraculaAccent.ORANGE,
  DraculaAccent.CYAN   to DraculaAccent.PURPLE,
  DraculaAccent.GREEN  to DraculaAccent.YELLOW,
  DraculaAccent.ORANGE to DraculaAccent.RED,
  DraculaAccent.RED    to DraculaAccent.PINK,
  DraculaAccent.YELLOW to DraculaAccent.GREEN,
)

fun draculaAccentColor(dark: Boolean, accent: DraculaAccent): Color =
  (if (dark) DRACULA_DARK_ACCENT else DRACULA_LIGHT_ACCENT)[accent] ?: Color(0xFFbd93f9)

fun draculaBaseColors(dark: Boolean): DraculaBaseColors =
  if (dark) DRACULA_DARK_BASE else DRACULA_LIGHT_BASE

fun draculaSecondaryAccent(accent: DraculaAccent): DraculaAccent =
  DRACULA_SECONDARY_ACCENT[accent] ?: DraculaAccent.PINK

fun draculaTertiaryAccent(accent: DraculaAccent): DraculaAccent =
  DRACULA_TERTIARY_ACCENT[accent] ?: DraculaAccent.CYAN

fun draculaAccentFromKey(key: String): DraculaAccent =
  DraculaAccent.entries.firstOrNull { it.key == key } ?: DraculaAccent.PURPLE

// Builds a complete Material 3 ColorScheme from the Dracula palette.
fun buildDraculaColorScheme(
  accent: DraculaAccent,
  dark: Boolean,
  monochrome: Boolean,
): ColorScheme {
  val b  = draculaBaseColors(dark)
  val bi = draculaBaseColors(!dark)

  val primary   = draculaAccentColor(dark, accent)
  val secondary = if (monochrome) primary
                  else draculaAccentColor(dark, draculaSecondaryAccent(accent))
  val tertiary  = if (monochrome) primary
                  else draculaAccentColor(dark, draculaTertiaryAccent(accent))

  fun onColor(c: Color) = if (c.luminance() < 0.35f) Color.White else b.base
  fun container(c: Color) = lerp(b.base, c, 0.22f)
  fun onContainer(c: Color) = lerp(b.text, c, 0.20f)

  val errorColor = draculaAccentColor(dark, DraculaAccent.RED)

  return if (dark) darkColorScheme(
    primary                = primary,
    onPrimary              = onColor(primary),
    primaryContainer       = container(primary),
    onPrimaryContainer     = onContainer(primary),
    secondary              = secondary,
    onSecondary            = onColor(secondary),
    secondaryContainer     = container(secondary),
    onSecondaryContainer   = onContainer(secondary),
    tertiary               = tertiary,
    onTertiary             = onColor(tertiary),
    tertiaryContainer      = container(tertiary),
    onTertiaryContainer    = onContainer(tertiary),
    error                  = errorColor,
    onError                = onColor(errorColor),
    errorContainer         = container(errorColor),
    onErrorContainer       = onContainer(errorColor),
    background             = b.base,
    onBackground           = b.text,
    surface                = b.base,
    onSurface              = b.text,
    surfaceVariant         = b.surface0,
    onSurfaceVariant       = b.subtext1,
    outline                = b.overlay1,
    outlineVariant         = b.surface2,
    scrim                  = b.crust,
    inverseSurface         = bi.base,
    inverseOnSurface       = bi.text,
    inversePrimary         = draculaAccentColor(!dark, accent),
    surfaceDim             = b.mantle,
    surfaceBright          = b.surface1,
    surfaceContainerLowest = b.crust,
    surfaceContainerLow    = b.mantle,
    surfaceContainer       = b.surface0,
    surfaceContainerHigh   = b.surface1,
    surfaceContainerHighest= b.surface2,
  ) else lightColorScheme(
    primary                = primary,
    onPrimary              = onColor(primary),
    primaryContainer       = container(primary),
    onPrimaryContainer     = onContainer(primary),
    secondary              = secondary,
    onSecondary            = onColor(secondary),
    secondaryContainer     = container(secondary),
    onSecondaryContainer   = onContainer(secondary),
    tertiary               = tertiary,
    onTertiary             = onColor(tertiary),
    tertiaryContainer      = container(tertiary),
    onTertiaryContainer    = onContainer(tertiary),
    error                  = errorColor,
    onError                = onColor(errorColor),
    errorContainer         = container(errorColor),
    onErrorContainer       = onContainer(errorColor),
    background             = b.base,
    onBackground           = b.text,
    surface                = b.base,
    onSurface              = b.text,
    surfaceVariant         = b.surface0,
    onSurfaceVariant       = b.subtext1,
    outline                = b.overlay1,
    outlineVariant         = b.surface2,
    scrim                  = b.crust,
    inverseSurface         = bi.base,
    inverseOnSurface       = bi.text,
    inversePrimary         = draculaAccentColor(!dark, accent),
    surfaceDim             = b.mantle,
    surfaceBright          = b.surface1,
    surfaceContainerLowest = b.crust,
    surfaceContainerLow    = b.mantle,
    surfaceContainer       = b.surface0,
    surfaceContainerHigh   = b.surface1,
    surfaceContainerHighest= b.surface2,
  )
}
