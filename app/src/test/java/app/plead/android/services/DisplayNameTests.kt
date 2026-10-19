package app.plead.android.services

import app.plead.android.models.EdgeError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/** Amendment bj: the display name can be edited from Us, under onboarding's rules. */
class DisplayNameTests {
    @get:Rule val main = MainDispatcherRule()

    @Test fun cleaningTrimsAndCapsAtThirty() {
        assertEquals("Sophie", DisplayName.clean("  Sophie \n"))
        assertEquals(30, DisplayName.clean("a".repeat(40)).length)
        assertFalse(DisplayName.isValid("   "))
        assertTrue(DisplayName.isValid(" S "))
    }

    @Test fun savingUpdatesMyProfile() = runTest(main.dispatcher) {
        val s = CaseStore(preview = PreviewData.onboardingMe, partner = null, couple = null)
        s.setDisplayName("  Soph ")
        assertEquals("Soph", s.me?.displayName)
    }

    @Test fun emptyNameIsRefused() = runTest(main.dispatcher) {
        val s = CaseStore(preview = PreviewData.onboardingMe, partner = null, couple = null)
        val before = s.me?.displayName
        try {
            s.setDisplayName("   ")
            fail("expected EdgeError")
        } catch (e: EdgeError) {
            assertEquals("invalid_input", e.code)
        }
        assertEquals(before, s.me?.displayName)
    }
}
