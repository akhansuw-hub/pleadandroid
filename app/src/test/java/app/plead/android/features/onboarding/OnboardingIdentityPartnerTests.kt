// Port of ArgueWinTests/OnboardingIdentityPartnerTests.swift: amendment ak · Your Court Identity
// (`CourtIdentityPreview`, avatar picker) and Bring in Your Partner (`PartnerVersusCard`).
package app.plead.android.features.onboarding

import app.plead.android.designsystem.PixelAvatar
import app.plead.android.models.Avatar
import app.plead.android.services.CaseStore
import app.plead.android.services.PreviewData
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingIdentityPartnerTests {
    @get:org.junit.Rule val main = app.plead.android.services.MainDispatcherRule()

    // MARK: Identity copy

    @Test fun identityCopyMatchesTheBrief() {
        assertEquals("How should the court know you?", CourtIdentityView.headline)
        assertEquals("Name on the docket", CourtIdentityView.nameLabel)
        assertEquals("IN COURT", CourtIdentityPreview.plateTitle)
    }

    // MARK: Preview text

    @Test fun previewShowsTheNameAsItWillAppear() {
        assertEquals("ARIF", CourtIdentityPreview.displayName(" arif "))
        assertTrue(CourtIdentityPreview.hasName("Arif"))
        assertEquals("IN COURT: ARIF", CourtIdentityPreview.label("Arif"))
    }

    @Test fun previewEmptyNameFallsBackToAPlaceholder() {
        for (empty in listOf("", "   ", "\n")) {
            assertEquals(CourtIdentityPreview.placeholderName, CourtIdentityPreview.displayName(empty))
            assertFalse(CourtIdentityPreview.hasName(empty))
            assertEquals("IN COURT: YOU", CourtIdentityPreview.label(empty))
            assertTrue(CourtIdentityPreview.accessibilityText(empty, Avatar.default).startsWith("In court preview. No name yet"))
        }
    }

    @Test fun previewLongNameIsKeptWholeAndWrapsToTwoLines() {
        // The field caps names at 30 characters; the plate shows it all, wrapping to two lines, then truncating.
        val long = "Maximiliana Alexandrina Wolfe"
        assertEquals(long.uppercase(), CourtIdentityPreview.displayName(long))
        assertEquals(2, OnboardingIdentityTokens.nameLines)
        assertTrue(CourtIdentityPreview.accessibilityText(long, Avatar.default).contains(long))
    }

    @Test fun pixelArtRendersAtIntegerScales() {
        val side = PixelAvatar.side.toFloat()
        for (size in listOf(OnboardingIdentityTokens.previewAvatar, OnboardingIdentityTokens.pickerAvatar, OnboardingIdentityTokens.versusAvatar)) {
            assertEquals(0f, size % side)
        }
        assertTrue(OnboardingIdentityTokens.previewAvatar > OnboardingIdentityTokens.pickerAvatar)
    }

    // MARK: Selected avatar

    @Test fun exactlyOnePresetIsSelected() {
        OnboardingAvatars.presets.forEachIndexed { i, preset ->
            assertEquals(listOf(i), CourtIdentityView.selectedIndices(preset))
            assertEquals(preset, CourtIdentityView.displayed(i, preset, preset))
        }
    }

    @Test fun customAvatarTakesTheFirstTile() {
        var custom = OnboardingAvatars.presets[3].let { it.copy(top = (it.top + 1) % Avatar.topColours.size) }
        if (OnboardingAvatars.presets.contains(custom)) {
            custom = custom.copy(hairstyle = if (custom.hairstyle == Avatar.Hairstyle.buzz) Avatar.Hairstyle.bun else Avatar.Hairstyle.buzz)
        }
        assertTrue(CourtIdentityView.isCustom(custom))
        assertEquals(listOf(0), CourtIdentityView.selectedIndices(custom))
        assertEquals(custom, CourtIdentityView.displayed(0, OnboardingAvatars.presets[0], custom))
        assertEquals(OnboardingAvatars.presets[1], CourtIdentityView.displayed(1, OnboardingAvatars.presets[1], custom))
    }

    @Test fun selectionSettlesOnlySlightly() {
        assertTrue(OnboardingIdentityTokens.pickerSelectedScale > 1)
        assertTrue(OnboardingIdentityTokens.pickerSelectedScale <= 1.05f)
    }

    // MARK: Versus card

    @Test fun versusCardCopy() {
        assertEquals("Cases will appear in this format.", PartnerVersusCard.caption)
        assertEquals("YOU v. PARTNER", PartnerVersusCard.heading(me = "", partner = ""))
        assertEquals("ARIF v. SOPHIE", PartnerVersusCard.heading(me = " Arif", partner = "sophie "))
        assertEquals(
            "Future case preview: Arif versus your partner. Cases will appear in this format.",
            PartnerVersusCard.accessibilityText(me = "Arif", partner = ""),
        )
        assertEquals("Invite Partner", PartnerSetupView.inviteTitle)
        assertEquals("I'll Do This Later", PartnerSetupView.laterTitle)
    }

    @Test fun versusCardIsNotAdversarial() {
        val copy = listOf(PartnerSetupView.headline, PartnerSetupView.subtitle, PartnerVersusCard.caption, PartnerVersusCard.title)
            .joinToString(" ").lowercase()
        for (word in listOf("fight", "enemy", "opponent", "battle", "versus")) assertFalse(word, copy.contains(word))
        assertEquals("v.", PartnerVersusCard.versus)
    }

    // MARK: Amendment aw: no partner-name entry

    /**
     * Before linking the partner is generic copy, even when a legacy `partner_name_temp` exists; once linked the
     * partner's own display name is used.
     */
    @Test fun partnerNameComesOnlyFromTheLinkedPartner() {
        val me = PreviewData.onboardingMe.copy(partnerNameTemp = "Sophie")
        val unlinked = CaseStore(preview = me, partner = null, couple = PreviewData.unlinkedCouple)
        assertNull(unlinked.partnerDisplayName)
        assertEquals("ARIF v. PARTNER", PartnerVersusCard.heading(me = "Arif", partner = ""))
        assertEquals("Your partner", OnboardingCompleteView.podiumName(null, fallback = OnboardingCompleteView.partnerFallback))

        val linked = CaseStore(preview = PreviewData.me, partner = PreviewData.partner, couple = PreviewData.couple)
        assertEquals("Alex", linked.partnerDisplayName)
    }

    /** The partner step saves only "together since"; it never writes a temporary partner name. */
    @Test fun partnerStepSavesOnlyTheDate() = runTest(main.dispatcher) {
        val date = Instant.ofEpochSecond(1_707_868_800)
        val store = CaseStore(preview = PreviewData.onboardingMe, partner = null, couple = PreviewData.unlinkedCouple)
        store.saveTogetherSince(date)
        assertEquals(date, store.couple?.togetherSince)
        assertNull(store.me?.partnerNameTemp)
    }

    @Test fun versusSettlesBeforeTheCTAs() {
        assertTrue(PartnerSetupView.docketSettled <= OnboardingMotionTokens.ctaDelay + 0.1)
        assertTrue(OnboardingIdentityTokens.envelopeRise > 0)
    }
}
