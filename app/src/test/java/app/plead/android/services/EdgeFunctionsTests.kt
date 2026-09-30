// The service halves of ArgueWinTests' ModelDecodingTests (encodesAvatarForProfileUpdate, edgeErrorEnvelope),
// AccountDeletionTests.deleteAccountBodySendsConfirm, InviteCodeTests.joinErrorsReadPlainly,
// PaywallCopyTests.productIdsAndEntitlement, AttributionTests (pure parts) and ExhibitLabelTests.draftsGetSequentialLabels.
package app.plead.android.services

import app.plead.android.models.Avatar
import app.plead.android.models.EdgeError
import app.plead.android.models.ExhibitLabel
import app.plead.android.models.ExhibitType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelEncodingTests {
    /** The app writes `avatar_json` with exactly the contract keys. */
    @Test fun encodesAvatarForProfileUpdate() {
        val row = ProfileService.AvatarUpdate(avatarJson = Avatar(skin = 0, hair = 1, hairstyle = Avatar.Hairstyle.bun, top = 2, outfit = Avatar.Outfit.tee))
        val json = row.body()
        val avatar = json["avatar_json"] as JsonObject
        assertEquals(setOf("skin", "hair", "hairstyle", "top", "outfit", "version"), avatar.keys)
        assertEquals("bun", (avatar["hairstyle"] as JsonPrimitive).content)
    }

    @Test fun edgeErrorEnvelope() {
        val e = EdgeFunctions.edgeError(from = """{"ok":false,"code":"limit_open_cases","message":"One at a time."}""", status = 403)
        assertEquals("limit_open_cases", e.code)
        assertFalse(e.isPremiumRequired)
        val fallback = EdgeFunctions.edgeError(from = "<html>", status = 501)
        assertEquals("not_implemented", fallback.code)
    }

    @Test fun premiumRequiredWithoutEnvelope() {
        val e = EdgeFunctions.edgeError(from = "<html>", status = 402)
        assertEquals("premium_required", e.code)
        assertTrue(e.isPremiumRequired)
        assertEquals("http_500", EdgeFunctions.edgeError(from = "", status = 500).code)
    }
}

class AccountDeletionTests {
    @Test fun deleteAccountBodySendsConfirm() {
        val json = JSONCoding.json.encodeToString(EdgeFunctions.DeleteAccountBody.serializer(), EdgeFunctions.DeleteAccountBody())
        assertEquals("""{"confirm":true}""", json)
    }
}

class InviteCodeTests {
    @Test fun joinErrorsReadPlainly() {
        assertEquals(EdgeErrors.tooManyJoinAttemptsMessage, EdgeErrors.joinMessage(EdgeError(code = "too_many_attempts", message = "x")))
        assertEquals(EdgeErrors.tooManyJoinAttemptsMessage, EdgeErrors.joinMessage(EdgeError(code = "http_429", message = "x")))
        assertEquals("That code doesn't match an open invite.", EdgeErrors.joinMessage(EdgeError(code = "invalid_code", message = "x")))
        assertEquals(
            "That's your own invite code. Send it to your partner instead.",
            EdgeErrors.joinMessage(EdgeError(code = "wrong_state", message = "you cannot join your own invite")),
        )
        assertEquals(
            "That invite has already been used. Ask your partner for a fresh code.",
            EdgeErrors.joinMessage(EdgeError(code = "wrong_state", message = "that couple is already linked")),
        )
        assertEquals("You're already linked with a partner.", EdgeErrors.joinMessage(EdgeError(code = "wrong_state", message = "leave your current couple first")))
    }
}

class SettlementAndJudgementMessageTests {
    @Test fun settlementMessages() {
        assertEquals(EdgeErrors.settlementRoundLimitMessage, EdgeErrors.settlementMessage(EdgeError("limit_rounds", "x")))
        assertEquals(EdgeErrors.unsafeTermsMessage, EdgeErrors.settlementMessage(EdgeError("http_422", "x")))
        assertEquals("It's your partner's move on this offer.", EdgeErrors.settlementMessage(EdgeError("wrong_role", "x")))
        assertEquals("Couldn't reach the court. Try again.", EdgeErrors.settlementMessage(IllegalStateException()))
    }

    @Test fun judgementMessages() {
        assertEquals(EdgeErrors.rerollLimitMessage, EdgeErrors.judgementMessage(EdgeError("limit_rerolls", "x")))
        assertEquals("This judgement has moved on. Pull to refresh.", EdgeErrors.judgementMessage(EdgeError("wrong_state", "x")))
    }
}

class PaywallCopyTests {
    @Test fun productIdsAndEntitlement() {
        assertEquals("plead.weekly", PurchasesService.weeklyID)
        assertEquals("plead.yearly", PurchasesService.annualID)
        assertEquals("premium", PurchasesService.entitlementID)
        assertEquals("plead.discount", PurchasesService.annualOfferID)
        assertEquals("exit_offer", PurchasesService.exitOfferingID)
    }

    @Test fun plansResolveToWhatLoaded() {
        val products = PaywallProducts.preview(plans = setOf(PurchasesService.Plan.monthly, PurchasesService.Plan.weekly))
        assertEquals(listOf(PurchasesService.Plan.monthly, PurchasesService.Plan.weekly), products.availablePlans)
        assertEquals(PurchasesService.Plan.monthly, products.resolve(PurchasesService.Plan.annual))
        assertEquals("£9.99 / week", products.weekly?.perPeriod)
        assertTrue(PaywallProducts.empty.isEmpty)
    }
}

class AttributionTests {
    @Test fun startsOnlyWithARealKeyOutsideDemoAndTests() {
        assertTrue(Attribution.shouldStart(devKey = "abc", appID = "6816141179", demo = false, underTests = false))
        assertFalse(Attribution.shouldStart(devKey = null, appID = "6816141179", demo = false, underTests = false))
        assertFalse(Attribution.shouldStart(devKey = "", appID = "6816141179", demo = false, underTests = false))
        assertFalse(Attribution.shouldStart(devKey = "abc", appID = null, demo = false, underTests = false))
        assertFalse(Attribution.shouldStart(devKey = "abc", appID = "6816141179", demo = true, underTests = false))
        assertFalse(Attribution.shouldStart(devKey = "abc", appID = "6816141179", demo = false, underTests = true))
        // Android keys AppsFlyer by package name: only the dev key, demo and tests decide (amendment az).
        assertTrue(Attribution.shouldStart(devKey = "abc", demo = false, underTests = false))
    }

    /** The unit-test host never runs AppsFlyer. */
    @Test fun notRunningUnderTests() {
        assertFalse(Attribution.isEnabled)
        assertEquals(null, Attribution.appsFlyerUID)
    }

    @Test fun waitsSixtySecondsForTheATTAnswer() {
        assertEquals(60.0, Attribution.attTimeout, 0.0)
    }

    /** Only name-only funnel events; purchases come from RevenueCat server-side (never double-counted). */
    @Test fun forwardsASmallNonPersonalAllowList() {
        assertEquals(setOf("onboarding_completed", "paywall_viewed"), Attribution.forwardedEvents)
        for (e in Attribution.forwardedEvents) {
            assertFalse(e.contains("purchase"))
            assertFalse(e.contains("trial"))
        }
    }
}

class DraftExhibitTests {
    @Test fun draftsGetSequentialLabels() {
        val drafts = (0 until 30).map { DraftExhibit(type = ExhibitType.text, caption = "c$it", body = "b") }
        assertEquals(ExhibitLabel.A, drafts.label(0))
        assertEquals("AD", drafts.label(29).rawValue)
    }

    @Test fun onlyReceiptsAndScreenshotsCarryADate() {
        assertTrue(DraftExhibit.supportsDate(ExhibitType.receipt))
        assertTrue(DraftExhibit.supportsDate(ExhibitType.screenshot))
        assertFalse(DraftExhibit.supportsDate(ExhibitType.photo))
        assertTrue(DraftExhibit(type = ExhibitType.photo, caption = "x", imageData = byteArrayOf(1)).needsUpload)
        assertFalse(DraftExhibit(type = ExhibitType.text, caption = "x", imageData = byteArrayOf(1)).needsUpload)
    }

    @Test fun receiptLineUsesBritishShortDates() {
        val date = java.time.ZonedDateTime.of(2026, 9, 12, 21, 14, 0, 0, java.time.ZoneOffset.UTC).toInstant()
        assertEquals("12 Sept, 21:14: said he'd be home by 9", DraftExhibit.receiptLine(date, "said he'd be home by 9", java.time.ZoneOffset.UTC))
    }
}
