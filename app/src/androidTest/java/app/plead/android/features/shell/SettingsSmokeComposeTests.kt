// Port of ArgueWinUITests/SettingsSmokeTests.swift: Settings from Home's gear: notifications, the onboarding preview,
// and the delete-account sheet (everything found by visible labels, as on iOS).
package app.plead.android.features.shell

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.plead.android.support.Onboarding
import app.plead.android.support.PleadComposeTestCase
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsSmokeComposeTests : PleadComposeTestCase() {

    @Test fun testSettingsSections() {
        launch()
        assertTabs()
        tap("home.settings")
        assertTrue("Settings never opened", waitUntil { exists(navigationBar("Settings")) })
        waitFor("Notifications")

        // Preview onboarding opens (Welcome) and closes.
        tap(swipeThrough("Preview onboarding"), "Preview onboarding")
        waitFor(Onboarding.welcome)
        tap("Close preview")
        waitForGone("Close preview")
        assertTrue("Closing the preview left Settings", waitUntil(5_000) { exists(navigationBar("Settings")) })

        // Delete account: the confirmation sheet opens and cancels.
        tap(swipeThrough("Delete account"), "Delete account")
        waitFor("Delete your account?")
        tap("Cancel")
        waitForGone("Delete your account?")
        assertTrue("Cancel left Settings", exists(navigationBar("Settings")))
    }
}
