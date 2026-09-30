// Port of ArgueWinTests/ModelDecodingTests.swift `AccountDeletionTests` (deleteAccountBodySendsConfirm is in
// services/EdgeFunctionsTests.kt, wave 2a). `clearingResetsOnboardingToWelcome` runs against the shell's onboarding
// host (same `onboarding.<scope>.step/.completed` keys) until wave 3b's OnboardingModel replaces it.
package app.plead.android.features.settings

import app.plead.android.app.ShellOnboarding
import app.plead.android.models.EdgeError
import app.plead.android.services.MainDispatcherRule
import app.plead.android.services.UserDefaults
import app.plead.android.services.uuidString
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AccountDeletionTests {
    @get:Rule val main = MainDispatcherRule()

    @Test fun clearsOnlyThisUsersAndLaunchKeys() {
        val me = UUID.randomUUID()
        val other = UUID.randomUUID()
        val keys = listOf(
            "onboarding.${me.uuidString}.step", "onboarding.${me.uuidString}.completed",
            "onboarding.device.step", "linkStepDone.${me.uuidString}",
            "AppLaunchState.hasSeenColdOpen", "coldOpen.fullPlayed",
            "onboarding.${other.uuidString}.completed", "linkStepDone.${other.uuidString}",
            "notification_status", "att_status", "AWDemo",
        )
        val cleared = AccountDeletion.keysToClear(me, keys).toSet()
        assertEquals(
            setOf(
                "onboarding.${me.uuidString}.step", "onboarding.${me.uuidString}.completed",
                "onboarding.device.step", "linkStepDone.${me.uuidString}",
                "AppLaunchState.hasSeenColdOpen", "coldOpen.fullPlayed",
            ),
            cleared,
        )
    }

    @Test fun clearingResetsOnboardingToWelcome() {
        val defaults = UserDefaults.inMemory()
        val uid = UUID.randomUUID()
        val model = ShellOnboarding(defaults)
        model.userChanged(uid, profile = null)
        repeat(9) { model.advance() }
        model.markCompleted()
        assertTrue(model.completedForUser)

        AccountDeletion.clearLocalState(uid, defaults)
        model.userChanged(uid, profile = null)
        assertFalse(model.completedForUser)
        AccountDeletion.clearLocalState(uid, defaults)
        model.userChanged(null, profile = null)
        assertEquals(1, model.stepRawValue)   // OnboardingStep.welcome
    }

    @Test fun missingFunctionReadsAsUnavailable() {
        val msg = AccountDeletion.failureMessage(EdgeError(code = "not_found", message = "x"))
        assertTrue(msg.contains("isn't available"))
    }

    /** Android: the deletion copy names Google Play, never the App Store, and never "Premium". */
    @Test fun copyNamesThisPlatformsStore() {
        assertTrue(AccountDeletion.sheetCopy.contains("Google Play"))
        assertFalse(AccountDeletion.sheetCopy.contains("App Store"))
        assertFalse(AccountDeletion.sheetCopy.contains("Premium"))
        assertEquals("Off in Android Settings. Court notices can't reach you.", NotificationSettingsSections.statusText(app.plead.android.services.NotificationStatus.denied))
        assertEquals(4, NotificationSettingsSections.pushToggles.size)
    }
}
