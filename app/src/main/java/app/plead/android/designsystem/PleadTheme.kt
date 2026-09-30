// The Material 3 base under the Plead design system (PORT.md §2: Material only as a base; the design system is ours).
// Plead is light-only (CONTRACTS-v2 §5: dark-mode polish is cut; iOS forces `.light` at the root).
package app.plead.android.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

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

@Composable
fun PleadTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PleadColorScheme, content = content)
}
