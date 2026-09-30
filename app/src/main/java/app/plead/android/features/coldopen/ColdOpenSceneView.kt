// Port of ArgueWin/Features/ColdOpen/ColdOpenSceneView.swift.
package app.plead.android.features.coldopen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import app.plead.android.designsystem.Color as HexColor
import app.plead.android.designsystem.PixelGrid
import app.plead.android.designsystem.PleadBrandColor
import app.plead.android.designsystem.PleadPixelArt

/**
 * One full-bleed cold-open frame: aspect-fill (never stretched) on every phone, nearest-neighbour sampling so
 * the pixel art stays crisp when scaled, with an optional camera transform (scale about an anchor, offset in dp).
 * Falls back to the courtroom art, then to a drawn placeholder, if the frame isn't bundled.
 */
@Composable
fun ColdOpenSceneView(
    name: String,
    modifier: Modifier = Modifier,
    frames: Map<String, ImageBitmap> = emptyMap(),
    fallback: ColdOpenScene.Fallback = ColdOpenScene.Fallback.courtroom,
    scale: Float = 1f,
    anchor: TransformOrigin = TransformOrigin.Center,
    offset: Offset = Offset.Zero,
) {
    val resources = LocalResources.current
    val preloaded = frames[name]
    // Not preloaded (or already released): load the bundled image or the fallback art once (Swift `UIImage(named:)`).
    val loaded = remember(name, fallback, resources, preloaded == null) {
        if (preloaded != null) null else ColdOpenScene.resolveName(name, emptyMap(), fallback)?.let { ColdOpenAssets.load(it, resources) }
    }
    val image = preloaded ?: loaded
    Box(modifier.fillMaxSize().clipToBounds().clearAndSetSemantics { }) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = anchor
                translationX = offset.x.dp.toPx()
                translationY = offset.y.dp.toPx()
            },
        ) {
            when {
                image != null -> Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.fillMaxSize(),
                )
                fallback == ColdOpenScene.Fallback.none -> Unit
                else -> ColdOpenPlaceholder()
            }
        }
    }
}

/** Swift `ColdOpenSceneView`'s statics. */
object ColdOpenScene {
    enum class Fallback { courtroom, paywall, placeholder, none }

    /**
     * The asset that would be drawn for [name]: the decoded frame or bundled image, else the fallback art (Swift
     * `resolve(_:frames:fallback:)`, returning the name rather than the image). Null = nothing to draw.
     */
    fun resolveName(name: String, frames: Map<String, ImageBitmap>, fallback: Fallback): String? {
        if (frames[name] != null || ColdOpenAssets.exists(name)) return name
        return when (fallback) {
            Fallback.courtroom -> listOf(ColdOpenAssets.fallbackCourtroom, ColdOpenAssets.fallbackPaywall).firstOrNull(ColdOpenAssets::exists)
            Fallback.paywall -> listOf(ColdOpenAssets.fallbackPaywall, ColdOpenAssets.fallbackCourtroom).firstOrNull(ColdOpenAssets::exists)
            Fallback.placeholder, Fallback.none -> null
        }
    }
}

/** Drawn stand-in when no art is available at all: warm wine-to-mahogany wash with the pixel scales. */
@Composable
fun ColdOpenPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().background(Brush.verticalGradient(listOf(PleadBrandColor.deepWine, PleadBrandColor.mahogany))),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val start = 10.dp.toPx()
            val end = 320.dp.toPx()
            drawRect(
                Brush.radialGradient(
                    0f to HexColor(hex = 0xF3C76A, opacity = 0.35f),
                    start / end to HexColor(hex = 0xF3C76A, opacity = 0.35f),
                    1f to Color.Transparent,
                    center = center,
                    radius = end,
                ),
            )
        }
        PixelGrid(
            rows = PleadPixelArt.scales,
            colors = mapOf('g' to PleadBrandColor.gold, 'l' to HexColor(hex = 0xF3C76A), 's' to HexColor(hex = 0x8F6236)),
            modifier = Modifier.size(150.dp, 100.dp),
        )
    }
}
