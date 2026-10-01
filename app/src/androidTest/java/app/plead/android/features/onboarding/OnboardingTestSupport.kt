// Compose counterpart of ArgueWinUITests/PleadUITestCase.swift for the onboarding and partner-code suites: the launch
// flags, a "launch" that builds the AppModel the way MainActivity + PleadApplication do from those flags, and the
// element helpers (`waitFor`, `tap`, `type`, `onboardingStep`, `passMockTrial`, `walkOnboarding`…).
//
// Differences from XCUITest, on purpose:
// - "Launch" builds a fresh demo AppModel (DemoHarness.model + coldOpen.launch + start + DemoHarness.apply) and swaps
//   it into one RootScreen. A "relaunch" keeps the same in-memory UserDefaults, like iOS keeps its defaults between
//   launches; every test starts on fresh defaults, so the iOS argument-domain overrides for the demo anonymous user
//   (`-onboarding.<anonId>.completed NO`, `.step 0`) are not needed.
// - The Compose main clock is driven by hand (`autoAdvance = false`): every wait advances it a few frames per poll.
//   The link celebration's floating hearts run a frame loop forever, which would never let an auto-advancing clock
//   go idle; a manual clock also makes the mock trial deterministic (a busy screen cannot fast-forward the trial
//   past the beat a test is waiting for while the test is looking it up).
// - Elements are matched like `PleadUITestCase.element(_:)`: test tag, or content description / text equal to the
//   key ignoring case, searched in the unmerged tree of every root (dialogs and sheets included), first match in
//   tree order.
package app.plead.android.features.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.plead.android.app.AppModel
import app.plead.android.app.DemoHarness
import app.plead.android.app.LaunchArguments
import app.plead.android.app.RootScreen
import app.plead.android.app.apply
import app.plead.android.app.model
import app.plead.android.designsystem.PleadTheme
import app.plead.android.features.paywall.PaywallOpeningRule
import app.plead.android.services.PreviewData
import app.plead.android.services.UserDefaults
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

class OnboardingTestSupport(private val rule: ComposeContentTestRule) {
    companion object {
        /** Generous default for waits (seconds), as `PleadUITestCase.defaultTimeout`. */
        const val defaultTimeout: Double = 10.0

        /** Virtual time the main clock moves per poll (three frames). */
        const val pollStepMillis: Long = 48

        /** Flags every launch gets (per-test flags come after and may override them: the last value wins). */
        val baseArguments: List<String> = listOf(
            "AWDemo", "YES",
            "AWColdOpen", "none",
            "AWPaywallOpening", "NO",
            "AWNoReviewPrompt", "YES",
            // Kept for parity with iOS (amendment at); Android has no ATT prompt (amendment az).
            "AWNoATTPrompt", "YES",
            "AWDemoSpeed", "fast",
        )

        /**
         * A fresh signed-out user walking onboarding deterministically (`PleadUITestCase.newUserArguments` minus the
         * argument-domain overrides for the demo anonymous user, see the file header).
         */
        val newUserArguments: List<String> = listOf(
            "AWDemoStore", "signedOut",
            "AWPermissions", "fresh",
            "AWWidgetDetected", "NO",
        )

        /** `PreviewData.anonId`: the anonymous user SAVE MY IDENTITY creates in demo runs. */
        val demoAnonymousUserId: String get() = PreviewData.anonId.toString()
    }

    /** `PleadUITestCase.Onboarding`, with the Android differences noted (STATUS.md, wave 3b decisions). */
    object Onboarding {
        const val welcome = "Settle arguments. Let AI judge."
        const val mockTrial = "onboarding.mockTrial"
        const val mockTrialStart = "onboarding.mockTrial.start"
        const val mockTrialSkip = "onboarding.mockTrial.skip"
        const val mockTrialContinue = "onboarding.mockTrial.continue"
        /**
         * iOS finds the CLAIM card, the exhibits, the progress rail and the versus card by identifier. On Android those
         * `testTag`s sit after `clearAndSetSemantics` on the same node, which clears them (they never reach the
         * semantics tree), so these are matched by their content descriptions (see [claimCard], [exhibitCard],
         * [progressRail], [versusCard]).
         */
        const val mockClaim = "onboarding.mockTrial.claim"
        const val mockClosedTitle = "That's a Plead trial."
        const val mockHelp = "court.help.mockOpeningStatement"
        const val helpSheet = "court.help.sheet"
        const val helpGotIt = "court.help.gotIt"
        const val summonsIntro = "onboarding.summonsIntro"
        const val summonsIntroContinue = "onboarding.summonsIntro.continue"
        const val summonsHeadline = "Your turn to summon them."
        const val howItWorks = "Three steps to a verdict."
        const val aiCourt = "One judge. Multiple opinions."
        const val examples = "What's going to court first?"
        const val identity = "How should the court know you?"
        const val partner = "Every case needs another side."
        const val notifications = "Don't miss your summons."
        const val widgets = "Court follows you."
        /** The iOS Privacy & tracking screen's headline: never routed to on Android (amendment az). */
        const val tracking = "Your arguments stay between you."
        const val ready = "Court is now in session"
        const val readyFileCase = "onboarding.ready.fileCase"

        /** Android: eleven active screens (no Privacy & tracking step). */
        const val screenCount = 11
    }

    /** The CLAIM card (`onboarding.mockTrial.claim`): "Claim: Alex ate the final slice after agreeing to save it." */
    val claimCard: SemanticsMatcher = hasContentDescription(
        "${MockTrialScript.sentence(MockTrialScript.claimLabel)}: " +
            MockTrialScript.claimText,
    )

    /** Either exhibit card (`onboarding.mockTrial.exhibit`): "Exhibit A: text message from …" / "Exhibit B: photo. …". */
    val exhibitCard: SemanticsMatcher = SemanticsMatcher("an exhibit card") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { Regex("^Exhibit [AB]: ").containsMatchIn(it) }
    }

    /** The court progress rail (`onboarding.progress`): its description is exactly "Step N of M". */
    val progressRail: SemanticsMatcher = SemanticsMatcher("the progress rail") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { Regex("^Step \\d+ of \\d+$").matches(it) }
    }

    /** The partner step's case card (`onboarding.versusCard`): "Future case preview: … versus …". */
    val versusCard: SemanticsMatcher = hasContentDescription("Future case preview:", substring = true)

    /** `element("onboarding.progress").label`. */
    fun progressLabel(): String {
        assertTrue("The progress rail never appeared", waitUntil { exists(progressRail) })
        return nodes(progressRail).first().config[SemanticsProperties.ContentDescription].joinToString(" ")
    }

    /** `element(key).label` for a node found by `matcher`. */
    fun label(matcher: SemanticsMatcher, name: String): String {
        val node = nodes(matcher).firstOrNull() ?: run { fail("\"$name\" not found"); error("unreachable") }
        return (node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }).joinToString(" ")
    }

    /** Asserts a node matching `matcher` appears within `timeout` seconds. */
    fun waitFor(matcher: SemanticsMatcher, name: String, timeout: Double = defaultTimeout) {
        assertTrue("\"$name\" did not appear within $timeout s", waitUntil(timeout) { exists(matcher) })
    }

    /** One set of defaults per test: survives "relaunches" like iOS UserDefaults survive app launches. */
    val defaults: UserDefaults = UserDefaults.inMemory()

    private var current by mutableStateOf<AppModel?>(null)
    private var generation by mutableIntStateOf(0)
    private var contentSet = false

    /** The model of the current launch. */
    val app: AppModel get() = current ?: error("launch() first")

    // MARK: Launch

    /**
     * Launches (or relaunches) with the base flags plus `arguments` (`"AWDemoStore", "unpaid"`…, names without the
     * dash, as MainActivity's intent extras). Builds the model the way PleadApplication.appModel() and
     * MainActivity.onCreate do, then shows it in RootScreen (MainActivity's content).
     */
    fun launch(vararg arguments: String) = launch(arguments.toList())

    fun launch(arguments: List<String>) {
        val all = baseArguments + arguments
        require(all.size % 2 == 0) { "Flags come in name/value pairs: $all" }
        LaunchArguments.set(all.chunked(2).associate { it[0] to it[1] })
        PaywallOpeningRule.shownThisLaunch = false
        var made: AppModel? = null
        rule.runOnUiThread {
            val model = DemoHarness.model(defaults)
            model.coldOpen.launch(forced = DemoHarness.coldOpen, demo = DemoHarness.isDemo, reduceMotion = false)
            model.start()
            DemoHarness.apply(to = model, defaults = defaults)
            made = model
        }
        if (!contentSet) {
            contentSet = true
            rule.mainClock.autoAdvance = false
            current = made
            rule.setContent {
                PleadTheme {
                    val model = current
                    if (model != null) key(generation) { RootScreen(model) }
                }
            }
        } else {
            rule.runOnUiThread {
                current = made
                generation += 1
            }
        }
        step(2)
    }

    /** Clears the process-wide flags a launch set (call from @After). */
    fun tearDown() {
        LaunchArguments.set(emptyMap())
        PaywallOpeningRule.shownThisLaunch = false
    }

    // MARK: Clock

    /** Advances the main clock `frames` frames (the Compose clock is manual in these tests). */
    fun step(frames: Int = 1) {
        repeat(frames) { rule.mainClock.advanceTimeByFrame() }
    }

    /** Polls `condition`, moving the clock [pollStepMillis] between polls, until it holds or `timeout` seconds pass. */
    fun waitUntil(timeout: Double = defaultTimeout, condition: () -> Boolean): Boolean {
        val end = System.nanoTime() + (timeout * 1e9).toLong()
        while (true) {
            if (condition()) return true
            if (System.nanoTime() > end) return condition()
            rule.mainClock.advanceTimeBy(pollStepMillis)
            // Real time for what runs off the Compose clock (viewModelScope on Dispatchers.Main, auth callbacks).
            Thread.sleep(4)
        }
    }

    // MARK: Finding elements

    /** `element(_ key)`: test tag, or content description / text equal to `key` (case-insensitive). */
    fun matcher(key: String): SemanticsMatcher =
        hasTestTag(key) or hasContentDescription(key, ignoreCase = true) or hasText(key, ignoreCase = true)

    /** `element(containing:)`: content description or text containing `text` (case-insensitive). */
    fun containing(text: String): SemanticsMatcher =
        hasContentDescription(text, substring = true, ignoreCase = true) or hasText(text, substring = true, ignoreCase = true)

    fun nodes(matcher: SemanticsMatcher, merged: Boolean = false): List<SemanticsNode> =
        rule.onAllNodes(matcher, useUnmergedTree = !merged).fetchSemanticsNodes(atLeastOneRootRequired = false)

    fun exists(key: String): Boolean = nodes(matcher(key)).isNotEmpty()
    fun exists(matcher: SemanticsMatcher): Boolean = nodes(matcher).isNotEmpty()

    /** iOS `exists && isEnabled` for the first match. */
    fun existsAndEnabled(key: String): Boolean = nodes(matcher(key)).firstOrNull()?.let(::isEnabled) ?: false

    private fun isEnabled(node: SemanticsNode): Boolean = !node.config.contains(SemanticsProperties.Disabled)

    /** Asserts `key` appears within `timeout` seconds. */
    fun waitFor(key: String, timeout: Double = defaultTimeout) {
        assertTrue("\"$key\" did not appear within $timeout s", waitUntil(timeout) { exists(key) })
    }

    /** Asserts something whose label contains `text` appears within `timeout` seconds. */
    fun waitForContaining(text: String, timeout: Double = defaultTimeout) {
        assertTrue("Nothing containing \"$text\" appeared within $timeout s", waitUntil(timeout) { exists(containing(text)) })
    }

    /** Asserts `key` is gone within `timeout` seconds. */
    fun waitForGone(key: String, timeout: Double = defaultTimeout) {
        assertTrue("\"$key\" was still on screen after $timeout s", waitUntil(timeout) { !exists(key) })
    }

    /** `element(key).label`: the merged node's content description and text, joined. */
    fun label(key: String): String {
        val node = nodes(matcher(key), merged = true).firstOrNull() ?: nodes(matcher(key)).firstOrNull()
            ?: run { fail("\"$key\" not found"); error("unreachable") }
        val parts = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        return parts.joinToString(" ")
    }

    /** `element(key).value` for a text field. */
    fun value(key: String): String? =
        nodes(matcher(key)).firstOrNull()?.config?.getOrNull(SemanticsProperties.EditableText)?.text

    // MARK: Actions

    /**
     * Waits for `key` to exist and be enabled (iOS: exists and hittable), scrolls it into view when it is outside its
     * window, then taps it.
     */
    fun tap(key: String, timeout: Double = defaultTimeout) = tap(matcher(key), key, timeout)

    fun tap(matcher: SemanticsMatcher, name: String, timeout: Double = defaultTimeout) {
        assertTrue("\"$name\" did not appear within $timeout s", waitUntil(timeout) { nodes(matcher).isNotEmpty() })
        assertTrue("\"$name\" never became enabled", waitUntil(timeout) { nodes(matcher).firstOrNull()?.let(::isEnabled) == true })
        if (!onScreen(nodes(matcher).first())) {
            runCatching { first(matcher).performScrollTo() }
            waitUntil(timeout) { nodes(matcher).firstOrNull()?.let(::onScreen) == true }
        }
        first(matcher).performClick()
        step()
    }

    /** Types into `key` (focusing it first), as `PleadUITestCase.type(_:into:)`. */
    fun type(text: String, into: String) {
        val m = matcher(into)
        assertTrue("\"$into\" did not appear within $defaultTimeout s", waitUntil { nodes(m).isNotEmpty() })
        first(m).performTextInput(text)
        step()
    }

    private fun first(matcher: SemanticsMatcher): SemanticsNodeInteraction =
        rule.onAllNodes(matcher, useUnmergedTree = true)[0]

    /** The node's centre lies inside its window (iOS `isHittable` without the occlusion test). */
    private fun onScreen(node: SemanticsNode): Boolean {
        var root = node
        while (true) root = root.parent ?: break
        val window = root.boundsInRoot
        val c = node.boundsInRoot.center
        return node.boundsInRoot.width > 0 && c.x in window.left..window.right && c.y in window.top..window.bottom
    }

    // MARK: Shared flows

    /** The tab bar is up (the gate let us into the app): the Home tab. */
    val homeTab: SemanticsMatcher = hasText("Home") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    fun assertTabs() {
        assertTrue("The tabs (Home) never appeared", waitUntil { tabsVisible() })
    }

    /** The Home tab is on screen now (`app.tabBars.buttons["Home"].exists`). */
    fun tabsVisible(): Boolean = nodes(homeTab, merged = true).isNotEmpty()

    fun tabsAppear(timeout: Double): Boolean = waitUntil(timeout) { tabsVisible() }

    /**
     * iOS `secureAccountWithApple()`. Android (amendment az): SecureAccountView offers Sign in with Google first and
     * hides Apple unless the project enables it, so the demo account is secured with Google (succeeds in memory).
     */
    fun secureAccountWithGoogle() {
        waitFor("Secure your court record")
        tap("secure.google")
        assertTabs()
    }

    /** Waits for the screen's headline, taps `cta`, waits until the headline leaves. */
    fun onboardingStep(headline: String, tapping: String) {
        waitFor(headline)
        tap(tapping)
        waitForGone(headline)
    }

    /** `passMockTrial(closedTimeout:)`: START, then CASE CLOSED's CTA (or SKIP DEMO), until the scene is gone. */
    fun passMockTrial(closedTimeout: Double = 90.0) {
        waitFor(Onboarding.mockTrial)
        val start = matcher(Onboarding.mockTrialStart)
        if (waitUntil(2.0) { nodes(start).firstOrNull()?.let { isEnabled(it) && onScreen(it) } == true }) {
            tap(Onboarding.mockTrialStart)
            waitUntil(closedTimeout) { exists(Onboarding.mockTrialContinue) }
        }
        val exit = listOf(Onboarding.mockTrialContinue, Onboarding.mockTrialSkip, "Skip demo")
            .firstOrNull { k -> nodes(matcher(k)).firstOrNull()?.let { isEnabled(it) && onScreen(it) } == true }
            ?: Onboarding.mockTrialSkip
        tap(exit)
        waitForGone(Onboarding.mockTrial)
    }

    /** `passSummonsIntro()`: the summons explainer's CONTINUE, until it is gone. */
    fun passSummonsIntro() {
        waitFor(Onboarding.summonsIntro)
        tap(Onboarding.summonsIntroContinue)
        waitForGone(Onboarding.summonsIntro)
    }

    /** `skipMockTrialFromInvitation()`: START MOCK TRIAL is offered; SKIP DEMO without starting. */
    fun skipMockTrialFromInvitation() {
        waitFor(Onboarding.mockTrial)
        waitFor(Onboarding.mockTrialStart)
        tap(Onboarding.mockTrialSkip)
        waitForGone(Onboarding.mockTrial)
    }

    /**
     * `walkOnboarding(name:)`: the eleven Android screens of a brand-new user, leaving the app at the gate. iOS passes
     * the Privacy & tracking screen between Court Notices and Widgets; Android has none (amendment az).
     */
    fun walkOnboarding(name: String = "Arif") {
        onboardingStep(Onboarding.welcome, tapping = "onboarding.begin")
        passMockTrial()
        passSummonsIntro()
        onboardingStep(Onboarding.howItWorks, tapping = "Continue")
        onboardingStep(Onboarding.aiCourt, tapping = "Continue")
        onboardingStep(Onboarding.examples, tapping = "Continue")
        waitFor(Onboarding.identity)
        type(name, into = "onboarding.name")
        onboardingStep(Onboarding.identity, tapping = "Save my identity")
        onboardingStep(Onboarding.partner, tapping = "I'll do this later")
        onboardingStep(Onboarding.notifications, tapping = "Not now")
        onboardingStep(Onboarding.widgets, tapping = "Not now")
        onboardingStep(Onboarding.ready, tapping = Onboarding.readyFileCase)
    }

    /** Android has no Privacy & tracking screen: asserts it never shows (where iOS `passPrivacy()` passes it). */
    fun assertNoPrivacyStep() {
        assertTrue("The Privacy & tracking screen showed on Android", !exists(Onboarding.tracking))
    }
}
