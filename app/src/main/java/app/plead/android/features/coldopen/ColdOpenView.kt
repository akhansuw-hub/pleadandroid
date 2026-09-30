// Port of ArgueWin/Features/ColdOpen/ColdOpenView.swift (ColdOpenView, LaunchCream).
package app.plead.android.features.coldopen

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import app.plead.android.R
import app.plead.android.designsystem.Color as HexColor
import app.plead.android.designsystem.PleadAssets
import app.plead.android.designsystem.PleadColor
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

private val T = ColdOpenTimeline
private val F = ColdOpenTimeline.Full
private val R_ = ColdOpenTimeline.Reduced

/**
 * Orchestrates the cold open: waits (≤ 300 ms) for the preloaded frames on the launch-screen cream, then plays
 * the timeline in [ColdOpenTimeline] against wall-clock (frame) time, fires the one gavel haptic, and completes the
 * coordinator at the end. Tap anywhere after 1 s skips; TalkBack sees one element that skips on activation.
 * Full-bleed artwork only: no product chrome while this is on screen.
 */
@Composable
fun ColdOpenView(coordinator: ColdOpenCoordinator, modifier: Modifier = Modifier) {
    var start by remember { mutableStateOf<Long?>(null) }
    var now by remember { mutableLongStateOf(0L) }
    val view = LocalView.current
    val mode = coordinator.mode
    val reduceMotion = coordinator.reduceMotion

    LaunchedEffect(coordinator) {
        coordinator.waitForFrames(limit = 300.milliseconds)
        if (!coordinator.isPlaying) return@LaunchedEffect
        val begin = System.nanoTime()
        now = begin
        start = begin
        val total = ColdOpenTimeline.duration(mode, reduceMotion)
        if (mode == ColdOpenCoordinator.Mode.full && !reduceMotion) {
            delay((ColdOpenTimeline.Full.impact * 1000).toLong())
            if (!coordinator.isPlaying) return@LaunchedEffect
            // UIImpactFeedbackGenerator(style: .light).
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        val remaining = total - (System.nanoTime() - begin) / 1e9
        if (remaining > 0) delay((remaining * 1000).toLong())
        coordinator.complete(ColdOpenCoordinator.Completion.finished)
    }
    // SwiftUI `TimelineView(.animation)`: one clock tick per frame once playback has started.
    LaunchedEffect(start) {
        if (start == null) return@LaunchedEffect
        while (true) withFrameNanos { now = System.nanoTime() }
    }

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(coordinator) {
                detectTapGestures {
                    val s = start ?: return@detectTapGestures
                    if ((System.nanoTime() - s) / 1e9 >= ColdOpenTimeline.skipAllowedAfter) coordinator.skip()
                }
            }
            .clearAndSetSemantics {
                contentDescription = "Plead is opening. Double-tap to skip."
                role = Role.Button
                onClick {
                    coordinator.skip()
                    true
                }
            },
    ) {
        LaunchCream()
        val s = start
        if (s != null) {
            val t = maxOf(0.0, (now - s) / 1e9)
            Stage(coordinator, mode, reduceMotion, t)
        }
    }
}

// MARK: Stage

@Composable
private fun Stage(coordinator: ColdOpenCoordinator, mode: ColdOpenCoordinator.Mode, reduceMotion: Boolean, t: Double) {
    when {
        mode == ColdOpenCoordinator.Mode.full && !reduceMotion -> FullSequence(coordinator.frames, t)
        mode == ColdOpenCoordinator.Mode.full -> ReducedSequence(coordinator.frames, t)
        mode == ColdOpenCoordinator.Mode.sting && !reduceMotion -> {
            val logo = T.logoReveal(t, ColdOpenTimeline.Sting.logoIn)
            PleadLogoReveal(
                frames = coordinator.frames,
                logoScale = logo.scale.toFloat(),
                logoOpacity = logo.opacity.toFloat(),
                modifier = Modifier.graphicsLayer { alpha = T.progress(t, ColdOpenTimeline.Sting.endcardIn).toFloat() },
            )
        }
        else -> PleadLogoReveal(frames = coordinator.frames)
    }
}

@Composable
private fun FullSequence(frames: Map<String, ImageBitmap>, t: Double) {
    val logo = T.logoReveal(t, F.logoIn)
    val gavel = T.gavelFrame(t)

    Box(Modifier.fillMaxSize()) {
        // 1 · Courthouse: slow push-in with a warm window glow.
        if (t < F.doorsIn.endInclusive) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    alpha = T.progress(t, F.fadeFromLaunch).toFloat()
                    compositingStrategy = CompositingStrategy.Offscreen
                },
            ) {
                ColdOpenSceneView(
                    name = ColdOpenAssets.courthouse, frames = frames, fallback = ColdOpenScene.Fallback.courtroom,
                    scale = T.courthouseScale(t).toFloat(), anchor = TransformOrigin(0.5f, 0.45f),
                )
                val glow = T.windowGlow(t).toFloat()
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(
                        Brush.radialGradient(
                            listOf(HexColor(hex = 0xFFB85C, opacity = 0.9f), Color.Transparent),
                            center = Offset(size.width * 0.5f, size.height * 0.4f),
                            radius = 260.dp.toPx(),
                        ),
                        alpha = glow,
                        blendMode = BlendMode.Softlight,
                    )
                }
            }
        }
        // 2 · Doors part over the lit interior; brightness blooms.
        if (t >= F.doorsIn.start && t < F.judgeIn.endInclusive) {
            Doors(t, frames, Modifier.graphicsLayer {
                alpha = T.progress(t, F.doorsIn).toFloat()
                compositingStrategy = CompositingStrategy.Offscreen
            })
        }
        // 3 · Judge: push toward the bench.
        if (t >= F.judgeIn.start && gavel == null) {
            ColdOpenSceneView(
                name = ColdOpenAssets.judge, frames = frames, fallback = ColdOpenScene.Fallback.courtroom,
                scale = T.judgeScale(t).toFloat(), anchor = TransformOrigin(0.5f, 0.36f),
                modifier = Modifier.graphicsLayer { alpha = T.progress(t, F.judgeIn).toFloat() },
            )
        }
        // 4 · Gavel: hard cut, 8 fps frames, flash + shake on the strike.
        if (gavel != null && t < F.endcardIn.endInclusive) {
            Box(Modifier.fillMaxSize()) {
                ColdOpenSceneView(
                    name = gavelName(gavel, frames), frames = frames, fallback = ColdOpenScene.Fallback.paywall,
                    scale = 1.04f, offset = T.shake(t),
                )
                Box(Modifier.fillMaxSize().background(HexColor(hex = 0xFFF1D6).copy(alpha = T.impactFlash(t).toFloat())))
            }
        }
        // 5 · End card.
        if (t >= F.endcardIn.start) {
            PleadLogoReveal(
                frames = frames,
                logoScale = logo.scale.toFloat(),
                logoOpacity = logo.opacity.toFloat(),
                modifier = Modifier.graphicsLayer { alpha = T.progress(t, F.endcardIn).toFloat() },
            )
        }
    }
}

@Composable
private fun Doors(t: Double, frames: Map<String, ImageBitmap>, modifier: Modifier) {
    val none = ColdOpenScene.Fallback.none
    val hasLayers = ColdOpenScene.resolveName(ColdOpenAssets.doorLeft, frames, none) != null &&
        ColdOpenScene.resolveName(ColdOpenAssets.doorRight, frames, none) != null
    val open = T.doorsOpen(t)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = maxWidth.value
        ColdOpenSceneView(
            name = ColdOpenAssets.doors, frames = frames, fallback = ColdOpenScene.Fallback.paywall,
            scale = (1.0 + 0.04 * open).toFloat(), anchor = TransformOrigin(0.5f, 0.6f),
        )
        if (hasLayers) {
            // Each layer is a full canvas with one half opaque: slide the halves out past the edges.
            ColdOpenSceneView(name = ColdOpenAssets.doorLeft, frames = frames, fallback = none, offset = Offset((-width * 0.56 * open).toFloat(), 0f))
            ColdOpenSceneView(name = ColdOpenAssets.doorRight, frames = frames, fallback = none, offset = Offset((width * 0.56 * open).toFloat(), 0f))
        } else if (ColdOpenScene.resolveName(ColdOpenAssets.doorsClosed, frames, none) != null) {
            // No layers: crossfade from the closed doors to the open frame.
            ColdOpenSceneView(
                name = ColdOpenAssets.doorsClosed, frames = frames, fallback = none,
                modifier = Modifier.graphicsLayer { alpha = (1 - open).toFloat() },
            )
        }
        val bloom = T.bloom(t).toFloat()
        Canvas(Modifier.fillMaxSize()) {
            drawRect(HexColor(hex = 0xFFF4E0), alpha = bloom, blendMode = BlendMode.Plus)
        }
    }
}

/** Missing frames: hold the nearest earlier frame that exists. */
private fun gavelName(index: Int, frames: Map<String, ImageBitmap>): String {
    for (i in index downTo 0) {
        val name = ColdOpenAssets.gavel[i]
        if (frames[name] != null || ColdOpenAssets.exists(name)) return name
    }
    return ColdOpenAssets.gavel[index]
}

/** Reduce Motion: static crossfades 1 → 3 → 5, no camera moves, shake or haptic. */
@Composable
private fun ReducedSequence(frames: Map<String, ImageBitmap>, t: Double) {
    Box(Modifier.fillMaxSize()) {
        ColdOpenSceneView(name = ColdOpenAssets.courthouse, frames = frames, fallback = ColdOpenScene.Fallback.courtroom)
        ColdOpenSceneView(
            name = ColdOpenAssets.judge, frames = frames, fallback = ColdOpenScene.Fallback.courtroom,
            modifier = Modifier.graphicsLayer { alpha = T.progress(t, R_.judgeIn).toFloat() },
        )
        PleadLogoReveal(
            frames = frames,
            modifier = Modifier.graphicsLayer { alpha = T.progress(t, R_.endcardIn).toFloat() },
        )
    }
}

/**
 * The launch screen's look (LaunchBackground + the PleadWordmark at its point size, centred), reproduced so the
 * hand-off from the system splash is seamless.
 */
@Composable
fun LaunchCream(modifier: Modifier = Modifier) {
    val mark = ImageBitmap.imageResource(R.drawable.plead_wordmark)
    val scale = PleadAssets.scale("plead_wordmark")
    Box(modifier.fillMaxSize().background(PleadColor.launchBackground).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Image(
            painter = BitmapPainter(mark, filterQuality = FilterQuality.None),
            contentDescription = null,
            modifier = Modifier.size((mark.width / scale).dp, (mark.height / scale).dp),
        )
    }
}
