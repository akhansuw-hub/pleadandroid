// Port of `InviteCodeTests` in ArgueWinTests/PartnerCodeJoinTests.swift (amendment as: invited partners enter their
// code on the onboarding partner step). `joinErrorsReadPlainly` lives in services/EdgeFunctionsTests.kt (wave 2a);
// `PartnerCodeGateTests` (app/AppGateTests.kt) and `PartnerCodeJoinFlowTests` (app/AppModelTests.kt) were ported by 2a.
package app.plead.android.features.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteCodeTests {
    @Test fun typedCodesAreUppercasedAndTrimmedToSix() {
        assertEquals("ABC234", InviteCode.sanitize("abc234"))
        assertEquals("ABC234", InviteCode.sanitize(" ab c-234 "))
        assertEquals("ABC234", InviteCode.sanitize("ABC2345"))
        assertEquals("", InviteCode.sanitize(""))
        assertEquals("AB", InviteCode.sanitize("ab"))
    }

    @Test fun pastedInvitesGiveTheirCode() {
        assertEquals("XK7P2Q", InviteCode.sanitize("https://plead-drab.vercel.app/join/xk7p2q"))
        assertEquals("XK7P2Q", InviteCode.sanitize("plead://join/XK7P2Q"))
        val message = "You've been summoned. Join me on Plead with code XK7P2Q. https://plead-drab.vercel.app/join/XK7P2Q"
        assertEquals("XK7P2Q", InviteCode.sanitize(message))
        assertEquals("XK7P2Q", InviteCode.sanitize("You've been summoned. Join me on Plead with code XK7P2Q."))
    }

    @Test fun wellFormedMatchesTheServerAlphabet() {
        // gen_invite_code(): ABCDEFGHJKLMNPQRSTUVWXYZ23456789; join_couple: ^[A-HJ-NP-Z2-9]{6}$
        assertEquals("ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toSet(), InviteCode.alphabet)
        assertTrue(InviteCode.isWellFormed("ABC234"))
        assertTrue(InviteCode.isWellFormed("ZZ9922"))
        for (bad in listOf("ABC23", "ABC2345", "ABO234", "ABI234", "AB0234", "AB1234", "abc234", "AB-234")) {
            assertFalse(bad, InviteCode.isWellFormed(bad))
        }
    }

    @Test fun lookalikesGetAHintOnlyWhenComplete() {
        assertNull(InviteCode.problem("AB0"))
        assertNull(InviteCode.problem("ABC234"))
        assertEquals(InviteCode.lookalikeMessage, InviteCode.problem("AB0234"))
        assertEquals(InviteCode.lookalikeMessage, InviteCode.problem("ABCDEO"))
    }

    @Test fun partnerStepCopy() {
        assertEquals("Invite Partner", PartnerSetupView.inviteTitle)
        assertEquals("I Have an Invite Code", PartnerSetupView.haveCodeTitle)
        assertEquals("I'll Do This Later", PartnerSetupView.laterTitle)
        assertEquals("You're linked with Alex.", PartnerSetupView.linkedHeadline("Alex"))
        assertEquals("Alex's subscription already covers you both.", PartnerSetupView.linkedSubtitle(partner = "Alex", premium = true))
        assertTrue(PartnerSetupView.linkedSubtitle(partner = "Alex", premium = false).contains("One subscription covers you both"))
        val copy = listOf(
            PartnerSetupView.joinKicker, PartnerSetupView.joinHeadline, PartnerSetupView.joinSubtitle,
            PartnerSetupView.linkedSubtitle(partner = "A", premium = false),
        ).joinToString(" ")
        assertFalse(copy.contains("Premium"))
        assertFalse(copy.lowercase().contains("arguewin"))
    }
}
