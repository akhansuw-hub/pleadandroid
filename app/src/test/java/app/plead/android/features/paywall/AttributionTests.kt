// Port of the source-reading half of ArgueWinTests/AttributionTests.swift (amendment at; amendment az: no ATT on
// Android). `startsOnlyWithARealKeyOutsideDemoAndTests`, `notRunningUnderTests`, `waitsSixtySecondsForTheATTAnswer`
// and `forwardsASmallNonPersonalAllowList` were ported with their subject in wave 2a
// (`services/EdgeFunctionsTests.kt`, class `AttributionTests`).
package app.plead.android.features.paywall

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AppsFlyer attribution + the RevenueCat link (CONTRACTS-v2 amendment at). */
class AttributionTests {
    /** The `android/app` module directory (unit tests run there; the fallback covers a run from `android/`). */
    private val module: File = listOf(File("."), File("app")).first { File(it, "src/main").exists() }

    private fun read(path: String): String = File(module, path).readText()

    /** Keys come from local.properties through BuildConfig, never from source (iOS: Info.plist substitution). */
    @Test fun configuredThroughBuildConfig() {
        val gradle = read("build.gradle.kts")
        assertTrue(gradle.contains("buildConfigField(\"String\", \"APPSFLYER_DEV_KEY\""))
        val example = read("../local.properties.example")
        assertTrue(example.contains("APPSFLYER_DEV_KEY=YOUR_APPSFLYER_DEV_KEY"))
        val source = read("src/main/java/app/plead/android/services/AttributionService.kt")
        assertTrue(source.contains("AppConfig.appsFlyerDevKey"))
        assertFalse(source.contains("setCustomerUserId"))
        assertFalse(source.contains("customerUserID"))
    }

    /** iOS: app target only, never the widget extension. Android: the Glance widgets never touch AppsFlyer. */
    @Test fun appsFlyerIsLinkedToTheAppOnly() {
        val gradle = read("build.gradle.kts")
        assertTrue(gradle.contains("implementation(libs.appsflyer)"))
        val widgets = File(module, "src/main/java/app/plead/android/widgets")
        widgets.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach {
            assertFalse(it.name, it.readText().contains("appsflyer", ignoreCase = true))
        }
    }

    /** The RevenueCat ↔ AppsFlyer link: identifiers + AppsFlyer id after configure / logIn. */
    @Test fun revenueCatReceivesTheAppsFlyerID() {
        val src = read("src/main/java/app/plead/android/services/PurchasesService.kt")
        assertTrue(src.contains("Purchases.sharedInstance.collectDeviceIdentifiers()"))
        assertTrue(src.contains("Purchases.sharedInstance.setAppsflyerID(uid)"))
        val model = read("src/main/java/app/plead/android/app/AppModel.kt")
        assertTrue(model.contains("purchases.syncAttribution()"))
    }
}
