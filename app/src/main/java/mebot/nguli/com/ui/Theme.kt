package mebot.nguli.com.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mebot.nguli.com.R
import mebot.nguli.com.core.Settings
import mebot.nguli.com.core.Appearance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.lerp

/** Shared design decisions. Feature screens use semantic colors from MaterialTheme. */
object MeBotTokens {
    val SpaceXs = 4.dp
    val SpaceSm = 8.dp
    val SpaceMd = 12.dp
    val SpaceLg = 16.dp
    val SpaceXl = 24.dp
    val Space2xl = 32.dp
    val ScreenPadding = 20.dp
    val CardRadius = 24.dp
    val ControlRadius = 16.dp
    val TouchTarget = 48.dp
    val ContentWidth = 680.dp
}

@Immutable
data class FeatureColors(
    val blueSurface: Color = Color(0xFFE8F3FF),
    val onBlue: Color = Color(0xFF225B9A),
    val violetSurface: Color = Color(0xFFF0EAFF),
    val onViolet: Color = Color(0xFF6650A4),
    val mintSurface: Color = Color(0xFFE4F4EF),
    val onMint: Color = Color(0xFF24674F),
)

val LocalFeatureColors = staticCompositionLocalOf { FeatureColors() }
object MeBotPalette {
    val Brand = Color(0xFFE60046)
    val OnBrand = Color.White
    val current: FeatureColors @Composable get() = LocalFeatureColors.current
}

private val Figtree = FontFamily(Font(R.font.figtree))
private fun type(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Float = 0f) =
    TextStyle(fontFamily = Figtree, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp)

private val MeBotTypography = Typography(
    displayLarge = type(64, 68, FontWeight.SemiBold, -2.5f),
    displayMedium = type(52, 58, FontWeight.SemiBold, -2f),
    displaySmall = type(40, 46, FontWeight.SemiBold, -1.4f),
    headlineLarge = type(32, 38, FontWeight.SemiBold, -.9f),
    headlineMedium = type(28, 34, FontWeight.SemiBold, -.7f),
    headlineSmall = type(24, 30, FontWeight.SemiBold, -.5f),
    titleLarge = type(22, 28, FontWeight.SemiBold, -.35f),
    titleMedium = type(16, 22, FontWeight.SemiBold, -.15f),
    titleSmall = type(14, 20, FontWeight.SemiBold),
    bodyLarge = type(16, 24),
    bodyMedium = type(14, 21),
    bodySmall = type(12, 18),
    labelLarge = type(14, 20, FontWeight.SemiBold),
    labelMedium = type(12, 17, FontWeight.Medium),
    labelSmall = type(10, 14, FontWeight.Medium),
)

@Composable
fun MeBotTheme(settings: Settings = Settings(), content: @Composable () -> Unit) {
    val dark = when (settings.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val baseAccent = Color(0xFF000000L or Appearance.rgb(settings).toLong())
    val primary = Color(0xFF000000L or Appearance.readableAccent(Appearance.rgb(settings), dark).toLong())
    val onPrimary = Color(0xFF000000L or Appearance.foreground(primary.toArgb()).toLong())
    val accentContainer = lerp(if (dark) Color(0xFF1C1E27) else Color.White, baseAccent, if (dark) .24f else .10f)
    val onAccentContainer = Color(0xFF000000L or Appearance.foreground(accentContainer.toArgb()).toLong())
    val baseScheme = if (dark) darkColorScheme(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = accentContainer, onPrimaryContainer = onAccentContainer,
        secondary = primary, onSecondary = onPrimary,
        secondaryContainer = accentContainer, onSecondaryContainer = onAccentContainer,
        tertiary = Color(0xFFB7ACF3), onTertiary = Color(0xFF262044),
        tertiaryContainer = Color(0xFF332A50), onTertiaryContainer = Color(0xFFE6DFFF),
        background = Color(0xFF111218), onBackground = Color(0xFFF4F4F8),
        surface = Color(0xFF1C1E27), onSurface = Color(0xFFF4F4F8),
        surfaceVariant = Color(0xFF2B2D39), onSurfaceVariant = Color(0xFFB1B5C6),
        surfaceContainerLowest = Color(0xFF0C0D12), surfaceContainerLow = Color(0xFF191B23),
        surfaceContainer = Color(0xFF23252F), surfaceContainerHigh = Color(0xFF2C2E39),
        surfaceContainerHighest = Color(0xFF353846), surfaceBright = Color(0xFF353846),
        surfaceDim = Color(0xFF111218), surfaceTint = Color.Transparent,
        outline = Color(0xFF74798C), outlineVariant = Color(0xFF333743),
        inverseSurface = Color(0xFFEFF0F5), inverseOnSurface = Color(0xFF1C1E27),
        inversePrimary = Color(0xFFB8003B), error = Color(0xFFFFB4AB),
        errorContainer = Color(0xFF5F2020), onErrorContainer = Color(0xFFFFDAD6),
    ) else lightColorScheme(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = accentContainer, onPrimaryContainer = onAccentContainer,
        secondary = primary, onSecondary = onPrimary,
        secondaryContainer = accentContainer, onSecondaryContainer = onAccentContainer,
        tertiary = Color(0xFF6650A4), onTertiary = Color.White,
        tertiaryContainer = Color(0xFFF0EAFF), onTertiaryContainer = Color(0xFF4F3B82),
        background = Color(0xFFF7F8FA), onBackground = Color(0xFF171922),
        surface = Color.White, onSurface = Color(0xFF171922),
        surfaceVariant = Color(0xFFF0F2F6), onSurfaceVariant = Color(0xFF626878),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F8FA),
        surfaceContainer = Color(0xFFF0F2F6), surfaceContainerHigh = Color(0xFFE9ECF2),
        surfaceContainerHighest = Color(0xFFE3E7EE), surfaceBright = Color.White,
        surfaceDim = Color(0xFFE9ECF2), surfaceTint = Color.Transparent,
        outline = Color(0xFF9198A8), outlineVariant = Color(0xFFE6E9EF),
        inverseSurface = Color(0xFF252832), inverseOnSurface = Color(0xFFF7F8FA),
        inversePrimary = Color(0xFFFFA9C1), error = Color(0xFFBC2634),
        errorContainer = Color(0xFFFFE8E9), onErrorContainer = Color(0xFF8B1520),
    )
    val featureColors = if (dark) FeatureColors(
        blueSurface = Color(0xFF19324B), onBlue = Color(0xFFA1CFFF),
        violetSurface = Color(0xFF312942), onViolet = Color(0xFFD0BAFF),
        mintSurface = Color(0xFF183B30), onMint = Color(0xFFA5DDC6),
    ) else FeatureColors()
    val scheme = when (settings.skin) {
        "paper" -> baseScheme.copy(
            background = if (dark) Color(0xFF171613) else Color(0xFFF7F3EB),
            surface = if (dark) Color(0xFF23211D) else Color(0xFFFFFCF5),
            surfaceContainerLow = if (dark) Color(0xFF1D1B17) else Color(0xFFF0EBDF),
            surfaceContainer = if (dark) Color(0xFF29251E) else Color(0xFFECE5D8))
        "tinted" -> baseScheme.copy(
            background = lerp(baseScheme.background, baseAccent, if (dark) .06f else .035f),
            surface = lerp(baseScheme.surface, baseAccent, .025f),
            surfaceContainerLow = lerp(baseScheme.surfaceContainerLow, baseAccent, .06f),
            surfaceContainer = lerp(baseScheme.surfaceContainer, baseAccent, .08f))
        "oled" -> if (dark) baseScheme.copy(background = Color.Black, surface = Color(0xFF0B0C0F),
            surfaceContainerLow = Color(0xFF101115), surfaceContainer = Color(0xFF181A20)) else baseScheme
        else -> baseScheme
    }
    CompositionLocalProvider(LocalFeatureColors provides featureColors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = MeBotTypography,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(MeBotTokens.ControlRadius),
                large = RoundedCornerShape(MeBotTokens.CardRadius), extraLarge = RoundedCornerShape(32.dp),
            ), content = content,
        )
    }
}

