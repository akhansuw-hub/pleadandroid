// The mock trial's bubble exit (port of the removal half of MockTrialScene.swift's bubble transitions).
//
// Swift gives every line bubble `.transition(.asymmetric(insertion: .identity, removal: .opacity))` and animates the
// stage with `.animation(reduceMotion ? .easeOut(duration: 0.15) : .easeOut(duration: MockTrialTiming.fadeBack),
// value: player.currentBeat / player.partsShown)`. So a bubble that leaves its slot (the next line takes it, the band
// gives way to deliberation / judgement / CASE CLOSED) fades from its current opacity to 0 where it stood, while the
// layout already shows what replaced it. Compose drops a removed child at once, so the stage keeps a short-lived copy
// of each departed bubble at its last bounds in an overlay over the band and fades that copy out:
//   MockTrialBubbleExit       duration / easing / opacity curve (the Swift animation values)
//   MockTrialExitPlan         which bubbles left between two compositions, with their last bounds and opacity
//   MockTrialBubbleExits      the overlay that draws and fades the departures (hidden from TalkBack, as SwiftUI
//                             removes a leaving view from accessibility at once)
package app.plead.android.features.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import app.plead.android.courtroom.DynamicTypeCap
import app.plead.android.courtroom.frameIn
import app.plead.android.designsystem.PleadMotion
import kotlin.math.roundToInt

/** The removal animation's values (Swift: the stage's `.animation(...)` applied to `.opacity` removal). */
object MockTrialBubbleExit {
    /** `.easeOut(duration: MockTrialTiming.fadeBack)`; Reduce Motion `.easeOut(duration: 0.15)`. */
    fun seconds(reduceMotion: Boolean): Double = if (reduceMotion) 0.15 else MockTrialTiming.fadeBack

    fun millis(reduceMotion: Boolean): Int = seconds(reduceMotion).millis()

    /** SwiftUI `.easeOut` (cubic-bezier 0, 0, 0.58, 1). */
    val easing: Easing get() = PleadMotion.easeOut

    /** Opacity of a bubble `elapsedMillis` after it left, starting from the opacity it had (`from`). */
    fun alpha(from: Float, elapsedMillis: Int, reduceMotion: Boolean): Float {
        val d = millis(reduceMotion)
        if (d <= 0) return 0f
        val t = (elapsedMillis.toFloat() / d).coerceIn(0f, 1f)
        return from * (1f - easing.transform(t))
    }

    /** Ids shown before and gone now, in their previous order. */
    fun departed(previous: List<String>, current: List<String>): List<String> {
        val now = current.toSet()
        return previous.filter { it !in now }
    }
}

/**
 * Bookkeeping for one slot's departures. Plain (non-snapshot) state: [sync] runs during composition and must not
 * write snapshot state; the overlay recomposes on its own tick when a departure finishes.
 */
class MockTrialExitPlan<T>(private val id: (T) -> String) {
    /** A bubble that left: drawn at [bounds] (root coordinates, px), fading from [from] to 0. */
    data class Departure<T>(val serial: Int, val item: T, val bounds: Rect, val from: Float) {
        val id: String get() = "exit-$serial"
    }

    private class Live<T>(var item: T, var alpha: Float, var bounds: Rect?)

    private val live = LinkedHashMap<String, Live<T>>()
    private var shown: List<String> = emptyList()
    private var serial = 0
    private val _departures = ArrayList<Departure<T>>()
    val departures: List<Departure<T>> get() = _departures

    /**
     * The slot now shows [items], each at opacity `alpha(item)`. Bubbles that are gone become departures (a bubble
     * never laid out has no bounds and simply goes); one that comes back cancels its departure (Swift re-inserts it).
     * Returns true when the departures changed.
     */
    fun sync(items: List<T>, alpha: (T) -> Float): Boolean {
        val ids = items.map(id)
        var changed = false
        for (gone in MockTrialBubbleExit.departed(shown, ids)) {
            val l = live.remove(gone) ?: continue
            val b = l.bounds ?: continue
            if (l.alpha <= 0f) continue
            _departures += Departure(serial++, l.item, b, l.alpha)
            changed = true
        }
        if (_departures.removeAll { id(it.item) in ids }) changed = true
        for (item in items) {
            val k = id(item)
            val l = live[k]
            if (l == null) live[k] = Live(item, alpha(item), null) else { l.item = item; l.alpha = alpha(item) }
        }
        shown = ids
        return changed
    }

    /** Where a shown bubble was last laid out (root coordinates, px). */
    fun place(itemId: String, bounds: Rect) {
        live[itemId]?.bounds = bounds
    }

    /** The fade of departure [serial] finished. */
    fun finish(serial: Int): Boolean = _departures.removeAll { it.serial == serial }
}

/** One slot's exit overlay: remember it per slot, feed it the shown bubbles, tag each bubble with [track]. */
@Stable
class MockTrialBubbleExitHost<T>(id: (T) -> String) {
    internal val plan = MockTrialExitPlan(id)
    internal var coords: LayoutCoordinates? = null
    internal var tick by mutableIntStateOf(0)
    private val idOf = id

    /** Records where [item] is laid out (root coordinates; the overlay converts to its own when it draws). */
    fun track(item: T): Modifier = Modifier.onGloballyPositioned { c ->
        if (!c.isAttached) return@onGloballyPositioned
        val o = c.positionInRoot()
        plan.place(idOf(item), Rect(o, Size(c.size.width.toFloat(), c.size.height.toFloat())))
    }
}

@Composable
fun <T> rememberMockTrialBubbleExitHost(id: (T) -> String): MockTrialBubbleExitHost<T> = remember { MockTrialBubbleExitHost(id) }

/**
 * Draws [host]'s departed bubbles over [rect] (the slot's frame on the stage, dp), each at its last bounds, fading
 * out with [MockTrialBubbleExit]. [shown] is what the slot shows now (empty when the slot is gone); [render] draws a
 * departed bubble in its final state (no entrance, full text, no semantics).
 */
@Composable
fun <T> MockTrialBubbleExits(
    host: MockTrialBubbleExitHost<T>,
    shown: List<T>,
    alpha: (T) -> Float,
    rect: Rect,
    zIndex: Float,
    reduceMotion: Boolean,
    render: @Composable (T) -> Unit,
) {
    host.plan.sync(shown, alpha)
    check(host.tick >= 0) // read: recompose when a fade finishes
    val density = LocalDensity.current
    DynamicTypeCap(MockTrialStage.maxType) {
        Box(
            Modifier
                .zIndex(zIndex)
                .frameIn(rect)
                .clipToBounds()
                .onGloballyPositioned { host.coords = it }
                .clearAndSetSemantics { },
        ) {
            for (d in host.plan.departures) {
                key(d.id) {
                    val fade = remember { Animatable(d.from) }
                    LaunchedEffect(Unit) {
                        fade.animateTo(0f, tween(MockTrialBubbleExit.millis(reduceMotion), easing = MockTrialBubbleExit.easing))
                        if (host.plan.finish(d.serial)) host.tick++
                    }
                    val w = with(density) { d.bounds.width.toDp() }
                    val h = with(density) { d.bounds.height.toDp() }
                    Box(
                        Modifier
                            .offset {
                                val origin = host.coords?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero
                                IntOffset((d.bounds.left - origin.x).roundToInt(), (d.bounds.top - origin.y).roundToInt())
                            }
                            .size(w, h)
                            .graphicsLayer { this.alpha = fade.value },
                    ) { render(d.item) }
                }
            }
        }
    }
}
