// Integration checks for the wave-4 wiring (the Android side of ArgueWinUITests' smoke flows): the tab shell mounts
// every tab body, the root sheet slot presents the real sheets, the summons cover shows over the tabs, and the
// component gallery (AWSheet gallery) renders without crashing.
package app.plead.android.app

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.plead.android.designsystem.ComponentGallery
import app.plead.android.designsystem.PleadTheme
import app.plead.android.services.PreviewData
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppShellComposeTests {
    @get:Rule val rule = createComposeRule()

    private fun waitForText(text: String) {
        rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun tabsMountTheirScreens() {
        val model = PreviewData.model()
        rule.setContent { PleadTheme { MainTabScreen(model) } }
        waitForText("Good ")                       // Home greeting
        rule.onNodeWithText("Cases").performClick()
        waitForText("The docket")
        rule.onNodeWithText("Us").performClick()
        waitForText("Presiding judge")
        rule.onNodeWithText("Home").performClick()
        waitForText("Good ")
    }

    @Test fun sheetSlotPresentsSettingsAndTheSettlementRoom() {
        val model = PreviewData.model()
        rule.setContent { PleadTheme { MainTabScreen(model) } }
        rule.runOnIdle { model.router.sheet = AppSheet.settings }
        waitForText("Preview onboarding")
        rule.runOnIdle { model.router.sheet = AppSheet.settlementRoom(PreviewData.summonedCase.id) }
        waitForText("SUGGESTED SETTLEMENTS")
        rule.runOnIdle { model.router.sheet = null }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("SUGGESTED SETTLEMENTS", substring = true)).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun summonsCoverShowsOverTheTabs() {
        val model = PreviewData.model()
        rule.setContent {
            PleadTheme {
                MainTabScreen(model)
                MainTabEffects(model)
            }
        }
        rule.runOnIdle { model.router.summonsCaseId = PreviewData.summonedCase.id }
        waitForText("YOU HAVE BEEN SUMMONED")
        rule.onAllNodesWithText("Decide later").onFirst().performClick()
        rule.waitUntil(10_000) { model.router.summonsCaseId == null }
    }

    @Test fun componentGalleryRenders() {
        rule.setContent { PleadTheme { ComponentGallery() } }
        // Buttons speak their title as a content description.
        rule.waitUntil(10_000) { rule.onAllNodes(hasContentDescription("SUMMON YOUR PARTNER")).fetchSemanticsNodes().isNotEmpty() }
    }
}
