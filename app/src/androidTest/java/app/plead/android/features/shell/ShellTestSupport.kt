// The Android side of ArgueWinUITests/PleadUITestCase.swift for the shell suites (paywall, partner paid, Home,
// docket, Settings, Us): launch flags, element lookup and the shared flows.
//
// iOS launches the app with `-AWDemo YES …`; here a test builds the model the way MainActivity does for those flags
// (`LaunchArguments` + `DemoHarness.model()` + `start()` + `DemoHarness.apply`) on an in-memory defaults suite, and
// mounts the real `RootScreen` in a compose rule. XCUITest's "identifier or label" maps to "test tag, text or content
// description" (case-insensitive), so tests keep the iOS keys (`home.primary.label`, "No thanks, not now").
package app.plead.android.features.shell

import android.os.SystemClock
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
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
import app.plead.android.services.UserDefaults
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule

abstract class ShellUITestCase {
    @get:Rule val rule = createComposeRule()

    lateinit var model: AppModel
        private set

    companion object {
        /** Generous default for waits, as iOS (`defaultTimeout`, 10 s). */
        const val defaultTimeout = 10_000L

        /** `PleadUITestCase.baseArguments` (flag names without the dash, as MainActivity's intent extras). */
        val baseArguments = listOf(
            "AWDemo" to "YES",
            "AWColdOpen" to "none",
            "AWPaywallOpening" to "NO",
            "AWNoReviewPrompt" to "YES",
            "AWNoATTPrompt" to "YES",
            "AWDemoSpeed" to "fast",
        )

        /**
         * `PleadUITestCase.newUserArguments`. The two `onboarding.<anon>.completed/step` overrides are not needed: every
         * launch here starts on a fresh in-memory defaults suite (and DemoHarness resets the anonymous scope).
         */
        val newUserArguments = listOf("AWDemoStore" to "signedOut", "AWPermissions" to "fresh", "AWWidgetDetected" to "NO")
    }

    @After fun clearLaunchArguments() {
        LaunchArguments.set(emptyMap())
    }

    // MARK: Launch

    /** Base flags plus [arguments] (the last value of a flag wins), then RootScreen on the demo model. */
    fun launch(vararg arguments: Pair<String, String>): AppModel = launch(arguments.toList())

    fun launch(arguments: List<Pair<String, String>>): AppModel {
        LaunchArguments.set((baseArguments + arguments).toMap())
        val defaults = UserDefaults.inMemory()
        lateinit var made: AppModel
        rule.runOnUiThread {
            // MainActivity.onCreate: PleadApplication.appModel() → start() → DemoHarness.apply(to:).
            made = DemoHarness.model(defaults)
            made.coldOpen.launch(forced = DemoHarness.coldOpen, demo = true, reduceMotion = false)
            made.start()
            DemoHarness.apply(to = made, defaults = defaults)
        }
        model = made
        rule.setContent { PleadTheme { RootScreen(made) } }
        return made
    }

    // MARK: Finding nodes

    /** Content descriptions, then texts (merged tree): what XCUITest calls the label. */
    fun labels(node: SemanticsNode): List<String> =
        (node.config.getOrNull(SemanticsProperties.ContentDescription) ?: emptyList()) +
            (node.config.getOrNull(SemanticsProperties.Text) ?: emptyList()).map { it.text } +
            listOfNotNull(node.config.getOrNull(SemanticsProperties.EditableText)?.text)

    /** XCUIElement `label`: the content description when there is one (iOS `accessibilityLabel`), else the text. */
    fun label(node: SemanticsNode): String {
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        return if (description.isNotEmpty()) description.joinToString(", ") else labels(node).joinToString(", ")
    }

    /** Everything a merged node reads out (descriptions and texts), for containers such as `home.primary.label`. */
    fun fullLabel(node: SemanticsNode): String = labels(node).joinToString(", ")

    fun tag(node: SemanticsNode): String? = node.config.getOrNull(SemanticsProperties.TestTag)

    fun isSelected(node: SemanticsNode): Boolean = node.config.getOrNull(SemanticsProperties.Selected) == true

    fun stateDescription(node: SemanticsNode): String = node.config.getOrNull(SemanticsProperties.StateDescription) ?: ""

    /** iOS `element(key)`: test tag equals [key], or a label equals it (case-insensitive). */
    fun element(key: String) = SemanticsMatcher("tag or label == \"$key\"") { n ->
        tag(n) == key || labels(n).any { it.equals(key, ignoreCase = true) }
    }

    /** iOS `element(containing:)`. */
    fun containing(text: String) = SemanticsMatcher("label contains \"$text\"") { n ->
        labels(n).any { it.contains(text, ignoreCase = true) }
    }

    /** iOS `element(beginningWith:)`. */
    fun beginningWith(prefix: String) = SemanticsMatcher("label begins with \"$prefix\"") { n ->
        labels(n).any { it.startsWith(prefix, ignoreCase = true) }
    }

    /** iOS `app.buttons`: a tappable node. */
    fun button(matcher: SemanticsMatcher) = matcher and hasClickAction()

    /** iOS `app.tabBars.buttons[label]`. */
    fun tabButton(label: String) = SemanticsMatcher("tab \"$label\"") { n ->
        n.config.getOrNull(SemanticsProperties.Role) == Role.Tab && labels(n).any { it == label }
    }

    /** iOS `app.navigationBars[title]`: the nav bar title is a heading. */
    fun navigationBar(beginningWith: String) = SemanticsMatcher("nav title begins with \"$beginningWith\"") { n ->
        n.config.contains(SemanticsProperties.Heading) && labels(n).any { it.startsWith(beginningWith) }
    }

    fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
        rule.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false)

    fun exists(matcher: SemanticsMatcher): Boolean = nodes(matcher).isNotEmpty()

    fun exists(key: String): Boolean = exists(element(key))

    fun first(matcher: SemanticsMatcher): SemanticsNodeInteraction = rule.onAllNodes(matcher).onFirst()

    fun node(matcher: SemanticsMatcher): SemanticsNode = first(matcher).fetchSemanticsNode()

    /**
     * XCUIElement `frame.minY` / `maxY`, in root pixels. Unclipped (`positionInRoot`): `boundsInRoot` is clipped by the
     * scroll viewport, so a node below the fold would report an empty rect at 0.
     */
    fun top(node: SemanticsNode): Float = node.positionInRoot.y

    fun bottom(node: SemanticsNode): Float = node.positionInRoot.y + node.size.height

    /** XCUIElement `isHittable`: on screen (inside the visible bounds of its window). */
    fun isHittable(matcher: SemanticsMatcher): Boolean =
        exists(matcher) && runCatching { first(matcher).assertIsDisplayed() }.isSuccess

    // MARK: Waiting

    /** Polls [condition] (advancing the compose clock frame by frame) until it holds or [timeout] passes. */
    fun waitUntil(timeout: Long = defaultTimeout, condition: () -> Boolean): Boolean = try {
        rule.waitUntil(timeout) { condition() }
        true
    } catch (e: ComposeTimeoutException) {
        condition()
    }

    /**
     * Like [waitUntil], but moves the compose clock [step] ms per poll: for long scripted sequences (the mock trial's
     * ~55 s of autoplay) that iOS simply waits out in real time.
     */
    fun waitUntilAdvancing(timeout: Long, step: Long = 100, condition: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeout
        while (true) {
            if (condition()) return true
            if (SystemClock.uptimeMillis() > end) return false
            rule.mainClock.advanceTimeBy(step)
        }
    }

    fun waitFor(matcher: SemanticsMatcher, name: String, timeout: Long = defaultTimeout): SemanticsNodeInteraction {
        assertTrue("\"$name\" did not appear within ${timeout / 1000} s", waitUntil(timeout) { exists(matcher) })
        return first(matcher)
    }

    /** iOS `waitFor(_ key:)`. */
    fun waitFor(key: String, timeout: Long = defaultTimeout): SemanticsNodeInteraction = waitFor(element(key), key, timeout)

    /** iOS `waitFor(containing:)`. */
    fun waitForContaining(text: String, timeout: Long = defaultTimeout): SemanticsNodeInteraction {
        val m = containing(text)
        assertTrue("Nothing containing \"$text\" appeared within ${timeout / 1000} s", waitUntil(timeout) { exists(m) })
        return first(m)
    }

    fun waitForGone(matcher: SemanticsMatcher, name: String, timeout: Long = defaultTimeout) {
        assertTrue("\"$name\" was still on screen after ${timeout / 1000} s", waitUntil(timeout) { !exists(matcher) })
    }

    /** iOS `waitForGone(_ key:)`. */
    fun waitForGone(key: String, timeout: Long = defaultTimeout) = waitForGone(element(key), key, timeout)

    // MARK: Acting

    /** Scrolls the node into view when it sits in a scroll container (a no-op otherwise). */
    fun scrollTo(matcher: SemanticsMatcher) {
        runCatching { first(matcher).performScrollTo() }
    }

    /** iOS `tap`: waits for the node, brings it on screen, waits until it is hittable, then taps it. */
    fun tap(matcher: SemanticsMatcher, name: String, timeout: Long = defaultTimeout) {
        assertTrue("\"$name\" did not appear within ${timeout / 1000} s", waitUntil(timeout) { exists(matcher) })
        scrollTo(matcher)
        assertTrue("\"$name\" never became hittable", waitUntil(timeout) { isHittable(matcher) })
        first(matcher).performClick()
    }

    fun tap(key: String, timeout: Long = defaultTimeout) = tap(element(key), key, timeout)

    /** iOS `swipeThrough(to:)`: brings [key] on screen inside its scroll container and asserts it is hittable. */
    fun swipeThrough(key: String): SemanticsMatcher {
        val m = element(key)
        assertTrue("\"$key\" did not appear", waitUntil { exists(m) })
        scrollTo(m)
        assertTrue("Scrolled without reaching \"$key\"", waitUntil(2_000) { isHittable(m) })
        return m
    }

    /** iOS `type(_:into:)`: focuses the field, then types. */
    fun type(text: String, into: String) {
        tap(into)
        first(element(into)).performTextInput(text)
    }

    // MARK: Shared flows

    /** iOS `assertTabs`: the tab bar is up (the gate let us into the app). */
    fun assertTabs() {
        assertTrue("The tabs (Home) never appeared", waitUntil { exists(tabButton("Home")) })
    }

    /** On SecureAccountView, taps the demo "Sign in with Apple" (succeeds in memory) and lands on the tabs. */
    fun secureAccountWithApple() {
        waitFor("Secure your court record")
        tap("secure.apple")
        assertTabs()
    }

    /** The purchase CTA: "START 3-DAY FREE TRIAL" / "CONTINUE WITH ANNUAL" / "CONTINUE , £9.99/WEEK". */
    fun purchaseCTA(): SemanticsMatcher = button(
        SemanticsMatcher("label begins with START or CONTINUE") { n ->
            labels(n).any { it.startsWith("START", ignoreCase = true) || it.startsWith("CONTINUE", ignoreCase = true) }
        },
    )

    /** An onboarding screen: waits for its headline, taps [cta], waits until the headline leaves. */
    fun onboardingStep(headline: String, tapping: String) {
        waitFor(headline)
        tap(tapping)
        waitForGone(headline)
    }

    /**
     * Screen 2, the Mock Trial Demo: START MOCK TRIAL, then up to [closedTimeout] for CASE CLOSED's CTA, falling back to
     * SKIP DEMO; waits until the scene is gone (both exits land on the summons explainer).
     */
    fun passMockTrial(closedTimeout: Long = 90_000) {
        waitFor(Onboarding.mockTrial)
        val start = element(Onboarding.mockTrialStart)
        if (waitUntil(2_000) { exists(start) } && waitUntil(2_000) { isHittable(start) }) {
            first(start).performClick()
            waitUntilAdvancing(closedTimeout) { exists(element(Onboarding.mockTrialContinue)) }
        }
        val exit = listOf(Onboarding.mockTrialContinue, Onboarding.mockTrialSkip, "Skip demo")
            .map(::element)
            .firstOrNull { isHittable(it) } ?: element(Onboarding.mockTrialSkip)
        tap(exit, "Mock trial continue / skip")
        waitForGone(Onboarding.mockTrial)
    }

    /** Screen 3, the summons explainer: CONTINUE, then gone (How Plead Works follows). */
    fun passSummonsIntro() {
        waitFor(Onboarding.summonsIntro)
        tap(Onboarding.summonsIntroContinue)
        waitForGone(Onboarding.summonsIntro)
    }

    /**
     * Every onboarding screen of a brand-new user (`AWDemoStore signedOut`), leaving the app at the gate. Android has
     * no Privacy & tracking step (amendment az: no ATT), so the 11-screen flow goes Notifications → Widgets.
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

    object Onboarding {
        /** TalkBack reads the two-line headline as one line (`CourtHeadline.spoken`). */
        const val welcome = "Settle arguments. Let AI judge."
        const val mockTrial = "onboarding.mockTrial"
        const val mockTrialStart = "onboarding.mockTrial.start"
        const val mockTrialSkip = "onboarding.mockTrial.skip"
        const val mockTrialContinue = "onboarding.mockTrial.continue"
        const val summonsIntro = "onboarding.summonsIntro"
        const val summonsIntroContinue = "onboarding.summonsIntro.continue"
        const val howItWorks = "Three steps to a verdict."
        const val aiCourt = "One judge. Multiple opinions."
        const val examples = "What's going to court first?"
        const val identity = "How should the court know you?"
        const val partner = "Every case needs another side."
        const val notifications = "Don't miss your summons."
        const val widgets = "Court follows you."
        const val ready = "Court is now in session"
        const val readyFileCase = "onboarding.ready.fileCase"
    }
}
