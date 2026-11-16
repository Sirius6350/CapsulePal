package com.capsulepal.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.capsulepal.app.data.model.CapsuleVersion

/**
 * 一套版本配色。浅色/深色各一份，所有页面只通过 MaterialTheme 或这里的语义颜色取色，
 * 这样切换版本时只换调色板，不动任何布局。
 */
@Immutable
data class CapsulePalette(
    val dark: Boolean,
    val accent: Color,
    val accentDeep: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val onAccentSoft: Color,
    val secondary: Color,
    val secondarySoft: Color,
    val onSecondary: Color,
    val onSecondarySoft: Color,
    val butter: Color,
    val butterSoft: Color,
    val onButterSoft: Color,
    val lavender: Color,
    val cream: Color,
    val card: Color,
    val ink: Color,
    val inkSoft: Color,
    val line: Color,
    val field: Color,
    /** 伙伴外壳渐变：上、中、下三档。 */
    val shellStart: Color,
    val shellMid: Color,
    val shellEnd: Color,
    /** 外壳轮廓光；浅色透明，深色加一层微弱高光。 */
    val shellRim: Color
) {
    val scheme: ColorScheme = if (dark) {
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = accentSoft,
            onPrimaryContainer = onAccentSoft,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondarySoft,
            onSecondaryContainer = onSecondarySoft,
            tertiary = butter,
            onTertiary = onButterSoft,
            tertiaryContainer = butterSoft,
            onTertiaryContainer = onButterSoft,
            background = cream,
            onBackground = ink,
            surface = card,
            onSurface = ink,
            surfaceVariant = field,
            onSurfaceVariant = inkSoft,
            outline = line,
            outlineVariant = line,
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005)
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = accentSoft,
            onPrimaryContainer = onAccentSoft,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondarySoft,
            onSecondaryContainer = onSecondarySoft,
            tertiary = butter,
            onTertiary = onButterSoft,
            tertiaryContainer = butterSoft,
            onTertiaryContainer = onButterSoft,
            background = cream,
            onBackground = ink,
            surface = card,
            onSurface = ink,
            surfaceVariant = field,
            onSurfaceVariant = inkSoft,
            outline = line,
            outlineVariant = line,
            error = Color(0xFFB3261E),
            onError = Color.White
        )
    }
}

private val CoralShell = Triple(Color(0xFFF5A794), Color(0xFFEF8269), Color(0xFFD65D45))
private val CeramicShell = Triple(Color(0xFFFFFDF7), Color(0xFFF2ECE1), Color(0xFFDED3C2))
private val SageShell = Triple(Color(0xFFD9E8C9), Color(0xFFAFCC9C), Color(0xFF87AE78))
private val ElectricShell = Triple(Color(0xFF7DA4FF), Color(0xFF3D6BF0), Color(0xFF1D3FBF))

private fun shellOf(version: CapsuleVersion): Triple<Color, Color, Color> = when (version) {
    CapsuleVersion.TODO -> CeramicShell
    CapsuleVersion.EFFICIENT -> SageShell
    CapsuleVersion.GEEK -> ElectricShell
    else -> CoralShell
}

private val FullLight = CapsulePalette(
    dark = false,
    accent = Color(0xFFE8735A),
    accentDeep = Color(0xFFC9553E),
    accentSoft = Color(0xFFFFE3DB),
    onAccent = Color.White,
    onAccentSoft = Color(0xFF5C1A0D),
    secondary = Color(0xFF7FB6A4),
    secondarySoft = Color(0xFFDDEFE8),
    onSecondary = Color.White,
    onSecondarySoft = Color(0xFF123A2E),
    butter = Color(0xFFF2C879),
    butterSoft = Color(0xFFFFF1D2),
    onButterSoft = Color(0xFF3E2E00),
    lavender = Color(0xFFA99BC9),
    cream = Color(0xFFFAF7F2),
    card = Color(0xFFFFFDF9),
    ink = Color(0xFF2B2724),
    inkSoft = Color(0xFF746B62),
    line = Color(0xFFEDE4D8),
    field = Color(0xFFF2EBE0),
    shellStart = CoralShell.first,
    shellMid = CoralShell.second,
    shellEnd = CoralShell.third,
    shellRim = Color.Transparent
)

private val FullDark = CapsulePalette(
    dark = true,
    accent = Color(0xFFFF9C85),
    accentDeep = Color(0xFFFFB5A3),
    accentSoft = Color(0xFF6D2A1B),
    onAccent = Color(0xFF4A1208),
    onAccentSoft = Color(0xFFFFDAD2),
    secondary = Color(0xFF9CD2C2),
    secondarySoft = Color(0xFF2A5044),
    onSecondary = Color(0xFF11372C),
    onSecondarySoft = Color(0xFFD3EFE6),
    butter = Color(0xFFF2C879),
    butterSoft = Color(0xFF5A4400),
    onButterSoft = Color(0xFFFFE9AE),
    lavender = Color(0xFFC4B8E0),
    cream = Color(0xFF141312),
    card = Color(0xFF211F1D),
    ink = Color(0xFFF3EEE7),
    inkSoft = Color(0xFFB5ABA1),
    line = Color(0xFF35312D),
    field = Color(0xFF2A2724),
    shellStart = CoralShell.first,
    shellMid = CoralShell.second,
    shellEnd = CoralShell.third,
    shellRim = Color(0x33FFFFFF)
)

/** 极简待办：暖白 + 雾蓝；深色炭灰。 */
private val TodoLight = CapsulePalette(
    dark = false,
    accent = Color(0xFF7E9DBE),
    accentDeep = Color(0xFF5D7FA3),
    accentSoft = Color(0xFFE5EDF6),
    onAccent = Color.White,
    onAccentSoft = Color(0xFF1F344C),
    secondary = Color(0xFF9FB6C9),
    secondarySoft = Color(0xFFEDF2F7),
    onSecondary = Color.White,
    onSecondarySoft = Color(0xFF24384A),
    butter = Color(0xFFE9C98B),
    butterSoft = Color(0xFFFBF1DC),
    onButterSoft = Color(0xFF44320A),
    lavender = Color(0xFFA7AAC6),
    cream = Color(0xFFFAF8F4),
    card = Color(0xFFFFFDFA),
    ink = Color(0xFF262726),
    inkSoft = Color(0xFF6F716F),
    line = Color(0xFFE9E4DA),
    field = Color(0xFFF2EEE7),
    shellStart = CeramicShell.first,
    shellMid = CeramicShell.second,
    shellEnd = CeramicShell.third,
    shellRim = Color.Transparent
)

private val TodoDark = CapsulePalette(
    dark = true,
    accent = Color(0xFFA8C4E0),
    accentDeep = Color(0xFFC8DBEF),
    accentSoft = Color(0xFF33465C),
    onAccent = Color(0xFF14283C),
    onAccentSoft = Color(0xFFDCE8F5),
    secondary = Color(0xFFAFC3D5),
    secondarySoft = Color(0xFF2C3A47),
    onSecondary = Color(0xFF1A2A38),
    onSecondarySoft = Color(0xFFDCE8F5),
    butter = Color(0xFFE9C98B),
    butterSoft = Color(0xFF4C3B18),
    onButterSoft = Color(0xFFF7E5C2),
    lavender = Color(0xFFBFC1DA),
    cream = Color(0xFF17181A),
    card = Color(0xFF222427),
    ink = Color(0xFFEFEDE9),
    inkSoft = Color(0xFFABA9A5),
    line = Color(0xFF34363A),
    field = Color(0xFF292B2E),
    shellStart = CeramicShell.first,
    shellMid = CeramicShell.second,
    shellEnd = CeramicShell.third,
    shellRim = Color(0x38FFFFFF)
)

/** 极简效率：暖白偏绿 + 鼠尾草 / 抹茶；深色墨绿黑。 */
private val EfficientLight = CapsulePalette(
    dark = false,
    accent = Color(0xFF7FA98A),
    accentDeep = Color(0xFF5C8A67),
    accentSoft = Color(0xFFE3EFE4),
    onAccent = Color.White,
    onAccentSoft = Color(0xFF1E3A24),
    secondary = Color(0xFFB5C9A5),
    secondarySoft = Color(0xFFEEF2E4),
    onSecondary = Color(0xFF2B3A1F),
    onSecondarySoft = Color(0xFF2B3A1F),
    butter = Color(0xFFE8C97F),
    butterSoft = Color(0xFFF8EFD6),
    onButterSoft = Color(0xFF3E2E00),
    lavender = Color(0xFFA6A6C4),
    cream = Color(0xFFF8F8F2),
    card = Color(0xFFFDFDF8),
    ink = Color(0xFF272A22),
    inkSoft = Color(0xFF6E7466),
    line = Color(0xFFE6E8DA),
    field = Color(0xFFF1F2E9),
    shellStart = SageShell.first,
    shellMid = SageShell.second,
    shellEnd = SageShell.third,
    shellRim = Color.Transparent
)

private val EfficientDark = CapsulePalette(
    dark = true,
    accent = Color(0xFF9FD0AC),
    accentDeep = Color(0xFFC3E6CC),
    accentSoft = Color(0xFF2C4A34),
    onAccent = Color(0xFF10281A),
    onAccentSoft = Color(0xFFD8EFDC),
    secondary = Color(0xFFB7D0A2),
    secondarySoft = Color(0xFF2E3D26),
    onSecondary = Color(0xFF1E2C17),
    onSecondarySoft = Color(0xFFD9E8CC),
    butter = Color(0xFFE8C97F),
    butterSoft = Color(0xFF4A3A12),
    onButterSoft = Color(0xFFF6E7C0),
    lavender = Color(0xFFBDBDDA),
    cream = Color(0xFF101512),
    card = Color(0xFF1A211C),
    ink = Color(0xFFECF1E9),
    inkSoft = Color(0xFFA9B4A8),
    line = Color(0xFF2C352E),
    field = Color(0xFF232B25),
    shellStart = SageShell.first,
    shellMid = SageShell.second,
    shellEnd = SageShell.third,
    shellRim = Color(0x33FFFFFF)
)

/** 极客：冷白 + 克莱因蓝 / 电光蓝；深色近黑。 */
private val GeekLight = CapsulePalette(
    dark = false,
    accent = Color(0xFF1F4FD1),
    accentDeep = Color(0xFF1237A8),
    accentSoft = Color(0xFFDCE6FF),
    onAccent = Color.White,
    onAccentSoft = Color(0xFF102A66),
    secondary = Color(0xFF4D7CFE),
    secondarySoft = Color(0xFFE4EDFF),
    onSecondary = Color.White,
    onSecondarySoft = Color(0xFF122C63),
    butter = Color(0xFFE8C35F),
    butterSoft = Color(0xFFFBF0D0),
    onButterSoft = Color(0xFF3E2E00),
    lavender = Color(0xFF8E9BD6),
    cream = Color(0xFFF6F8FB),
    card = Color(0xFFFFFFFF),
    ink = Color(0xFF1B2430),
    inkSoft = Color(0xFF66707D),
    line = Color(0xFFDFE5EF),
    field = Color(0xFFEDF1F7),
    shellStart = ElectricShell.first,
    shellMid = ElectricShell.second,
    shellEnd = ElectricShell.third,
    shellRim = Color.Transparent
)

private val GeekDark = CapsulePalette(
    dark = true,
    accent = Color(0xFF6E9BFF),
    accentDeep = Color(0xFF9DBBFF),
    accentSoft = Color(0xFF1B2E5C),
    onAccent = Color(0xFF07152F),
    onAccentSoft = Color(0xFFD8E4FF),
    secondary = Color(0xFF7FA8FF),
    secondarySoft = Color(0xFF1F2B45),
    onSecondary = Color(0xFF0A1A38),
    onSecondarySoft = Color(0xFFD8E4FF),
    butter = Color(0xFFE8C35F),
    butterSoft = Color(0xFF4A3A12),
    onButterSoft = Color(0xFFF6E7C0),
    lavender = Color(0xFF9FA9DE),
    cream = Color(0xFF0B0D10),
    card = Color(0xFF14181D),
    ink = Color(0xFFEDF1F7),
    inkSoft = Color(0xFF9AA4B2),
    line = Color(0xFF242B34),
    field = Color(0xFF191E25),
    shellStart = ElectricShell.first,
    shellMid = ElectricShell.second,
    shellEnd = ElectricShell.third,
    shellRim = Color(0x30FFFFFF)
)

/** 当前版本 + 深浅色对应的调色板；默认完整本体浅色。 */
fun paletteFor(version: CapsuleVersion, dark: Boolean): CapsulePalette = when (version) {
    CapsuleVersion.FULL, CapsuleVersion.BALANCED -> if (dark) FullDark else FullLight
    CapsuleVersion.TODO -> if (dark) TodoDark else TodoLight
    CapsuleVersion.EFFICIENT -> if (dark) EfficientDark else EfficientLight
    CapsuleVersion.GEEK -> if (dark) GeekDark else GeekLight
}

val LocalCapsulePalette = staticCompositionLocalOf { FullLight }

// 旧代码里的语义色名保留为随主题变化的取值，版本切换时自动跟着换。
val Cream: Color
    @Composable @ReadOnlyComposable get() = LocalCapsulePalette.current.cream

val CardCream: Color
    @Composable @ReadOnlyComposable get() = LocalCapsulePalette.current.card

val Ink: Color
    @Composable @ReadOnlyComposable get() = LocalCapsulePalette.current.ink

val InkSoft: Color
    @Composable @ReadOnlyComposable get() = LocalCapsulePalette.current.inkSoft

val Coral: Color
    @Composable @ReadOnlyComposable get() = LocalCapsulePalette.current.accent

val CoralDeep: Color
    @Composable @ReadOnlyComposable get() = LocalCapsulePalette.current.accentDeep

val CoralSoft: Color
    @Composable @ReadOnlyComposable get() = LocalCapsulePalette.current.accentSoft

val Mint: Color
    @Composable @ReadOnlyComposable get() = LocalCapsulePalette.current.secondary

val MintSoft: Color
    @Composable @ReadOnlyComposable get() = LocalCapsulePalette.current.secondarySoft

val Butter: Color
    @Composable @ReadOnlyComposable get() = LocalCapsulePalette.current.butter

val ButterSoft: Color
    @Composable @ReadOnlyComposable get() = LocalCapsulePalette.current.butterSoft

val Lavender: Color
    @Composable @ReadOnlyComposable get() = LocalCapsulePalette.current.lavender

val Line: Color
    @Composable @ReadOnlyComposable get() = LocalCapsulePalette.current.line

private val CapsuleTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 54.sp,
        lineHeight = 60.sp,
        letterSpacing = (-1.5).sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
        lineHeight = 46.sp,
        letterSpacing = (-1).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.3.sp
    )
)

@Composable
fun CapsuleTheme(
    version: CapsuleVersion = CapsuleVersion.FULL,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val palette = paletteFor(version, darkTheme)
    CompositionLocalProvider(LocalCapsulePalette provides palette) {
        MaterialTheme(
            colorScheme = palette.scheme,
            typography = CapsuleTypography,
            content = content
        )
    }
}
