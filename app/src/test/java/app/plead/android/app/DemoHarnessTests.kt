// The demo-harness flag parser: iOS `UserDefaults` argument-domain semantics on intent extras.
package app.plead.android.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoHarnessTests {
    @After fun tearDown() = LaunchArguments.set(emptyMap())

    @Test fun boolFollowsNSStringBoolValue() {
        for (yes in listOf("YES", "yes", "true", "T", "1", "  +01", "y")) assertTrue(yes, LaunchArguments.boolValue(yes))
        for (no in listOf("NO", "no", "false", "0", "", "000", "x")) assertFalse(no, LaunchArguments.boolValue(no))
    }

    @Test fun numbersFollowNSString() {
        assertEquals(14, LaunchArguments.integerValue("14"))
        assertEquals(0, LaunchArguments.integerValue("abc"))
        assertEquals(2.5, LaunchArguments.doubleValue("2.5"), 0.0)
        assertEquals(0.0, LaunchArguments.doubleValue(""), 0.0)
    }

    @Test fun typedFlags() {
        LaunchArguments.set(
            mapOf(
                "AWDemo" to "YES", "AWDemoStore" to "settlementCounter", "AWTab" to "court", "AWSheet" to "exitOffer",
                "AWPaywallOpening" to "NO", "AWProducts" to "monthly,weekly", "AWAutoCloseAfter" to "2.5",
                "AWOpenCase" to "14", "AWTrial" to "no", "AWDemoName" to "A".repeat(40), "AWFileStep" to "evidence",
            ),
        )
        assertTrue(DemoHarness.isDemo)
        assertEquals(DemoHarness.DemoStore.settlementCounter, DemoHarness.demoStore)
        assertEquals(DemoHarness.Tab.court, DemoHarness.tab)
        assertEquals(DemoHarness.Sheet.exitOffer, DemoHarness.sheet)
        assertEquals(false, DemoHarness.paywallOpening)
        assertEquals(setOf("monthly", "weekly"), DemoHarness.products)
        assertEquals(2.5, DemoHarness.autoCloseAfter, 0.0)
        assertEquals(14, DemoHarness.openCase)
        assertFalse(DemoHarness.trialEligible)
        assertTrue(DemoHarness.exitOfferEligible)
        assertEquals(30, DemoHarness.demoName?.length)
        assertTrue(DemoHarness.fileCaseEvidenceStep)
        assertNull(DemoHarness.coldOpen)
    }

    @Test fun unknownValuesAreIgnored() {
        LaunchArguments.set(mapOf("AWDemo" to "YES", "AWTab" to "settings", "AWDemoStore" to "nope"))
        assertNull(DemoHarness.tab)
        assertNull(DemoHarness.demoStore)
        assertNull(DemoHarness.paywallOpening)
    }
}
