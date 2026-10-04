// Port of ArgueWin/Features/Onboarding/About/AboutIntroScreen.swift. "About you two" intro (CONTRACTS-v2 amendment bg,
// mockup docs/onboarding-about-brief/mockup-intro.png): between Welcome and the first question. Not skippable (no
// Skip); START QUESTIONS is the only way forward, Back → Welcome.
//
//   hero     AboutHeroScene(intro): the judge writing on a clipboard, both parties calm and attentive, banners and
//            the wall sconces. It is the flexible part of the page: at most `maxHero`, otherwise exactly the height the
//            text block and CTA leave free, so there is no empty band: the eyebrow sits `heroGap` under the hero and the
//            CTA `ctaGap` under the facts row. iOS never lets it go below the question screens' hero; Android (short
//            screens, 3-button navigation, large fonts) lets it give height back first, down to `AboutMotion.heroMin`,
//            and only then does the page scroll.
//   copy     BEFORE WE BEGIN · "Tell the court about you two." · body · facts (pixel checklist "5 quick questions",
//            pixel gavel "1 courtroom oath"; both counts derived) · START QUESTIONS →
//   motion   hero fades in (layer 0); eyebrow layer 1, title layer 1 + 0.06 s, body layer 2, facts layer 2 + 0.08 s,
//            CTA layer 3. Reduce Motion: fades only, still hero. The page-turn haptic of amendment bf comes from
//            `advance()`.
package app.plead.android.features.onboarding.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.app.AppModel
import app.plead.android.designsystem.PixelGrid
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.features.onboarding.CourtLayerStyle
import app.plead.android.features.onboarding.CourtPrimaryButton
import app.plead.android.features.onboarding.OnboardingKitTokens
import app.plead.android.features.onboarding.OnboardingPalette
import app.plead.android.features.onboarding.OnboardingStep
import app.plead.android.features.onboarding.courtLayer
import app.plead.android.features.onboarding.onboardingModel

object AboutIntroScreen {
    const val eyebrow = "BEFORE WE BEGIN"
    const val title = "Tell the court about you two."
    const val body = "A few quick questions about the little things you disagree on. Then take your courtroom oath."
    const val cta = "START QUESTIONS →"

    /** "5 quick questions" from the question list; "1 courtroom oath" from the oath steps. */
    val questionCount: Int get() = AboutQuestion.entries.size
    val oathCount: Int get() = OnboardingStep.aboutYouTwoSteps.count { it == OnboardingStep.oath }
    val questionsFact: String get() = "$questionCount quick question${if (questionCount == 1) "" else "s"}"
    val oathFact: String get() = "$oathCount courtroom oath${if (oathCount == 1) "" else "s"}"

    // Layout (dp).
    val heroGap: Dp = 18.dp
    val titleGap: Dp = 6.dp
    val bodyGap: Dp = 10.dp
    val factsGap: Dp = 16.dp
    val ctaGap: Dp = 20.dp
    val bottomGap: Dp = 8.dp
    val maxHero: Dp = 460.dp
    val iconSize: Dp = 24.dp
    val factsSpacing: Dp = 16.dp
    val factsStackSpacing: Dp = 10.dp

    /** iOS's floor (the question screens' hero). */
    fun minHero(width: Float): Float = AboutHeroLayout.height(width)

    /**
     * The hero height for a page `available` dp tall whose text block is `text` dp (pure; unit-tested): the space the
     * text leaves, at most `maxHero`; at least iOS's floor when that fits, otherwise (Android) down to
     * `AboutMotion.heroMin`, past which the page scrolls.
     */
    fun heroHeight(available: Float, width: Float, text: Float): Float {
        val free = available - text
        val floor = minOf(minHero(width), AboutMotion.heroMin.value)
        return free.coerceIn(floor, maxHero.value)
    }

    const val identifier = "onboarding.about.intro"
    const val ctaIdentifier = "onboarding.about.start"
}

@Composable
fun AboutIntroScreen(app: AppModel) {
    val model = app.onboardingModel
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxSize().testTag(AboutIntroScreen.identifier)) {
        val available = constraints.maxHeight
        Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Layout(
                contents = listOf(
                    {
                        Box(Modifier.testTag("onboarding.about.heroFrame")) {
                            AboutHeroScene(AboutHeroKind.intro, Modifier.fillMaxSize().courtLayer(0, CourtLayerStyle.fade))
                        }
                    },
                    { IntroText { model.advance() } },
                ),
            ) { (heroM, textM), c ->
                val w = c.maxWidth
                val textP = textM.first().measure(Constraints(minWidth = w, maxWidth = w))
                val heroH = AboutIntroScreen.heroHeight(available / density, w / density, textP.height / density).dp.roundToPx()
                val heroP = heroM.first().measure(Constraints.fixed(w, heroH))
                layout(w, maxOf(heroH + textP.height, available)) {
                    heroP.place(0, 0)
                    textP.place(0, heroH)
                }
            }
        }
    }
}

@Composable
private fun IntroText(onStart: () -> Unit) {
    val S = AboutIntroScreen
    Column(
        Modifier
            .fillMaxWidth()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = OnboardingKitTokens.Spacing.maxWidth)
            .fillMaxWidth()
            .padding(horizontal = AboutMotion.gutter)
            .padding(top = S.heroGap, bottom = S.bottomGap)
            .navigationBarsPadding(),
    ) {
        Text(
            S.eyebrow,
            style = PleadType.labelCaps.copy(letterSpacing = PleadType.capsTracking.sp),
            color = OnboardingPalette.burgundy,
            modifier = Modifier.clearAndSetSemantics { }.courtLayer(1),
        )
        Text(
            S.title,
            style = PleadType.display(36f, weight = FontWeight.SemiBold, relativeTo = TextStyleKind.largeTitle)
                .let { it.copy(lineHeight = (it.fontSize.value * 1.19f - 2f).sp) },
            color = OnboardingPalette.wine,
            modifier = Modifier
                .padding(top = S.titleGap)
                .testTag("onboarding.about.intro.title")
                .semantics { heading() }
                .courtLayer(1, delay = 0.06),
        )
        Text(
            S.body,
            style = PleadType.text(18f, FontWeight.Normal, relativeTo = TextStyleKind.body)
                .let { it.copy(lineHeight = (it.fontSize.value * 1.19f + 2f).sp) },
            color = OnboardingPalette.cocoa,
            modifier = Modifier.padding(top = S.bodyGap).courtLayer(2),
        )
        IntroFacts(Modifier.padding(top = S.factsGap).courtLayer(2, delay = 0.08))
        CourtPrimaryButton(
            title = S.cta,
            identifier = S.ctaIdentifier,
            modifier = Modifier.padding(top = S.ctaGap).courtLayer(3),
            action = onStart,
        )
    }
}

/** The two facts side by side, or stacked when they don't fit (Swift `ViewThatFits`; large text wraps, never clips). */
@Composable
private fun IntroFacts(modifier: Modifier = Modifier) {
    Layout(
        contents = listOf(
            { Fact(AboutIntroIcon.checklist, AboutIntroScreen.questionsFact) },
            { Fact(AboutIntroIcon.gavel, AboutIntroScreen.oathFact) },
        ),
        modifier = modifier.fillMaxWidth(),
    ) { (aM, bM), c ->
        val loose = Constraints(maxWidth = c.maxWidth)
        val a = aM.first().measure(loose)
        val b = bM.first().measure(loose)
        val gap = AboutIntroScreen.factsSpacing.roundToPx()
        if (a.width + gap + b.width <= c.maxWidth) {
            val h = maxOf(a.height, b.height)
            layout(c.maxWidth, h) {
                a.place(0, (h - a.height) / 2)
                b.place(a.width + gap, (h - b.height) / 2)
            }
        } else {
            val vgap = AboutIntroScreen.factsStackSpacing.roundToPx()
            layout(c.maxWidth, a.height + vgap + b.height) {
                a.place(0, 0)
                b.place(0, a.height + vgap)
            }
        }
    }
}

@Composable
private fun Fact(icon: AboutIntroIcon, text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) { },
    ) {
        PixelGrid(rows = icon.rows, colors = AboutIntroIcon.palette, modifier = Modifier.size(AboutIntroScreen.iconSize))
        Text(
            text,
            style = PleadType.ui(15f, FontWeight.SemiBold, relativeTo = TextStyleKind.subheadline),
            color = OnboardingPalette.cocoa,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** The intro's two fact icons: Court Burgundy pixel glyphs with a gold accent (12 × 12). */
enum class AboutIntroIcon {
    checklist, gavel;

    val rows: List<String>
        get() = when (this) {
            checklist -> listOf(
                "..bbbbbbbb..",
                ".b...gg...b.",
                ".b........b.",
                ".b..g.....b.",
                ".bgg..bbb.b.",
                ".b........b.",
                ".b..g.....b.",
                ".bgg..bbb.b.",
                ".b........b.",
                ".b..g.....b.",
                ".bgg..bbb.b.",
                "..bbbbbbbb..",
            )
            gavel -> listOf(
                "...bb.......",
                "..bbbb......",
                ".bbbbbb.....",
                "bbbbbbbb....",
                ".bbbbbbbb...",
                "..bbbbbb.b..",
                "...bbbb...b.",
                "....bb.....b",
                "............",
                ".gggggggg...",
                "bbbbbbbbbb..",
                "bbbbbbbbbb..",
            )
        }

    companion object {
        val palette: Map<Char, Color> get() = mapOf('b' to OnboardingPalette.burgundy, 'g' to OnboardingPalette.gold)
    }
}
