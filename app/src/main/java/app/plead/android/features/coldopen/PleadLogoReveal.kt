// Port of ArgueWin/Features/ColdOpen/PleadLogoReveal.swift.
package app.plead.android.features.coldopen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import app.plead.android.designsystem.Color as HexColor
import app.plead.android.designsystem.PleadBrandColor
import app.plead.android.designsystem.PleadLogo
import app.plead.android.designsystem.PleadLogoPlacement

/**
 * The end card: cream, the courtroom glowing behind the couple (frame 5), and the Plead logo (`PleadLogo`,
 * heart-accent wordmark + strapline) resolving in the cream top third (scale 0.94→1.0 + fade), then holding.
 * The glow stays low, behind the couple; a cream band keeps the art clear of the logo's clear space (brief:
 * no busy artwork immediately behind the wordmark). iOS drops the glow under Increase Contrast and the veil under
 * Reduce Transparency; Android has neither setting, so both always draw ([increasedContrast] / [reduceTransparency]
 * keep the branches).
 */
@Composable
fun PleadLogoReveal(
    modifier: Modifier = Modifier,
    frames: Map<String, ImageBitmap> = emptyMap(),
    logoScale: Float = 1f,
    logoOpacity: Float = 1f,
    increasedContrast: Boolean = false,
    reduceTransparency: Boolean = false,
) {
    // Measured in the safe area like the Welcome screen, though the art itself is full-bleed.
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    BoxWithConstraints(modifier.fillMaxSize().background(PleadBrandColor.warmCream).clearAndSetSemantics { }) {
        val width = maxWidth
        val height = maxHeight
        ColdOpenSceneView(name = ColdOpenAssets.endcard, frames = frames, fallback = ColdOpenScene.Fallback.none)
        if (!increasedContrast) {
            // Courtroom glow behind the couple.
            Canvas(Modifier.fillMaxSize()) {
                val start = 4.dp.toPx()
                val end = size.width * 0.7f
                drawRect(
                    Brush.radialGradient(
                        0f to HexColor(hex = 0xF3C76A, opacity = 0.30f),
                        (start / end).coerceIn(0f, 1f) to HexColor(hex = 0xF3C76A, opacity = 0.30f),
                        1f to Color.Transparent,
                        center = Offset(size.width * 0.5f, size.height * 0.74f),
                        radius = end,
                    ),
                    blendMode = BlendMode.Plus,
                )
            }
        }
        if (!reduceTransparency) {
            // Keeps the lockup clean where the art's cream fades into the room.
            Canvas(Modifier.fillMaxSize()) {
                drawRect(
                    Brush.verticalGradient(
                        listOf(PleadBrandColor.warmCream, PleadBrandColor.warmCream.copy(alpha = 0f)),
                        startY = 0f,
                        endY = size.height * 0.42f,
                    ),
                )
            }
        }
        // Same size and spot as the Welcome screen's lockup (PleadLogoPlacement, measured in the safe area),
        // so the crossfade into onboarding leaves the logo exactly where it is.
        val top = insets.calculateTopPadding()
        val bottom = insets.calculateBottomPadding()
        val safeHeight = (height - top - bottom).value
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            PleadLogo(
                strapline = true,
                width = PleadLogoPlacement.width(width.value).dp,
                image = frames[ColdOpenAssets.logo],
                modifier = Modifier
                    .padding(top = top + PleadLogoPlacement.top(safeHeight).dp)
                    .graphicsLayer {
                        scaleX = logoScale
                        scaleY = logoScale
                        alpha = logoOpacity
                    },
            )
        }
    }
}
