package app.autobrief.car.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.autobrief.car.BuildConfig
import app.autobrief.car.R

/** Bundled Pretendard (SIL OFL 1.1, unmodified; licence in assets/licenses). */
val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_bold, FontWeight.Bold),
)

// The look the app shipped with (v2.0.1). Every text colour keeps WCAG 4.5:1 (AA) on background, surface, surfaceVariant
// and on its own tint.
private val CurrentNight = ColorRoles(
    background = Color(0xFF0A0C10), surface = Color(0xFF11151C), surfaceVariant = Color(0xFF181E27), surfaceRaised = Color(0xFF11151C),
    outline = Color(0xFF252E3D), hairline = Color(0xFF252E3D).copy(alpha = 0.45f),
    textPrimary = Color(0xFFF2F5F9), textSecondary = Color(0xFF9AA6B8), textTertiary = Color(0xFF9AA6B8),
    primary = Color(0xFF66ADFF), onPrimary = Color(0xFF0A0C10), accent = Color(0xFF66ADFF), onAccent = Color(0xFF0A0C10),
    positive = Color(0xFF4FD685), warning = Color(0xFFF4A93B), critical = Color(0xFFF88078),
    chipBg = Color(0xFF181E27), chipText = Color(0xFFF2F5F9),
    purposeCommute = Color(0xFF70B5FF), purposeBusiness = Color(0xFF3CC9B0), purposePersonal = Color(0xFFDDA77B),
    purposeUnclassified = Color(0xFFF4A93B), purposeGap = Color(0xFFA3B1C6),
    tintAlpha = 0.16f, emphasisBorderAlpha = 0.55f, chipBorderAlpha = 0.45f, tonalBorderAlpha = 0.65f, outlinedFillAlpha = 0.5f,
)

private val CurrentDay = ColorRoles(
    background = Color(0xFFF3F5F8), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFE8ECF2), surfaceRaised = Color(0xFFFFFFFF),
    outline = Color(0xFFCBD2DE), hairline = Color(0xFFCBD2DE).copy(alpha = 0.45f),
    textPrimary = Color(0xFF0E131B), textSecondary = Color(0xFF4A5568), textTertiary = Color(0xFF4A5568),
    primary = Color(0xFF0051B8), onPrimary = Color(0xFFFFFFFF), accent = Color(0xFF0051B8), onAccent = Color(0xFFFFFFFF),
    positive = Color(0xFF166534), warning = Color(0xFF965000), critical = Color(0xFFB91C1C),
    chipBg = Color(0xFFE8ECF2), chipText = Color(0xFF0E131B),
    purposeCommute = Color(0xFF0B5394), purposeBusiness = Color(0xFF0B6B58), purposePersonal = Color(0xFF8A4F1D),
    purposeUnclassified = Color(0xFF965000), purposeGap = Color(0xFF475569),
    tintAlpha = 0.12f, emphasisBorderAlpha = 0.55f, chipBorderAlpha = 0.45f, tonalBorderAlpha = 0.65f, outlinedFillAlpha = 0.5f,
)

private val CurrentBody = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Normal, fontSize = 24.sp, lineHeight = 32.sp)

private val CurrentTokens = AutoBriefTokens(
    night = CurrentNight,
    day = CurrentDay,
    type = TypeScale(
        display = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 64.sp, lineHeight = 70.sp),
        headline = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 42.sp),
        title = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp),
        body = CurrentBody,
        label = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
        bodyStrong = CurrentBody.copy(fontWeight = FontWeight.Bold),
        caption = CurrentBody,
        glanceScale = 1.6f,
    ),
    shape = ShapeTokens(
        cornerSmall = 6.dp, cornerMedium = 10.dp, cornerLarge = 14.dp, strokeHairline = 1.dp,
        cornerControl = 8.dp, cornerRow = 12.dp, cornerHero = 16.dp, strokeEmphasis = 1.5.dp, strokeStrong = 2.dp,
        indicatorWidth = 3.dp, swatchWidth = 4.dp,
    ),
    chipStyle = ChipStyle.LEADING_BAR,
    cardStyle = CardStyle.OUTLINE,
)

/**
 * The design variants. The build picks one ([BUILD], from BuildConfig.THEME_VARIANT: `-Pautobrief.themeVariant=A`);
 * the screenshot test draws any of them by providing [LocalThemeVariant].
 */
enum class ThemeVariant(val tokens: AutoBriefTokens) {
    /** The look the app shipped with. */
    CURRENT(CurrentTokens),

    /** Placeholder: the same as CURRENT until its token set is filled in. */
    A(CurrentTokens),

    /** Placeholder: the same as CURRENT until its token set is filled in. */
    B(CurrentTokens),

    /** Placeholder: the same as CURRENT until its token set is filled in. */
    C(CurrentTokens);

    companion object {
        /** The variant this build draws with. */
        val BUILD: ThemeVariant = valueOf(BuildConfig.THEME_VARIANT)
    }
}

/** The variant [AutoBriefTheme] draws with: the build's own unless a caller provides another. */
val LocalThemeVariant = staticCompositionLocalOf { ThemeVariant.BUILD }

private val LocalAutoBriefTokens = staticCompositionLocalOf { ThemeVariant.BUILD.tokens }
private val LocalColorRoles = staticCompositionLocalOf { ThemeVariant.BUILD.tokens.night }

/** The active variant's tokens, inside [AutoBriefTheme]. */
object AutoBriefTheme {
    val tokens: AutoBriefTokens
        @Composable @ReadOnlyComposable get() = LocalAutoBriefTokens.current

    /** The colour roles of the active mode (night or day). */
    val colors: ColorRoles
        @Composable @ReadOnlyComposable get() = LocalColorRoles.current

    val shape: ShapeTokens
        @Composable @ReadOnlyComposable get() = LocalAutoBriefTokens.current.shape
}

/** The active variant's [TypeScale], by name (`Type.body`). */
object Type {
    val display: TextStyle @Composable @ReadOnlyComposable get() = LocalAutoBriefTokens.current.type.display
    val headline: TextStyle @Composable @ReadOnlyComposable get() = LocalAutoBriefTokens.current.type.headline
    val title: TextStyle @Composable @ReadOnlyComposable get() = LocalAutoBriefTokens.current.type.title
    val body: TextStyle @Composable @ReadOnlyComposable get() = LocalAutoBriefTokens.current.type.body
    val bodyStrong: TextStyle @Composable @ReadOnlyComposable get() = LocalAutoBriefTokens.current.type.bodyStrong
    val caption: TextStyle @Composable @ReadOnlyComposable get() = LocalAutoBriefTokens.current.type.caption
    val label: TextStyle @Composable @ReadOnlyComposable get() = LocalAutoBriefTokens.current.type.label
    val glance: TextStyle @Composable @ReadOnlyComposable get() = LocalAutoBriefTokens.current.type.glance
}

@Composable
fun AutoBriefTheme(night: Boolean = isSystemInDarkTheme(), variant: ThemeVariant = LocalThemeVariant.current, content: @Composable () -> Unit) {
    val tokens = variant.tokens
    val c = tokens.colors(night)
    val scheme = if (night) {
        darkColorScheme(primary = c.primary, onPrimary = c.onPrimary, background = c.background, surface = c.surface, onSurface = c.textPrimary, outline = c.outline)
    } else {
        lightColorScheme(primary = c.primary, onPrimary = c.onPrimary, background = c.background, surface = c.surface, onSurface = c.textPrimary, outline = c.outline)
    }
    val t = tokens.type
    val typography = Typography(
        bodyLarge = t.body, bodyMedium = t.body, bodySmall = t.caption,
        labelLarge = t.label, titleLarge = t.title, headlineMedium = t.headline,
    )
    CompositionLocalProvider(LocalAutoBriefTokens provides tokens, LocalColorRoles provides c) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
