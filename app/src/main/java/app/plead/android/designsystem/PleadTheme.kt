// The Material 3 base under the Plead design system (PORT.md §2: Material only as a base; the design system is ours).
// Plead is light-only (CONTRACTS-v2 §5: dark-mode polish is cut; iOS forces `.light` at the root).
package app.plead.android.designsystem

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle

private val PleadColorScheme = lightColorScheme(
    primary = PleadColor.burgundy,
    onPrimary = PleadColor.cream,
    secondary = PleadColor.walnut,
    onSecondary = PleadColor.cream,
    tertiary = PleadColor.gold,
    background = PleadColor.background,
    onBackground = PleadColor.text,
    surface = PleadColor.card,
    onSurface = PleadColor.text,
    surfaceVariant = PleadColor.parchment,
    onSurfaceVariant = PleadColor.subtleText,
    outline = PleadColor.separator,
    outlineVariant = PleadColor.separator,
    error = PleadColor.danger,
    onError = PleadColor.cream,
)

/**
 * Material's type scale with no letter spacing ([PleadTracking]): iOS text is untracked unless the Swift tracks it,
 * while the Material 3 defaults add 0.5 sp to body text, 0.1–0.5 sp to labels and 0.25 sp to bodyMedium. Sizes,
 * weights and line heights stay Material's (they only reach text that has no Plead style: Material components'
 * own labels and the odd unstyled `Text`).
 */
object PleadThemeTypography {
    private fun TextStyle.untracked(): TextStyle = copy(letterSpacing = PleadTracking.none)

    val typography: Typography = Typography().let { m ->
        Typography(
            displayLarge = m.displayLarge.untracked(),
            displayMedium = m.displayMedium.untracked(),
            displaySmall = m.displaySmall.untracked(),
            headlineLarge = m.headlineLarge.untracked(),
            headlineMedium = m.headlineMedium.untracked(),
            headlineSmall = m.headlineSmall.untracked(),
            titleLarge = m.titleLarge.untracked(),
            titleMedium = m.titleMedium.untracked(),
            titleSmall = m.titleSmall.untracked(),
            bodyLarge = m.bodyLarge.untracked(),
            bodyMedium = m.bodyMedium.untracked(),
            bodySmall = m.bodySmall.untracked(),
            labelLarge = m.labelLarge.untracked(),
            labelMedium = m.labelMedium.untracked(),
            labelSmall = m.labelSmall.untracked(),
        )
    }

    /** Every style of [typography], by its Material name (tests). */
    val all: Map<String, TextStyle>
        get() = with(typography) {
            mapOf(
                "displayLarge" to displayLarge, "displayMedium" to displayMedium, "displaySmall" to displaySmall,
                "headlineLarge" to headlineLarge, "headlineMedium" to headlineMedium, "headlineSmall" to headlineSmall,
                "titleLarge" to titleLarge, "titleMedium" to titleMedium, "titleSmall" to titleSmall,
                "bodyLarge" to bodyLarge, "bodyMedium" to bodyMedium, "bodySmall" to bodySmall,
                "labelLarge" to labelLarge, "labelMedium" to labelMedium, "labelSmall" to labelSmall,
            )
        }

    /** What a `Text` with no style of its own draws with under [PleadTheme] (`LocalTextStyle`). */
    val defaultTextStyle: TextStyle get() = typography.bodyLarge
}

@Composable
fun PleadTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PleadColorScheme, typography = PleadThemeTypography.typography) {
        // MaterialTheme already provides bodyLarge; provided again so the default is stated here, untracked.
        CompositionLocalProvider(LocalTextStyle provides PleadThemeTypography.defaultTextStyle, content = content)
    }
}
