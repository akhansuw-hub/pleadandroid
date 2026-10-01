// Port of ArgueWin/Features/Onboarding/CourtPanelScreen.swift. Onboarding redesign 2.0 · Meet the AI Court
// (CONTRACTS-v2 amendment ak, brief §04; on cream per amendment am). The same warm-cream system as every other
// onboarding screen, distinctive through composition only: Judge Wigsworth's pixel sprite behind a small mahogany
// bench, then the three juror roles (Evidence, Consistency, Fairness) on paper cards whose status dots switch on one
// after another. Nothing loops once it has settled.
package app.plead.android.features.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.app.AppModel
import app.plead.android.designsystem.PixelAvatar
import app.plead.android.designsystem.PixelAvatarView
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.pleadShadow
import app.plead.android.models.Avatar
import app.plead.android.models.JudgePersona
import app.plead.android.models.JurorRole
import kotlinx.coroutines.delay
import app.plead.android.courtroom.JudgeSprite

// MARK: - Tokens

/** Every size, colour and timing of the Meet the AI Court screen, in one place. */
object CourtPanelTokens {
    // Colours (on `OnboardingPalette.cream`).
    val text = OnboardingPalette.wine
    val secondaryText = OnboardingPalette.secondaryText
    val accent = OnboardingPalette.burgundy
    val cardFill = OnboardingPalette.paper
    val cardBorder = OnboardingPalette.burgundy.copy(alpha = 0.14f)
    val cardShadow = OnboardingPalette.cocoa.copy(alpha = 0.07f)
    val cardShadowRadius: Dp = 8.dp
    val cardShadowY: Dp = 3.dp
    val benchWood = OnboardingPalette.mahogany
    val benchShade = OnboardingPalette.cocoa.copy(alpha = 0.35f)
    val benchTrim = OnboardingPalette.gold
    val floorShadow = OnboardingPalette.cocoa.copy(alpha = 0.10f)
    val lightOn = OnboardingPalette.gold
    val lightRing = OnboardingPalette.gold.copy(alpha = 0.28f)
    val lightOff = OnboardingPalette.border

    // Judge hero. The sprite is drawn at an integer cell so every art pixel is a whole number of points.
    const val judgeCell: Float = 4f
    val benchHeight: Dp = 30.dp
    val benchWidth: Dp = 164.dp
    val benchTrimHeight: Dp = 2.dp
    val benchBaseHeight: Dp = 4.dp
    val benchRadius: Dp = 4.dp
    val benchSeal: Dp = 20.dp
    /** How far the bench covers the judge's robe (in art pixels). */
    const val benchOverlapCells: Float = 4f
    val floorShadowWidth: Dp = 200.dp
    val floorShadowHeight: Dp = 12.dp
    val floorShadowBlur: Dp = 5.dp

    // Juror cards.
    val portraitTile: Dp = 44.dp
    const val portraitScale = 2           // 16-px avatar → 32 pt
    val portraitRadius: Dp = 12.dp
    const val roleTracking: Float = 1.2f
    val lightSize: Dp = 8.dp
    val lightRingWidth: Dp = 3.dp
    val cardRadius: Dp = OnboardingKitTokens.Radius.card

    // Motion (amendment ak: hero → headline → copy → content → CTA, 100–150 ms stagger, 300–450 ms).
    const val judgeAt: Double = 0.0
    const val judgeDuration: Double = 0.44
    const val headlineAt: Double = 0.24
    const val copyAt: Double = 0.36
    const val firstJurorAt: Double = 0.52
    const val jurorStagger: Double = 0.12
    const val jurorDuration: Double = 0.38
    /** A juror's dot switches on shortly after its card has settled. */
    const val lightGap: Double = 0.06
    const val lightDuration: Double = 0.3
    const val rulingGap: Double = 0.1
    const val ctaGap: Double = 0.12
}

/** The screen's entrance as absolute start times (seconds from screen entry). Pure, so it is unit-tested. */
object CourtPanelTimeline {
    private val T = CourtPanelTokens
    val judge: Double = T.judgeAt
    val headline: Double = T.headlineAt
    val copy: Double = T.copyAt
    fun juror(i: Int): Double = T.firstJurorAt + T.jurorStagger * i
    fun jurorSettled(i: Int): Double = juror(i) + T.jurorDuration
    fun light(i: Int): Double = jurorSettled(i) + T.lightGap
    fun ruling(jurors: Int): Double = light(jurors - 1) + T.rulingGap
    fun cta(jurors: Int): Double = ruling(jurors) + T.ctaGap
}

// MARK: - Content

/** One juror seat on the panel: the role, the question it asks and the pixel juror who holds it. */
data class CourtJuror(val role: JurorRole, val line: String, val portrait: Avatar) {
    val id: JurorRole get() = role
    val title: String get() = role.mandate
    val accessibilityLabel: String get() = "${role.mandate} juror. $line"
}

// MARK: - Screen

/**
 * Onboarding step `.aiCourt`. The headline is the one the UI tests look for, the CTA the standard CONTINUE
 * (amendment am).
 */
object CourtPanelScreen {
    const val eyebrow = "Meet the AI Court"
    const val headline = "One judge. Multiple opinions."
    const val copy = "Multiple AI jurors review both sides before Judge Wigsworth gives the final ruling."
    const val rulingLine = "Judge Wigsworth considers their findings and delivers the final ruling."
    const val cta = "Continue"

    /** Evidence → Consistency → Fairness, the order the court activates them in. */
    val jurors: List<CourtJuror> = listOf(
        CourtJuror(JurorRole.evidence, "What do the receipts actually prove?",
            Avatar(skin = 2, hair = 0, hairstyle = Avatar.Hairstyle.short, top = 7, outfit = Avatar.Outfit.suit)),
        CourtJuror(JurorRole.consistency, "Whose story holds together?",
            Avatar(skin = 0, hair = 3, hairstyle = Avatar.Hairstyle.bun, top = 0, outfit = Avatar.Outfit.suit)),
        CourtJuror(JurorRole.fairness, "What would be a reasonable outcome?",
            Avatar(skin = 4, hair = 4, hairstyle = Avatar.Hairstyle.buzz, top = 6, outfit = Avatar.Outfit.suit)),
    )
}

@Composable
fun CourtPanelScreen(app: AppModel) {
    val S = OnboardingKitTokens.Spacing
    val n = CourtPanelScreen.jurors.size
    OnboardingShell(
        dark = false,
        spacing = S.section,
        hero = {
            JudgeHero(
                Modifier.courtPanelReveal(
                    PleadRevealKind.card, at = CourtPanelTimeline.judge,
                    scale = OnboardingMotionTokens.judgeStartScale, duration = CourtPanelTokens.judgeDuration,
                ),
            )
        },
        content = {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(S.tight)) {
                    CourtEyebrow(CourtPanelScreen.eyebrow, Modifier.courtPanelReveal(PleadRevealKind.headline, at = CourtPanelTimeline.headline))
                    CourtHeadline(CourtPanelScreen.headline, Modifier.courtPanelReveal(PleadRevealKind.headline, at = CourtPanelTimeline.headline))
                    CourtSupportingText(CourtPanelScreen.copy, Modifier.courtPanelReveal(PleadRevealKind.body, at = CourtPanelTimeline.copy))
                }
            }
            item { CourtPanel(CourtPanelScreen.jurors) }
            item {
                Text(
                    CourtPanelScreen.rulingLine,
                    style = PleadType.display(17f, weight = FontWeight.Medium, italic = true, relativeTo = TextStyleKind.body),
                    color = CourtPanelTokens.text,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("onboarding.court.ruling")
                        .courtPanelReveal(PleadRevealKind.body, at = CourtPanelTimeline.ruling(n)),
                )
            }
        },
        cta = {
            CourtPrimaryButton(
                title = CourtPanelScreen.cta,
                modifier = Modifier.courtPanelReveal(PleadRevealKind.cta, at = CourtPanelTimeline.cta(n)),
                identifier = "onboarding.primary",
            ) { app.onboardingModel.advance() }
        },
    )
}

// MARK: - Components

/** The juror bench: one `JurorRoleCard` per seat, each switching on in turn. */
@Composable
fun CourtPanel(jurors: List<CourtJuror>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(OnboardingKitTokens.Spacing.stack)) {
        jurors.forEachIndexed { i, juror ->
            JurorRoleCard(
                juror,
                Modifier.courtPanelReveal(PleadRevealKind.card, at = CourtPanelTimeline.juror(i), duration = CourtPanelTokens.jurorDuration),
                lightDelay = CourtPanelTimeline.light(i),
            )
        }
    }
}

/**
 * Judge Wigsworth at the bench: the real pixel sprite (integer scale) behind a small mahogany bench with a thin
 * gold trim and the scales seal, sitting directly on the cream over a soft warm floor shadow. One TalkBack element.
 */
object JudgeHero {
    const val role = "Hears every juror, then gives the final ruling."

    fun accessibilityLabel(persona: JudgePersona): String = "${persona.displayName}, the presiding judge. $role"
}

@Composable
fun JudgeHero(modifier: Modifier = Modifier, persona: JudgePersona = JudgePersona.wigsworth) {
    val T = CourtPanelTokens
    Box(
        modifier
            .fillMaxWidth()
            .padding(bottom = T.floorShadowHeight / 2)
            .testTag("onboarding.court.judge")
            .clearAndSetSemantics { contentDescription = JudgeHero.accessibilityLabel(persona) },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier
                .offset(y = T.floorShadowHeight / 2)
                .size(T.floorShadowWidth, T.floorShadowHeight)
                .blur(T.floorShadowBlur)
                .background(T.floorShadow, androidx.compose.foundation.shape.GenericShape { size, _ -> addOval(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height)) }),
        )
        JudgeSprite(persona, Modifier.padding(bottom = T.benchHeight - (T.benchOverlapCells * T.judgeCell).dp), cell = T.judgeCell)
        // The bench.
        Column(
            Modifier
                .size(T.benchWidth, T.benchHeight)
                .clip(RoundedCornerShape(topStart = T.benchRadius, topEnd = T.benchRadius)),
        ) {
            Box(Modifier.fillMaxWidth().height(T.benchTrimHeight).background(T.benchTrim))
            Box(Modifier.fillMaxWidth().weight(1f).background(T.benchWood), contentAlignment = Alignment.Center) {
                CourtSeal(size = T.benchSeal)
            }
            Box(Modifier.fillMaxWidth().height(T.benchBaseHeight).background(T.benchWood).background(T.benchShade))
        }
    }
}

/**
 * One juror role on paper: pixel juror, role in tracked caps, the question it asks, and a status dot that
 * switches on once (decorative). One TalkBack element.
 */
@Composable
fun JurorRoleCard(
    juror: CourtJuror,
    modifier: Modifier = Modifier,
    /** When the status dot switches on (seconds from appearance); `null` = already on. */
    lightDelay: Double? = null,
) {
    val T = CourtPanelTokens
    val S = OnboardingKitTokens.Spacing
    val accessibilitySize = LocalDensity.current.fontScale >= 1.6f
    val shape = RoundedCornerShape(T.cardRadius)
    Row(
        modifier
            .fillMaxWidth()
            .pleadShadow(T.cardShadow, radius = T.cardShadowRadius, y = T.cardShadowY, shape = shape)
            .background(T.cardFill, shape)
            .border(OnboardingKitTokens.Size.hairline, T.cardBorder, shape)
            .padding(horizontal = S.cardPadding, vertical = S.cardInner + 2.dp)
            .testTag("onboarding.court.juror.${juror.role.rawValue}")
            .clearAndSetSemantics { contentDescription = juror.accessibilityLabel },
        horizontalArrangement = Arrangement.spacedBy(S.cardInner),
        verticalAlignment = if (accessibilitySize) Alignment.Top else Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(T.portraitTile)
                .clip(RoundedCornerShape(T.portraitRadius))
                .background(OnboardingPalette.parchment)
                .border(1.dp, OnboardingPalette.gold.copy(alpha = 0.6f), RoundedCornerShape(T.portraitRadius)),
            contentAlignment = Alignment.Center,
        ) {
            PixelAvatarView(juror.portrait, Modifier.offset(y = PleadSpacing.xs), size = (PixelAvatar.side * T.portraitScale).dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                juror.title.uppercase(),
                style = PleadType.ui(13f, FontWeight.ExtraBold, relativeTo = TextStyleKind.footnote).copy(letterSpacing = T.roleTracking.sp),
                color = T.accent,
            )
            Text(
                juror.line,
                style = PleadType.text(15f, FontWeight.Medium, relativeTo = TextStyleKind.subheadline),
                color = T.text,
            )
        }
        JurorStatusLight(delay = lightDelay)
    }
}

/** A tiny gold dot that switches on once and stays on (no pulsing). Decorative. */
@Composable
fun JurorStatusLight(modifier: Modifier = Modifier, delay: Double? = null) {
    val T = CourtPanelTokens
    val on = remember { Animatable(if (delay == null) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (on.value == 1f || delay == null) return@LaunchedEffect
        if (delay > 0) delay(delay.millis().toLong())
        on.animateTo(1f, tween(T.lightDuration.millis(), easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)))
    }
    val v = on.value
    Box(
        modifier
            .background(lerp(Color.Transparent, T.lightRing, v), CircleShape)
            .padding(T.lightRingWidth)
            .size(T.lightSize)
            .background(lerp(T.lightOff, T.lightOn, v), CircleShape)
            .clearAndSetSemantics { },
    )
}

// MARK: - Reveal at an absolute time

/**
 * `pleadReveal` of `kind`, starting at `at` seconds from screen entry (instead of the kind's own delay).
 * Keeps the shared grammar: one-shot per entry, opacity-only under Reduce Motion.
 */
fun Modifier.courtPanelReveal(kind: PleadRevealKind, at: Double, scale: Float? = null, duration: Double? = null): Modifier {
    val base = PleadRevealParameters.make(kind, reduceMotion = false).delay
    return pleadReveal(kind, scale = scale, duration = duration, delay = at - base)
}
