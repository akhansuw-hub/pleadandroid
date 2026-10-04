// Port of ArgueWin/Features/Onboarding/About/CourtroomOathView.swift. Screen 6 of "About you two": The Courtroom Oath
// (CONTRACTS-v2 amendment bd).
//
//   hero     banners sway, the judge taps the gavel in the air; during the hold he raises it (0 → 50°, eased) and both
//            parties raise a palm to swear; on completion the gavel strikes (−5°, 0.10 s, impact pixels, a 3 dp room
//            shake)
//   card     the oath on a parchment card with a double gold rule and corner brackets; on completion a light flash
//            and the court seal stamps onto its corner (1.6 → 1.0, 0.18 s ease-in)
//   button   HOLD TO TAKE THE OATH: hold 2.0 s. A gold fill sweeps across the button and a gold line traces its edge;
//            haptics tick faster and stronger as it fills (every 0.26 s → 0.10 s, intensity 0.35 → 1.0, light then
//            medium); releasing early drains the fill (0.35 s × fill) and nothing advances. Completion: heavy impact
//            (the strike) + success, then the flow moves to the Mock Trial 0.8 s later.
//   a11y     TalkBack / Switch Access: activating the button takes the oath at once (a hold must not block them).
//   Reduce Motion: the 2-second hold stays; the hero is still, the seal and flash fade instead of stamping.
//
// Not skippable: the step only advances through `OnboardingModel.takeOath()`.
//
// Android: the hold runs on the Compose frame clock (`withFrameMillis`), so a press and its release are stamped with
// frame times (≤ one frame late) and the 2 s completion is checked each frame (Swift: `onLongPressGesture` with a
// 2-second minimum duration and 60 pt maximum distance, which the pointer loop below mirrors).
package app.plead.android.features.onboarding.about

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.app.AppModel
import app.plead.android.app.DemoHarness
import app.plead.android.designsystem.Color as hex
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.designsystem.pleadShadow
import app.plead.android.features.onboarding.CourtLayerStyle
import app.plead.android.features.onboarding.CourtSeal
import app.plead.android.features.onboarding.OnboardingModel
import app.plead.android.features.onboarding.OnboardingPalette
import app.plead.android.features.onboarding.OnboardingStep
import app.plead.android.features.onboarding.PleadPressStyle
import app.plead.android.features.onboarding.courtLayer
import app.plead.android.features.onboarding.onboardingModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * The hold's fill over time (pure; unit-tested): rises linearly for `hold` seconds while pressed; after a release it
 * drains from where it was, over `drain × fill` seconds. Times are milliseconds on one clock.
 */
data class OathHoldClock(
    val pressStart: Long? = null,
    val releaseAt: Long? = null,
    val releaseFrom: Double = 0.0,
) {
    fun progress(at: Long): Double {
        if (pressStart != null) return ((at - pressStart) / 1000.0 / hold).coerceIn(0.0, 1.0)
        if (releaseAt == null || releaseFrom <= 0) return 0.0
        val span = drain * releaseFrom
        val p = ((at - releaseAt) / 1000.0 / maxOf(span, 0.01)).coerceIn(0.0, 1.0)
        val eased = 1 - (1 - p) * (1 - p)
        return releaseFrom * (1 - eased)
    }

    /** Something is moving (pressed, or still draining at `at`). */
    fun isActive(at: Long): Boolean =
        pressStart != null || (releaseAt?.let { (at - it) / 1000.0 < drain * releaseFrom } ?: false)

    fun pressed(at: Long): OathHoldClock = OathHoldClock(pressStart = at, releaseAt = null, releaseFrom = 0.0)

    fun released(at: Long): OathHoldClock = OathHoldClock(pressStart = null, releaseAt = at, releaseFrom = progress(at))

    companion object {
        const val hold: Double = CourtroomOathCopy.holdDuration
        const val drain: Double = 0.35

        /** The gavel follows the fill with an ease-in-out (it lifts slowly, then commits). */
        fun gavelRaise(p: Double): Double = p * p * (3 - 2 * p)
    }
}

/** Timing of the oath's completion (seconds). */
object CourtroomOathTiming {
    /** Amendment be: strike → burst (≈ 1 s) → advance. */
    const val burstDelay: Double = 0.08
    const val advanceDelay: Double = 1.25
}

@Composable
fun CourtroomOathView(app: AppModel) {
    CourtroomOathView(app.onboardingModel)
}

@Composable
fun CourtroomOathView(model: OnboardingModel) {
    val reduceMotion = accessibilityReduceMotion()
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    var clock by remember { mutableStateOf(OathHoldClock()) }
    var now by remember { mutableLongStateOf(0L) }
    var pending by remember { mutableStateOf<Boolean?>(null) } // true = press, false = release (stamped next frame)
    var animating by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }
    var strikeCount by remember { mutableIntStateOf(0) }
    val sealed = remember { Animatable(0f) }
    val flash = remember { Animatable(0f) }
    /** DEBUG captures only (`AWOathProgress`): a fill held still. */
    val frozen = remember { DemoHarness.oathProgress?.coerceIn(0.0, 0.99) }
    /** DEBUG captures only (`AWOathCelebration 0.35`): the completed oath with the burst held at that time. */
    val frozenBurst = remember { DemoHarness.oathCelebration }
    /** Seconds into the celebration burst (null = none yet), on the frame clock. */
    var burstT by remember { mutableStateOf<Double?>(null) }
    var burstRunning by remember { mutableStateOf(false) }
    /** The oath card's centre and the screen's origin in the root (the burst's origin). */
    var cardCenter by remember { mutableStateOf(Offset.Zero) }
    var screenOrigin by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(Unit) {
        if (frozenBurst != null) {
            completed = true
            sealed.snapTo(1f)
            burstT = frozenBurst
        }
    }
    LaunchedEffect(burstRunning) {
        if (!burstRunning) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) {
            val t = withFrameMillis { (it - start) / 1000.0 }
            burstT = t
            if (t > OathCelebration.duration + 0.1) break
        }
    }

    fun fill(): Double = if (completed) 1.0 else (frozen ?: clock.progress(now))

    fun complete() {
        if (completed || model.step != OnboardingStep.oath) return
        completed = true
        clock = OathHoldClock()
        AboutHaptics.strike(view)
        strikeCount += 1
        scope.launch {
            if (reduceMotion) sealed.animateTo(1f, tween(250, easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)))
            else sealed.animateTo(1f, tween(180, delayMillis = 60, easing = CubicBezierEasing(0.42f, 0f, 1f, 1f)))
        }
        scope.launch {
            flash.animateTo(1f, tween(80, delayMillis = 60, easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)))
        }
        // Amendment be: strike → burst (≈ 1 s, never under Reduce Motion) → advance.
        scope.launch {
            delay((CourtroomOathTiming.burstDelay * 1000).toLong())
            if (!reduceMotion) burstRunning = true
            delay(20)
            AboutHaptics.success(view)
            launch { flash.animateTo(0f, tween(400, easing = CubicBezierEasing(0f, 0f, 0.58f, 1f))) }
            delay(((CourtroomOathTiming.advanceDelay - CourtroomOathTiming.burstDelay - 0.02) * 1000).toLong())
            animating = false
            model.takeOath()
        }
    }

    // The frame clock: stamps presses / releases, completes the hold at 2 s, stops once nothing moves.
    LaunchedEffect(animating) {
        if (!animating) return@LaunchedEffect
        while (animating) {
            withFrameMillis { t ->
                now = t
                when (pending) {
                    true -> clock = clock.pressed(t)
                    false -> if (clock.pressStart != null) clock = clock.released(t)
                    null -> Unit
                }
                pending = null
                if (!completed && clock.pressStart != null && clock.progress(t) >= 1.0) complete()
                if (!completed && !clock.isActive(t)) animating = false
            }
        }
    }

    // Rising haptics while held (every 0.26 s → 0.10 s, intensity 0.35 → 1.0).
    val holding = clock.pressStart != null || pending == true
    LaunchedEffect(holding) {
        if (!holding) return@LaunchedEffect
        var elapsed = 0.0
        while (elapsed < OathHoldClock.hold - 0.05) {
            val f = elapsed / OathHoldClock.hold
            AboutHaptics.holdTick(view, f)
            val interval = AboutHaptics.holdInterval(f)
            delay((interval * 1000).toLong())
            elapsed += interval
        }
    }

    fun press() {
        if (completed) return
        pending = true
        animating = true
    }

    fun release() {
        if (completed) return
        if (clock.pressStart == null && pending != true) return
        pending = if (clock.pressStart == null) null else false
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { screenOrigin = it.positionInRoot() }) {
    AboutScaffold(
        modifier = Modifier.fillMaxSize().testTag("onboarding.about.oath"),
        contentAlignment = Alignment.CenterHorizontally,
        hero = {
            AboutHeroScene(
                AboutHeroKind.oath,
                Modifier.fillMaxSize().courtLayer(0, CourtLayerStyle.fade),
                gavel = { AboutGavelState(raise = if (completed) 1.0 else (if (reduceMotion) 0.0 else OathHoldClock.gavelRaise(fill())), struck = completed) },
                strikeCount = strikeCount,
            )
        },
        content = {
            AboutCopyBlock(CourtroomOathCopy.eyebrow, CourtroomOathCopy.title, center = true, titleIdentifier = "onboarding.oath.title")
            // Amendment be: the card is centred in the space between the title and the hold button.
            Spacer(Modifier.height(16.dp))
            Spacer(Modifier.weight(1f))
            OathCard(
                sealed = { sealed.value }, flash = { flash.value }, reduceMotion = reduceMotion,
                modifier = Modifier
                    .onGloballyPositioned { cardCenter = it.boundsInRoot().center }
                    .courtLayer(2, CourtLayerStyle.spring, delay = 0.08),
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(16.dp))
        },
        cta = {
            Box(Modifier.fillMaxWidth().courtLayer(3, delay = 0.1)) {
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    OathHoldButton(
                        progress = { fill().toFloat() },
                        pressed = clock.pressStart != null || pending == true,
                        reduceMotion = reduceMotion,
                        onPress = ::press,
                        onRelease = ::release,
                        onAccessibilityActivate = ::complete,
                    )
                    Text(
                        CourtroomOathCopy.caption,
                        style = PleadType.caption,
                        color = OnboardingPalette.cocoa.copy(alpha = 0.6f),
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
            }
        },
    )
    // Amendment be: the celebration over the whole screen after the strike (never under Reduce Motion).
    val t = burstT
    if (t != null && !reduceMotion) {
        OathCelebration(t = { burstT ?: 0.0 }, origin = { cardCenter - screenOrigin }, modifier = Modifier.fillMaxSize())
    }
    }
}

// MARK: - Card

@Composable
private fun OathCard(sealed: () -> Float, flash: () -> Float, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    val gold = OnboardingPalette.gold
    Box(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .pleadShadow(OnboardingPalette.cocoa.copy(alpha = 0.08f), radius = 8.dp, y = 3.dp, shape = shape)
                .background(hex(0xFBF0DD), shape)
                .drawWithContent {
                    drawContent()
                    val r = 6.dp.toPx()
                    // Inner rule (inset 4) and outer rule.
                    val inset = 4.dp.toPx()
                    val l1 = 1.dp.toPx()
                    drawRoundRect(
                        gold.copy(alpha = 0.55f), topLeft = Offset(inset + l1 / 2, inset + l1 / 2),
                        size = Size(size.width - 2 * inset - l1, size.height - 2 * inset - l1), cornerRadius = CornerRadius(maxOf(0f, r - inset)), style = Stroke(l1),
                    )
                    val l2 = 1.5.dp.toPx()
                    drawRoundRect(gold.copy(alpha = 0.9f), topLeft = Offset(l2 / 2, l2 / 2), size = Size(size.width - l2, size.height - l2), cornerRadius = CornerRadius(r), style = Stroke(l2))
                    // Corner brackets (12 long, 3 outside the card).
                    val len = 12.dp.toPx()
                    val o = -3.dp.toPx()
                    val path = Path()
                    val corners = listOf(
                        Triple(Offset(o, o), 1f, 1f), Triple(Offset(size.width - o, o), -1f, 1f),
                        Triple(Offset(o, size.height - o), 1f, -1f), Triple(Offset(size.width - o, size.height - o), -1f, -1f),
                    )
                    for ((c, dx, dy) in corners) {
                        path.moveTo(c.x + dx * len, c.y)
                        path.lineTo(c.x, c.y)
                        path.lineTo(c.x, c.y + dy * len)
                    }
                    drawPath(path, gold, style = Stroke(2.dp.toPx()))
                    // The completion flash.
                    val f = flash()
                    if (f > 0f) drawRoundRect(Color.White.copy(alpha = 0.55f * f), cornerRadius = CornerRadius(r))
                }
                .padding(horizontal = 22.dp, vertical = 18.dp)
                .clearAndSetSemantics { contentDescription = "The oath: ${CourtroomOathCopy.oath}" }
                .testTag("onboarding.oath.card"),
        ) {
            Text(
                CourtroomOathCopy.oath,
                style = PleadType.display(17f, weight = FontWeight.Normal, relativeTo = TextStyleKind.body)
                    .let { it.copy(lineHeight = (it.fontSize.value * 1.2f + 3f).sp) },
                color = OnboardingPalette.cocoa,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        CourtSeal(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 2.dp, y = 14.dp)
                .graphicsLayer {
                    val p = sealed()
                    alpha = p.coerceIn(0f, 1f)
                    val s = if (reduceMotion) 1f else 1.6f - 0.6f * p
                    scaleX = s
                    scaleY = s
                    rotationZ = -10f
                },
            size = 40.dp,
        )
    }
}

// MARK: - Button

@Composable
private fun OathHoldButton(
    progress: () -> Float,
    pressed: Boolean,
    reduceMotion: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    onAccessibilityActivate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    val press by animateFloatAsState(
        if (pressed && !reduceMotion) 0.98f else 1f,
        spring(dampingRatio = 0.6f, stiffness = PleadPressStyle.springStiffness(0.3f)),
        label = "oathPress",
    )
    Box(
        modifier
            .graphicsLayer {
                scaleX = press
                scaleY = press
            }
            .pleadShadow(OnboardingPalette.wine.copy(alpha = 0.25f + 0.2f * progress()), radius = (10f + 6f * progress()).dp, y = 5.dp, shape = shape)
            .fillMaxWidth()
            .defaultMinSize(minHeight = 58.dp)
            .clip(shape)
            .drawBehind {
                val p = progress()
                val r = CornerRadius(size.height / 2)
                drawRoundRect(OnboardingPalette.wine, cornerRadius = r)
                // The gold fill sweeping across…
                if (p > 0f) {
                    drawRoundRect(
                        Brush.horizontalGradient(
                            listOf(OnboardingPalette.gold.copy(alpha = 0.55f), OnboardingPalette.goldLight.copy(alpha = 0.75f)),
                            startX = 0f, endX = size.width * p,
                        ),
                        size = Size(size.width * p, size.height), cornerRadius = r,
                    )
                }
            }
            .drawWithContent {
                drawContent()
                val p = progress()
                // …and a gold line tracing the edge.
                if (p > 0f) {
                    val inset = 1.5.dp.toPx() + 1.5.dp.toPx()
                    val path = Path().apply {
                        addRoundRect(RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(size.height / 2 - inset)))
                    }
                    val m = PathMeasure().apply { setPath(path, false) }
                    val seg = Path()
                    m.getSegment(0f, m.length * p, seg, true)
                    drawPath(seg, OnboardingPalette.goldLight, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                }
                val l = 1.dp.toPx()
                drawRoundRect(
                    OnboardingPalette.gold.copy(alpha = 0.45f), topLeft = Offset(l / 2, l / 2), size = Size(size.width - l, size.height - l),
                    cornerRadius = CornerRadius(size.height / 2 - l / 2), style = Stroke(l),
                )
            }
            .pointerInput(Unit) {
                val maxDistance = 60.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    onPress()
                    // Held until lifted, cancelled, or dragged further than 60 dp (Swift's `maximumDistance`).
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val d = change.position - down.position
                        if (hypot(d.x, d.y) > maxDistance) break
                    }
                    onRelease()
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = "Hold to take the oath"
                stateDescription = "Hold for 2 seconds."
                // TalkBack / Switch Access: activation takes the oath (a hold must not block assistive users).
                onClick(label = "Take the oath") {
                    onAccessibilityActivate()
                    true
                }
            }
            .testTag("onboarding.oath.hold")
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            CourtroomOathCopy.button,
            style = PleadType.uiButton.copy(letterSpacing = 0.8.sp, textAlign = TextAlign.Center),
            color = OnboardingPalette.cream,
            maxLines = 2,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

// MARK: - Celebration

/**
 * The oath's celebration (amendment be): gold and burgundy pixel confetti and pixel hearts bursting from the oath card
 * over the whole screen, with gravity and spin, for about a second. Deterministic (seeded with iOS's SplitMix64 and
 * seed), so every run and both platforms match. One Canvas, read in the draw phase; never hit-tests; hidden from
 * TalkBack.
 */
object OathCelebration {
    data class Particle(
        val heart: Boolean,
        /** Index into `colors` (confetti) or `heartColors` (hearts). */
        val color: Int,
        /** Launch direction, radians (0 = right, −π/2 = up). */
        val angle: Double,
        /** dp/s. */
        val speed: Double,
        /** Confetti edge, or heart cell (dp). */
        val size: Float,
        /** deg/s. */
        val spin: Double,
        val life: Double,
        val delay: Double,
        /** Confetti width / height (1 = square, 2 = strip). */
        val aspect: Float,
    )

    const val confettiCount = 64
    const val heartCount = 16
    const val gravity: Double = 980.0
    const val duration: Double = 1.15

    /** Gold, light gold, Court Burgundy, deep wine. */
    val colors = listOf(Color(0xFFCA9858), Color(0xFFF3C76A), Color(0xFF7C3042), Color(0xFF541F2C))
    /** Coral (the logo heart) and Court Burgundy. */
    val heartColors = listOf(Color(0xFFE85E68), Color(0xFF7C3042))

    /** SplitMix64, seeded: the same burst every time (iOS `OathCelebration.Random`). */
    class Random(private var state: ULong) {
        fun next(): Double {
            state += 0x9E3779B97F4A7C15uL
            var z = state
            z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
            z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
            z = z xor (z shr 31)
            return (z shr 11).toDouble() / (1uL shl 53).toDouble()
        }

        fun range(a: Double, b: Double): Double = a + (b - a) * next()
    }

    /**
     * Confetti: launched in an upward fan (−170°…−10°), 450–950 dp/s, 5–8 dp (a third are 2:1 strips), spin ±720°/s,
     * life 0.9–1.15 s, delay 0–0.06 s. Hearts: −155°…−25°, 380–760 dp/s, 2–3 dp cells (the 7 × 6 logo grid → 14–21 dp),
     * spin ±180°/s, life 1.0–1.15 s, delay 0.02–0.1 s. Gravity 980 dp/s²; each fades over the last 35 % of its life.
     */
    val particles: List<Particle> by lazy {
        val r = Random(0x504C454144uL) // "PLEAD"
        val out = mutableListOf<Particle>()
        for (i in 0 until confettiCount) {
            out += Particle(
                heart = false, color = i % colors.size,
                angle = r.range(-170.0, -10.0) * Math.PI / 180, speed = r.range(450.0, 950.0),
                size = r.range(5.0, 9.0).toInt().toFloat(), spin = r.range(-720.0, 720.0),
                life = r.range(0.9, 1.15), delay = r.range(0.0, 0.06), aspect = if (i % 3 == 0) 2f else 1f,
            )
        }
        for (i in 0 until heartCount) {
            out += Particle(
                heart = true, color = i % heartColors.size,
                angle = r.range(-155.0, -25.0) * Math.PI / 180, speed = r.range(380.0, 760.0),
                size = r.range(2.0, 4.0).toInt().toFloat(), spin = r.range(-180.0, 180.0),
                life = r.range(1.0, 1.15), delay = r.range(0.02, 0.1), aspect = 1f,
            )
        }
        out
    }

    /** Where a particle is at `t` seconds into the burst (null = not visible): position (dp), rotation (deg), opacity. */
    fun state(p: Particle, t: Double, origin: Offset): Triple<Offset, Double, Double>? {
        val lt = t - p.delay
        if (lt < 0 || lt > p.life) return null
        val x = origin.x + kotlin.math.cos(p.angle) * p.speed * lt
        val y = origin.y + kotlin.math.sin(p.angle) * p.speed * lt + 0.5 * gravity * lt * lt
        val fadeFrom = p.life * 0.65
        val opacity = if (lt < fadeFrom) 1.0 else maxOf(0.0, 1 - (lt - fadeFrom) / (p.life - fadeFrom))
        return Triple(Offset(x.toFloat(), y.toFloat()), p.spin * lt, opacity)
    }
}

/** [t] seconds into the burst; [origin] the card centre in this composable's space (px). */
@Composable
fun OathCelebration(t: () -> Double, origin: () -> Offset, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.clearAndSetSemantics { }.testTag("onboarding.oath.celebration")) {
        val time = t()
        if (time > OathCelebration.duration + 0.1) return@Canvas
        val d = density
        val o = origin() / d
        for (p in OathCelebration.particles) {
            val (pt, rot, op) = OathCelebration.state(p, time, o) ?: continue
            val cx = Math.round(pt.x * d).toFloat()
            val cy = Math.round(pt.y * d).toFloat()
            withTransform({
                translate(cx, cy)
                rotate(rot.toFloat(), pivot = Offset.Zero)
            }) {
                if (p.heart) {
                    val rows = app.plead.android.designsystem.PleadPixelArt.heart
                    val cell = p.size * d
                    val w = 7 * cell
                    val h = rows.size * cell
                    val fill = OathCelebration.heartColors[p.color]
                    rows.forEachIndexed { ri, row ->
                        row.forEachIndexed { ci, ch ->
                            if (ch == '.') return@forEachIndexed
                            val color = when (ch) {
                                'h' -> Color(0xFFFFFDFC)
                                'd' -> Color(0xFF541F2C)
                                else -> fill
                            }
                            drawRect(color, Offset(ci * cell - w / 2, ri * cell - h / 2), Size(cell + 0.2f * d, cell + 0.2f * d), alpha = op.toFloat())
                        }
                    }
                } else {
                    val w = p.size * p.aspect * d
                    val h = p.size * d
                    drawRect(OathCelebration.colors[p.color], Offset(-w / 2, -h / 2), Size(w, h), alpha = op.toFloat())
                }
            }
        }
    }
}
