// Compose-host checks for the design system (run by the integrator on the emulator): semantics and press handling
// that need a composition. Pure logic lives in src/test (ComponentsTests, PixelAvatarTests, PleadWordmarkTests).
package app.plead.android.designsystem

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.plead.android.models.Avatar
import app.plead.android.models.CaseStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComponentsComposeTests {
    @get:Rule val rule = createComposeRule()

    @Test fun primaryButtonClicksAndDisablesWhileLoading() {
        var taps = 0
        rule.setContent {
            PleadTheme {
                androidx.compose.foundation.layout.Column {
                    PrimaryButton("Summon your partner") { taps++ }
                    PrimaryButton("Saving", isLoading = true) { taps += 100 }
                }
            }
        }
        rule.onNodeWithContentDescription("Summon your partner").performClick()
        rule.onNodeWithContentDescription("Saving").assertIsNotEnabled()
        assertEquals(1, taps)
    }

    @Test fun avatarBadgeSpeaksWhoItIs() {
        rule.setContent {
            PleadTheme {
                AvatarBadge(Avatar.default, isYou = true)
                AvatarBadge(Avatar(hairstyle = Avatar.Hairstyle.curly, outfit = Avatar.Outfit.hoodie))
                AvatarPlaceholder()
            }
        }
        rule.onNodeWithContentDescription("Your avatar").assertExists()
        rule.onNodeWithContentDescription("Pixel avatar: curly hair, hoodie").assertExists()
        rule.onNodeWithContentDescription("Partner not linked yet").assertExists()
    }

    @Test fun statusChipAndLogoLabels() {
        rule.setContent {
            PleadTheme {
                StatusChip(CaseStatus.deliberating)
                PleadLogo(strapline = true, width = androidx.compose.ui.unit.Dp(220f))
            }
        }
        rule.onNodeWithContentDescription("Status: Deliberating").assertExists()
        rule.onNodeWithContentDescription("Plead. A Happier Kind Of Debate").assertExists()
    }

    @Test fun caseFileCardOpensAndActs() {
        var opened = 0
        var acted = 0
        rule.setContent {
            PleadTheme {
                CaseFileCard(
                    number = 16, title = "The Spoiler", parties = "Alex v. Sam",
                    status = CaseFileStatus(CaseFileStatus.Kind.needsYou, "Needs you", "File your defence", null),
                    action = CaseFileAction("File your defence") { acted++ },
                    onOpen = { opened++ },
                )
            }
        }
        rule.onNodeWithTag("casefile.16").performClick()
        rule.onNodeWithTag("casefile.16.action").performClick()
        rule.onNodeWithText("File your defence").assertExists()
        assertEquals(1, opened)
        assertEquals(1, acted)
    }
}
