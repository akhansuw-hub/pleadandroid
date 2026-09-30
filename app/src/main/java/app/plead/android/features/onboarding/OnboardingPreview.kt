// The body of `OnboardingPreviewCover` (ArgueWin/Features/Settings/SettingsView.swift): Settings → "Preview onboarding".
// SettingsView.kt (wave 3e) draws the full-screen cover and passes this as its `onboardingPreview` slot.
//
// `OnboardingContainer` has no preview flag, so this shows only the stateless story screens (Welcome, the mock trial,
// the summons explainer, How it works, the AI court and the docket). They drive a throwaway `OnboardingModel` on an
// in-memory defaults suite, so the user's persisted onboarding state is never touched; the docket's CTA dismisses it.
package app.plead.android.features.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.app.AppModel
import app.plead.android.app.NoColdOpen
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.services.UserDefaults

/** Swift `OnboardingPreviewCover.body`, minus the cover itself (SettingsView's `OnboardingPreviewCover`). */
@Composable
fun OnboardingPreviewScreens(app: AppModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val previewApp = remember(app) {
        val defaults = UserDefaults.inMemory()
        val onboarding = OnboardingModel(defaults)
        AppModel(
            auth = app.auth, store = app.store, purchases = app.purchases, push = app.push,
            onboarding = onboarding, defaults = defaults, coldOpen = NoColdOpen(),
        ).also { onboarding.go(OnboardingStep.welcome) }
    }
    val onboarding = previewApp.onboardingModel
    val step = onboarding.step
    val reduceMotion = accessibilityReduceMotion()

    LaunchedEffect(step) { if (step after OnboardingStep.examples) onClose() }
    BackHandler { if (step == OnboardingStep.welcome) onClose() else onboarding.back() }

    Column(modifier.fillMaxSize().background(OnboardingPalette.cream).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { onboarding.back() },
                enabled = step != OnboardingStep.welcome,
                modifier = Modifier.size(44.dp).alpha(if (step == OnboardingStep.welcome) 0f else 1f),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBackIos, contentDescription = "Back", tint = OnboardingPalette.wine, modifier = Modifier.size(17.dp))
            }
            val position = minOf(step.position(OnboardingStep.activeSteps()), 5)
            OnboardingProgressBar(progress = position / 5.0, modifier = Modifier.weight(1f))
            Text(
                "PREVIEW",
                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp),
                color = OnboardingPalette.wine,
                modifier = Modifier
                    .background(OnboardingPalette.goldLight, CircleShape)
                    .padding(horizontal = PleadSpacing.s, vertical = 4.dp),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close preview", tint = OnboardingPalette.wine, modifier = Modifier.size(15.dp))
            }
        }

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val ms = if (reduceMotion) 0 else 250
                fadeIn(tween(ms)) togetherWith fadeOut(tween(ms))
            },
            modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
            label = "onboardingPreview",
        ) { s ->
            Box(Modifier.fillMaxSize()) {
                when (s) {
                    OnboardingStep.welcome -> OnboardingWelcomeView(previewApp)
                    OnboardingStep.mockTrial -> MockTrialDemoView(
                        onContinue = { onboarding.completeMockTrial() },
                        onSkip = { onboarding.skipMockTrial() },
                    )
                    OnboardingStep.summonsIntro -> SummonsIntroView(previewApp)
                    OnboardingStep.howItWorks -> HowPleadWorksView(previewApp)
                    OnboardingStep.aiCourt -> CourtPanelScreen(previewApp)
                    else -> CaseDocketScreen(previewApp)
                }
            }
        }
    }
}

