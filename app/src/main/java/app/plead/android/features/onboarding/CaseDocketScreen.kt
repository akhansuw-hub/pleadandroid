// Port of ArgueWin/Features/Onboarding/CaseDocketScreen.swift. Onboarding redesign 2.0 · Example Cases
// (CONTRACTS-v2 amendment ak, brief §05, image5). A miniature docket feed of `CasePreviewCard`s: the files slide in
// one after another, each status stamp pops once its file has settled, and the last file sits partly under a fade so
// the feed reads as scrollable.
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.app.AppModel
import app.plead.android.designsystem.CourtFilePalette
import app.plead.android.designsystem.PixelAvatar
import app.plead.android.designsystem.PixelAvatarView
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.designsystem.pleadShadow
import app.plead.android.designsystem.swiftSpring
import app.plead.android.models.Avatar
import kotlinx.coroutines.delay

// MARK: - Tokens

/** Every size, colour and timing of the docket and its cards, in one place. */
object CaseDocketTokens {
    // Card (same paper / border / radius family as the app's `CaseFileCard`).
    val paper = CourtFilePalette.paper
    val border = CourtFilePalette.border
    val radius: Dp = PleadRadius.card
    val shadow = CourtFilePalette.wine.copy(alpha = 0.06f)
    val caption = CourtFilePalette.mahogany
    val title = CourtFilePalette.wine
    val question = PleadColor.cocoa.copy(alpha = 0.82f)
    val chipFill = PleadColor.parchment
    val chipText = CourtFilePalette.mahogany

    // Avatars: two 16-px pixel people at 2x on coloured discs, overlapping slightly.
    const val avatarScale = 2
    val avatarDisc: Dp = 36.dp
    val avatarOverlap: Dp = 10.dp
    val avatarDiscs: Pair<Color, Color> = PleadColor.blush to PleadColor.gold

    // Stamp.
    const val stampRotation: Float = -6f
    val stampBorder: Dp = 1.5.dp
    val stampRadius: Dp = 4.dp
    const val stampStartScale: Float = OnboardingMotionTokens.stampStartScale
    const val stampDuration: Double = 0.22
    /** The stamp lands this long after its file has settled. */
    const val stampGap: Double = 0.04

    // Feed.
    val cardSpacing: Dp = PleadSpacing.m
    /** The fade over the last file (smaller at accessibility sizes so more of it stays readable). */
    val fadeHeight: Dp = 72.dp
    val fadeHeightAccessibility: Dp = 36.dp
    const val ctaGap: Double = 0.08
}

// MARK: - Model

/** A case as the docket preview shows it. Static content: nothing here is a real case. */
data class CasePreview(
    val number: Int,
    val title: String,
    val question: String,
    val category: String,
    val status: Status,
    val plaintiff: Avatar,
    val defendant: Avatar,
) {
    enum class Status(val rawValue: String) {
        filed("filed"), inTrial("inTrial"), deliberating("deliberating"), verdict("verdict"), settled("settled");

        val stamp: String
            get() = when (this) {
                filed -> "FILED"
                inTrial -> "IN TRIAL"
                deliberating -> "DELIBERATING"
                verdict -> "VERDICT"
                settled -> "SETTLED"
            }
        val spoken: String
            get() = when (this) {
                filed -> "Filed"
                inTrial -> "In trial"
                deliberating -> "Deliberating"
                verdict -> "Verdict"
                settled -> "Settled"
            }
        val color: Color
            get() = when (this) {
                filed -> CourtFilePalette.mahogany
                inTrial -> CourtFilePalette.burgundy
                deliberating -> PleadColor.walnut
                verdict -> PleadColor.success
                settled -> PleadColor.success
            }
    }

    val id: Int get() = number

    /** "CASE #016" */
    val caseLabel: String get() = "CASE #%03d".format(number)

    /** "Case 16, The Spoiler. Is a meaningful look a spoiler? TV & film. In trial." */
    val accessibilityLabel: String get() = "Case $number, $title. $question $category. ${status.spoken}."

    companion object {
        /** Light, everyday disputes only (brief §05): nothing about abuse, threats, self-harm or legal matters. */
        val onboardingExamples: List<CasePreview> = listOf(
            CasePreview(
                16, "The Spoiler", "Is a meaningful look a spoiler?", "TV & film", Status.inTrial,
                Avatar(skin = 1, hair = 2, hairstyle = Avatar.Hairstyle.ponytail, top = 0, outfit = Avatar.Outfit.hoodie),
                Avatar(skin = 3, hair = 0, hairstyle = Avatar.Hairstyle.curly, top = 5, outfit = Avatar.Outfit.shirt),
            ),
            CasePreview(
                21, "The Late Reply", "Is 6 hours too long to text back?", "Texting", Status.deliberating,
                Avatar(skin = 4, hair = 1, hairstyle = Avatar.Hairstyle.short, top = 5, outfit = Avatar.Outfit.tee),
                Avatar(skin = 0, hair = 3, hairstyle = Avatar.Hairstyle.long, top = 2, outfit = Avatar.Outfit.dress),
            ),
            CasePreview(
                14, "The Last Slice", "Was the last slice fair game?", "Food", Status.verdict,
                Avatar(skin = 2, hair = 4, hairstyle = Avatar.Hairstyle.bun, top = 6, outfit = Avatar.Outfit.shirt),
                Avatar(skin = 5, hair = 0, hairstyle = Avatar.Hairstyle.buzz, top = 1, outfit = Avatar.Outfit.hoodie),
            ),
        )
    }
}

// MARK: - Screen

/**
 * Onboarding step `.examples`. Advances exactly like the previous `ExampleCasesView` (same headline and CTA the UI
 * tests drive).
 */
object CaseDocketScreen {
    const val eyebrow = "Example cases"
    const val headline = "What's going to court first?"
    const val copy = "Everyday disagreements become clean little case files."
    const val cta = "Continue"
    val cases: List<CasePreview> = CasePreview.onboardingExamples

    /** Each file enters as a card, one `cardStagger` apart; its stamp lands once it has settled. */
    fun stampDelay(i: Int): Double = PleadRevealParameters.make(PleadRevealKind.card, index = i, reduceMotion = false).end + CaseDocketTokens.stampGap

    val ctaAt: Double get() = stampDelay(cases.size - 1) + CaseDocketTokens.stampDuration + CaseDocketTokens.ctaGap
}

@Composable
fun CaseDocketScreen(app: AppModel) {
    val accessibilitySize = LocalDensity.current.fontScale >= 1.6f
    OnboardingShell(
        hero = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                Text(
                    CaseDocketScreen.eyebrow.uppercase(),
                    style = PleadType.labelCapsTracked,
                    color = OnboardingPalette.burgundy,
                    modifier = Modifier.pleadReveal(PleadRevealKind.headline),
                )
                Text(
                    CaseDocketScreen.headline,
                    style = PleadType.displayXL,
                    color = OnboardingPalette.wine,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.pleadReveal(PleadRevealKind.headline).semantics { heading() }.testTag("onboarding.title"),
                )
                Text(
                    CaseDocketScreen.copy,
                    style = PleadType.body,
                    color = OnboardingPalette.secondaryText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.pleadReveal(PleadRevealKind.body),
                )
            }
        },
        content = {
            item {
                Box(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CaseDocketTokens.cardSpacing)) {
                        CaseDocketScreen.cases.forEachIndexed { i, preview ->
                            CasePreviewCard(preview, Modifier.pleadReveal(PleadRevealKind.card, index = i), stampDelay = CaseDocketScreen.stampDelay(i))
                        }
                    }
                    // The last file runs under a soft fade into the page, so the docket reads as a feed that continues.
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(if (accessibilitySize) CaseDocketTokens.fadeHeightAccessibility else CaseDocketTokens.fadeHeight)
                            .background(Brush.verticalGradient(listOf(OnboardingPalette.cream.copy(alpha = 0f), OnboardingPalette.cream)))
                            .clearAndSetSemantics { },
                    )
                }
            }
        },
        cta = {
            CourtPrimaryButton(
                title = CaseDocketScreen.cta,
                modifier = Modifier.pleadReveal(
                    PleadRevealKind.cta,
                    delay = CaseDocketScreen.ctaAt - PleadRevealParameters.make(PleadRevealKind.cta, reduceMotion = false).delay,
                ),
                identifier = "onboarding.primary",
            ) { app.onboardingModel.advance() }
        },
    )
}

// MARK: - Case preview card

/**
 * A compact, non-interactive case file: number, serif title, the question in one line, a category chip, a
 * tiny status stamp and the two parties' pixel avatars. One TalkBack element. Reusable in empty states and
 * marketing; pass `stampDelay` to have the stamp pop in once (null = already stamped).
 */
@Composable
fun CasePreviewCard(preview: CasePreview, modifier: Modifier = Modifier, stampDelay: Double? = null) {
    val T = CaseDocketTokens
    val accessibilitySize = LocalDensity.current.fontScale >= 1.6f
    val shape = RoundedCornerShape(T.radius)
    Column(
        modifier
            .fillMaxWidth()
            .pleadShadow(T.shadow, radius = 8.dp, y = 2.dp, shape = shape)
            .background(T.paper, shape)
            .border(1.dp, T.border, shape)
            .padding(PleadSpacing.l)
            .clearAndSetSemantics { contentDescription = preview.accessibilityLabel }
            .testTag("casepreview.${preview.number}"),
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.s),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(preview.caseLabel, style = PleadType.labelCapsTracked, color = T.caption)
                Text(preview.title, style = PleadType.displayM, color = T.title)
            }
            CaseStatusStamp(preview.status, delay = stampDelay)
        }
        Text("“${preview.question}”", style = PleadType.body, color = T.question)
        if (accessibilitySize) {
            Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                PreviewChip(preview)
                PreviewAvatars(preview)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PreviewChip(preview)
                Spacer(Modifier.weight(1f).width(PleadSpacing.s))
                PreviewAvatars(preview)
            }
        }
    }
}

@Composable
private fun PreviewChip(preview: CasePreview) {
    Text(
        preview.category.uppercase(),
        style = PleadType.labelCapsTracked,
        color = CaseDocketTokens.chipText,
        modifier = Modifier
            .background(CaseDocketTokens.chipFill, RoundedCornerShape(50))
            .padding(horizontal = PleadSpacing.m, vertical = PleadSpacing.xs + 2.dp),
    )
}

@Composable
private fun PreviewAvatars(preview: CasePreview) {
    val T = CaseDocketTokens
    Row(Modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(-T.avatarOverlap)) {
        Disc(preview.plaintiff, T.avatarDiscs.first)
        Disc(preview.defendant, T.avatarDiscs.second)
    }
}

@Composable
private fun Disc(avatar: Avatar, color: Color) {
    val T = CaseDocketTokens
    Box(
        Modifier.size(T.avatarDisc).clip(CircleShape).background(color).border(2.dp, T.paper, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        PixelAvatarView(avatar, Modifier.offset(y = PleadSpacing.xs), size = (PixelAvatar.side * T.avatarScale).dp)
    }
}

/** A tiny rubber status stamp that lands once at `delay` (scale 1.35 → 1; a fade under Reduce Motion). */
@Composable
fun CaseStatusStamp(status: CasePreview.Status, modifier: Modifier = Modifier, delay: Double? = null) {
    val T = CaseDocketTokens
    val reduceMotion = accessibilityReduceMotion()
    val landed = remember { Animatable(if (delay == null) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (landed.value == 1f || delay == null) return@LaunchedEffect
        if (delay > 0) delay(delay.millis().toLong())
        landed.animateTo(
            1f,
            if (reduceMotion) tween(T.stampDuration.millis(), easing = CubicBezierEasing(0f, 0f, 0.58f, 1f))
            else swiftSpring(T.stampDuration.toFloat(), 0.2f),
        )
    }
    Text(
        status.stamp,
        style = PleadType.labelCaps.copy(letterSpacing = PleadType.capsTracking.sp),
        color = status.color,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .graphicsLayer {
                val v = landed.value
                val start = OnboardingMotionTokens.revealStartOpacity.toFloat()
                alpha = (start + (0.92f - start) * v).coerceIn(0f, 1f)
                val s = if (reduceMotion) 1f else T.stampStartScale + (1f - T.stampStartScale) * v
                scaleX = s
                scaleY = s
            }
            .rotate(T.stampRotation)
            .border(T.stampBorder, status.color, RoundedCornerShape(T.stampRadius))
            .padding(horizontal = PleadSpacing.s, vertical = 3.dp)
            .clearAndSetSemantics { },
    )
}
