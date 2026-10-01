// Port of ArgueWinUITests/PartnerPaidTests.swift: joined a couple the partner already paid for
// ("Your partner's subscription covers you both.") → CONTINUE → the app.
package app.plead.android.features.shell

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PartnerPaidComposeTests : ShellUITestCase() {

    @Test fun testPartnerPaidContinuesIntoApp() {
        launch("AWDemoStore" to "partnerPaid")
        waitForContaining("Your partner")
        tap("CONTINUE")
        // `partnerPaid` is a secured account, so the tabs follow; an anonymous one would meet SecureAccountView.
        if (waitUntil(3_000) { exists("secure.apple") }) secureAccountWithApple()
        assertTabs()
    }
}
