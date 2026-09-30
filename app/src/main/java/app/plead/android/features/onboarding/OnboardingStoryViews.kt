// Port of ArgueWin/Features/Onboarding/OnboardingStoryViews.swift. Screens 1 and 4 of the redesign (amendment ak):
// story before forms. No auth, no permissions.
package app.plead.android.features.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.plead.android.app.AppModel
import app.plead.android.designsystem.PixelAvatarView
import app.plead.android.designsystem.PleadLogo
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.designsystem.pleadShadow
import app.plead.android.designsystem.swiftSpring
import app.plead.android.models.Avatar
import app.plead.android.models.JudgePersona
import app.plead.android.models.JurorRole
import kotlinx.coroutines.delay
import app.plead.android.features.paywall.PixelGlyph
import app.plead.android.features.paywall.PaywallSprites
import app.plead.android.courtroom.JudgeSprite

// MARK: 1 · Welcome

/**
 * Amendment ak / redesign brief image1: a compact logo block, the Fraunces hero line, three miniature case
 * documents (File the Case → Plead Both Sides → Get a Verdict) and ENTER THE COURT. Assembles in layers:
 * logo (its pixel heart pulses once) → headline → copy → the three documents one after another → CTA.
 * Reduce Motion: fades only, no pulse.
 */
object OnboardingWelcomeView {
    const val headline = "Settle arguments.\nLet AI judge."
    const val supporting = "Turn any disagreement into a case. Present both sides and let the court decide."
    const val cta = "ENTER THE COURT"
    /** Kept from the previous Welcome so UI tests keep working. */
    const val ctaIdentifier = "onboarding.begin"

    data class Step(val title: String, val subtitle: String, val icon: CourtKitIcon, val color: Color)

    val steps: List<Step> = listOf(
        Step("File the Case", "Tell the court what happened.", CourtKitIcon.system("doc.text"), OnboardingPalette.burgundy),
        Step("Plead Both Sides", "You and your partner make your case.", CourtKitIcon.system("bubble.left.and.bubble.right.fill"), OnboardingPalette.rose),
        Step("Get a Verdict", "The AI court decides.", CourtKitIcon.scales, OnboardingPalette.gold),
    )

    /** "STEP 1 · TAKES LESS THAN A MINUTE". */
    val footnote: String get() = "STEP ${OnboardingStep.welcome.position()} · TAKES LESS THAN A MINUTE"
    val footnoteSpoken: String
        get() = "Step ${OnboardingStep.welcome.position()} of ${OnboardingStep.activeSteps().size}. Takes less than a minute."
}

@Composable
fun OnboardingWelcomeView(app: AppModel) {
    val S = OnboardingKitTokens.Spacing
    val M = OnboardingKitTokens.Motion
    val reduceMotion = accessibilityReduceMotion()
    val heart = remember { Animatable(1f) }
    var pulsed by rememberSaveable { mutableStateOf(false) }
    // The pixel heart pulses once as the logo lands (never under Reduce Motion, never again on this entry).
    LaunchedEffect(Unit) {
        if (pulsed || reduceMotion) return@LaunchedEffect
        pulsed = true
        delay(M.duration.millis().toLong())
        heart.animateTo(M.heartPulseScale.toFloat(), tween(M.heartPulseUp.millis(), easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)))
        heart.animateTo(1f, swiftSpring(M.heartPulseDown.toFloat(), 0.35f))
    }
    OnboardingShell(
        autoLayers = true,
        spacing = S.stack,
        hero = {
            PleadLogo(strapline = true, width = OnboardingKitTokens.Size.welcomeLogo, heartScale = heart.value.toDouble())
        },
        content = {
            item { CourtHeadline(OnboardingWelcomeView.headline, Modifier.padding(top = S.tight), size = CourtHeadline.Size.display) }
            item { CourtSupportingText(OnboardingWelcomeView.supporting, Modifier.padding(bottom = S.tight)) }
            items(OnboardingWelcomeView.steps) { i, step ->
                CaseDocumentCard(number = i + 1, title = step.title, subtitle = step.subtitle, icon = step.icon, iconColor = step.color)
            }
        },
        cta = {
            CourtPrimaryButton(title = OnboardingWelcomeView.cta, identifier = OnboardingWelcomeView.ctaIdentifier) { app.onboardingModel.advance() }
            Text(
                OnboardingWelcomeView.footnote,
                style = PleadType.caption.copy(letterSpacing = 0.6.sp),
                color = OnboardingPalette.cocoa.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = S.ctaStack)
                    .clearAndSetSemantics { contentDescription = OnboardingWelcomeView.footnoteSpoken },
            )
        },
    )
}

/** Faint concentric court arches (decoration only; kept for other screens). `center` / `radius` in dp. */
@Composable
fun CourtArches(center: Offset, radius: Dp, modifier: Modifier = Modifier, rings: Int = 6, spacing: Dp = 5.dp) {
    Canvas(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .clearAndSetSemantics { },
    ) {
        val c = Offset(center.x.dp.toPx(), center.y.dp.toPx())
        for (i in 0 until rings) {
            val r = radius.toPx() + i * spacing.toPx()
            drawCircle(OnboardingPalette.wine.copy(alpha = 0.06f), radius = r, center = c, style = Stroke(1.dp.toPx()))
        }
        // Mask: opaque at the top, gone by 42 % of the height.
        drawRect(
            Brush.verticalGradient(0f to Color.Black, 0.18f to Color.Black, 0.42f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }
}

// MARK: 4 · How Plead works

/**
 * Amendment ak / brief image2: the product loop as three case folders stacked on the desk, each with its own
 * icon and accent, very slightly overlapping and tilted (≤ 1.5°, none under Reduce Motion). Eyebrow → headline →
 * copy, then the folders rise onto the desk one by one with a soft spring, then the CTA. Nothing loops.
 */
object HowPleadWorksView {
    const val eyebrow = "How Plead works"
    const val headline = "Three steps to a verdict."
    const val supporting = "Every case follows the same simple court structure."

    val steps: List<Pair<String, String>> = listOf(
        "File your case" to "Explain what happened and what you want the court to decide.",
        "Both sides plead" to "You and your partner each tell the court your side.",
        "Court decides" to "AI jurors review the case before Judge Wigsworth rules.",
    )

    data class Look(val icon: CourtKitIcon, val accent: Color, val tilt: Double)

    /** Icon, accent and tilt per folder. */
    val looks: List<Look> = listOf(
        Look(CourtKitIcon.system("doc.text.fill"), OnboardingPalette.burgundy, -1.0),
        Look(CourtKitIcon.system("bubble.left.and.bubble.right.fill"), OnboardingPalette.rose, 0.8),
        Look(CourtKitIcon.scales, OnboardingPalette.gold, -0.5),
    )

    /** Layers: eyebrow 0 · headline 1 · copy 2 · folders 3… · CTA last. */
    const val firstFolderLayer = 3
    val ctaLayer: Int get() = firstFolderLayer + steps.size
}

@Composable
fun HowPleadWorksView(app: AppModel) {
    val S = OnboardingKitTokens.Spacing
    OnboardingShell(
        centered = true,
        spacing = S.stack,
        ctaLayer = HowPleadWorksView.ctaLayer,
        hero = { CourtEyebrow(HowPleadWorksView.eyebrow, Modifier.courtLayer(0)) },
        content = {
            item { CourtHeadline(HowPleadWorksView.headline, Modifier.courtLayer(1)) }
            item { CourtSupportingText(HowPleadWorksView.supporting, Modifier.courtLayer(2).padding(bottom = S.section)) }
            item {
                Column(Modifier.fillMaxWidth().padding(bottom = S.section), verticalArrangement = Arrangement.spacedBy(-S.folderOverlap)) {
                    HowPleadWorksView.steps.forEachIndexed { i, s ->
                        val look = HowPleadWorksView.looks[i]
                        CourtLayer(HowPleadWorksView.firstFolderLayer + i, Modifier.zIndex(i.toFloat()), style = CourtLayerStyle.spring) {
                            CaseStepCard(
                                number = i + 1, systemImage = "", title = s.first, description = s.second,
                                accent = look.accent, tilt = look.tilt, icon = look.icon,
                            )
                        }
                    }
                }
            }
        },
        cta = { CourtPrimaryButton(title = "Continue") { app.onboardingModel.advance() } },
    )
}

/** Gold numbered marker (screen 2). */
@Composable
fun NumberMarker(number: Int, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(OnboardingRadius.marker)
    Box(
        modifier
            .size(40.dp)
            .background(OnboardingPalette.goldLight, shape)
            .border(1.dp, OnboardingPalette.gold.copy(alpha = 0.5f), shape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$number",
            style = TextStyle(fontSize = TextStyleKind.title3.defaultSize.sp, fontWeight = FontWeight.ExtraBold),
            color = OnboardingPalette.wine,
        )
    }
}

/** Modern paper-white card (screens 2–3): hairline, soft elevation, 20 pt corners. */
fun Modifier.onboardingCard(padding: Dp = PleadSpacing.l, fill: Color = OnboardingPalette.paper): Modifier {
    val shape = RoundedCornerShape(OnboardingRadius.card)
    return this
        .fillMaxWidth()
        .pleadShadow(OnboardingPalette.cocoa.copy(alpha = 0.05f), radius = 10.dp, y = 3.dp, shape = shape)
        .background(fill, shape)
        .border(1.dp, OnboardingPalette.border, shape)
        .padding(padding)
}

// MARK: 3 · Meet the AI court

/** The question each juror answers (onboarding screen 3). */
val JurorRole.onboardingQuestion: String
    get() = when (this) {
        JurorRole.evidence -> "What actually supports each side?"
        JurorRole.consistency -> "Whose story holds up?"
        JurorRole.fairness -> "What outcome is reasonable?"
    }

/** Pixel juror portrait for the card (a distinct court officer per mandate). */
val JurorRole.onboardingPortrait: Avatar
    get() = when (this) {
        JurorRole.evidence -> Avatar(skin = 2, hair = 0, hairstyle = Avatar.Hairstyle.short, top = 7, outfit = Avatar.Outfit.suit)
        JurorRole.consistency -> Avatar(skin = 0, hair = 3, hairstyle = Avatar.Hairstyle.bun, top = 0, outfit = Avatar.Outfit.suit)
        JurorRole.fairness -> Avatar(skin = 4, hair = 4, hairstyle = Avatar.Hairstyle.buzz, top = 6, outfit = Avatar.Outfit.suit)
    }

/** Judge Wigsworth at the bench: the pixel judge in a parchment medallion with a gold rim. */
@Composable
fun PresidingJudge(modifier: Modifier = Modifier) {
    Column(
        modifier.clearAndSetSemantics { contentDescription = "Presiding: Judge Wigsworth" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs + 2.dp),
    ) {
        Box(
            Modifier
                .size(84.dp)
                .pleadShadow(OnboardingPalette.cocoa.copy(alpha = 0.10f), radius = 8.dp, y = 3.dp, shape = CircleShape)
                .clip(CircleShape)
                .background(OnboardingPalette.parchment)
                .border(2.dp, OnboardingPalette.gold, CircleShape),
            contentAlignment = Alignment.BottomCenter,
        ) {
            JudgeSprite(JudgePersona.wigsworth, Modifier.offset(y = 6.dp), cell = 3.5f)
        }
        Text(
            "PRESIDING · JUDGE WIGSWORTH",
            style = TextStyle(fontSize = TextStyleKind.caption2.defaultSize.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp),
            color = OnboardingPalette.mahogany,
        )
    }
}

/** Small pixel juror: head-and-shoulders in a parchment circle with a tiny gold scales badge. */
@Composable
fun JurorPortrait(avatar: Avatar, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    Box(modifier.size(size).clearAndSetSemantics { }) {
        Box(
            Modifier.size(size).clip(CircleShape).background(OnboardingPalette.parchment).border(1.5.dp, OnboardingPalette.gold, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            PixelAvatarView(avatar, Modifier.offset(y = size * 0.2f), size = size * 1.05f, outlined = true)
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 4.dp, y = 3.dp)
                .background(OnboardingPalette.paper, CircleShape)
                .border(1.dp, OnboardingPalette.gold.copy(alpha = 0.6f), CircleShape)
                .padding(3.dp),
        ) {
            PixelGlyph(PaywallSprites.scales, Modifier.size(16.dp, 12.dp))
        }
    }
}

// MARK: 4 · Example cases

/** A non-interactive court file in the Cases parchment style, with a small docket number. */
@Composable
fun ExampleCourtFile(
    number: Int,
    title: String,
    question: String,
    modifier: Modifier = Modifier,
    /** The top file of the docket gets the OPEN CASE stamp, in its own column so it never covers the copy. */
    stampDelay: Double? = null,
) {
    val shape = RoundedCornerShape(OnboardingRadius.card)
    Box(
        modifier
            .fillMaxWidth()
            .pleadShadow(OnboardingPalette.cocoa.copy(alpha = 0.05f), radius = 8.dp, y = 2.dp, shape = shape)
            .clip(shape)
            .background(OnboardingPalette.parchment, shape)
            // Burgundy file tab down the spine.
            .drawBehind { drawRect(OnboardingPalette.burgundy.copy(alpha = 0.85f), size = androidx.compose.ui.geometry.Size(5.dp.toPx(), size.height)) }
            .border(1.dp, OnboardingPalette.mahogany.copy(alpha = 0.2f), shape)
            .clearAndSetSemantics { contentDescription = "Example: ${title.swiftCapitalized()}. $question" },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = PleadSpacing.l, vertical = PleadSpacing.m + 2.dp),
            horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    title,
                    style = TextStyle(fontSize = TextStyleKind.subheadline.defaultSize.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp),
                    color = OnboardingPalette.wine,
                )
                Text(
                    question,
                    style = TextStyle(fontSize = TextStyleKind.subheadline.defaultSize.sp, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic),
                    color = OnboardingPalette.cocoa.copy(alpha = 0.78f),
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                Text(
                    "No. %03d".format(number),
                    style = TextStyle(fontSize = TextStyleKind.caption2.defaultSize.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
                    color = OnboardingPalette.mahogany.copy(alpha = 0.8f),
                )
                if (stampDelay != null) PleadStamp("OPEN CASE", stampDelay, Modifier.padding(top = 2.dp))
            }
        }
    }
}
