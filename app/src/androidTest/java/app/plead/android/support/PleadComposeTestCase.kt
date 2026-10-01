// The Android side of ArgueWinUITests/PleadUITestCase.swift, shared by every Compose UI test under `features/`: the
// base launch flags, a "launch" that builds the app the way PleadApplication + MainActivity do, and the XCUITest-style
// lookups, waits and taps. Onboarding walkers are in OnboardingFlows.kt.
//
// Mapping from XCUITest:
// - Launch: base flags + the test's flags (later values win) as `LaunchArguments`, then `DemoHarness.model()`,
//   `coldOpen.launch`, `start()`, `DemoHarness.apply(to:)` on an in-memory defaults suite, shown as MainActivity's
//   content (`RootScreen` + `WidgetPreviewOverlay`). A second `launch` in the same test is a relaunch: the previous
//   model's demo simulator is stopped and the defaults are kept, as iOS keeps UserDefaults between launches. Every test
//   starts on fresh defaults, so the iOS argument-domain overrides for the demo anonymous user
//   (`-onboarding.<anonId>.completed NO`, `.step 0`) are not needed.
// - iOS "identifier" = Compose `testTag`; iOS "label" = a node's content description and text (Compose buttons here
//   speak their title as a content description). `element(key)` matches either, ignoring case.
// - Lookups search the merged tree first and fall back to the unmerged one (a tag inside a merged button lives on the
//   merged node; a tag on a child of a merging parent only in the unmerged tree). Both cover every root (dialogs and
//   bottom sheets included).
// - `isHittable` = displayed (some of it inside its window) and enabled. Taps wait for a hittable match, scroll matches
//   that sit in a scroll container into view (XCUITest does this before tapping), and retry if the node left the tree
//   between lookup and click (the court dock re-renders as the simulated partner and the judge act).
// - Positions (`top` / `bottom`) are unclipped (`positionInRoot`): `boundsInRoot` is clipped by the scroll viewport.
//
// The clock: by default the Compose main clock advances by itself and waits poll with `ComposeTestRule.waitUntil`.
// Suites that pass `manualClock = true` (onboarding, partner code) drive it by hand: every poll moves it
// [POLL_STEP_MS] (three frames) and sleeps 4 ms of real time for work off the Compose clock. The link celebration's
// hearts run a frame loop forever, which never lets an auto-advancing clock go idle, and a manual clock keeps the mock
// trial deterministic (a busy screen cannot fast-forward it past the beat a test is waiting for).
package app.plead.android.support

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
import app.plead.android.services.UserDefaults
import app.plead.android.widgets.WidgetPreviewOverlay
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule

abstract class PleadComposeTestCase(
    /** Drive the Compose clock by hand (see the file header). */
    val manualClock: Boolean = false,
) {
    @get:Rule val rule = createComposeRule()

    /** One defaults suite per test: survives relaunches like iOS UserDefaults survive app launches. */
    val defaults: UserDefaults = UserDefaults.inMemory()

    private var current by mutableStateOf<AppModel?>(null)
    private var generation by mutableIntStateOf(0)
    private var contentSet = false

    /** The model of the current launch. */
    val model: AppModel get() = current ?: error("launch() first")

    companion object {
        /** `PleadUITestCase.defaultTimeout` (10 s). */
        const val DEFAULT_TIMEOUT_MS = 10_000L

        /** Virtual time a manual clock moves per poll (three frames). */
        const val POLL_STEP_MS = 48L

        /** `PleadUITestCase.baseArguments` (flag names without the dash, as MainActivity's intent extras). */
        val baseArguments: List<Pair<String, String>> = listOf(
            "AWDemo" to "YES",
            "AWColdOpen" to "none",
            "AWPaywallOpening" to "NO",
            "AWNoReviewPrompt" to "YES",
            // Kept for parity with iOS (amendment at); Android has no ATT prompt (amendment az).
            "AWNoATTPrompt" to "YES",
            "AWDemoSpeed" to "fast",
        )

        /**
         * `PleadUITestCase.newUserArguments`: a fresh signed-out user walking onboarding deterministically (without the
         * argument-domain overrides for the demo anonymous user, see the file header).
         */
        val newUserArguments: List<Pair<String, String>> = listOf(
            "AWDemoStore" to "signedOut",
            "AWPermissions" to "fresh",
            "AWWidgetDetected" to "NO",
        )
    }

    // MARK: Launch

    /** iOS `launch(_:)`: base flags plus [arguments] (the last value of a flag wins), then MainActivity's content. */
    fun launch(vararg arguments: Pair<String, String>): AppModel = launch(arguments.toList())

    fun launch(arguments: List<Pair<String, String>>): AppModel {
        LaunchArguments.set((baseArguments + arguments).toMap())
        PaywallOpeningRule.shownThisLaunch = false
        lateinit var made: AppModel
        rule.runOnUiThread {
            // A relaunch "kills" the previous app: its demo simulator stops.
            current?.store?.demo?.cancelAll()
            // PleadApplication.appModel() + MainActivity.onCreate: model → cold open → start() → DemoHarness.apply.
            made = DemoHarness.model(defaults)
            made.coldOpen.launch(forced = DemoHarness.coldOpen, demo = DemoHarness.isDemo, reduceMotion = false)
            made.start()
            DemoHarness.apply(to = made, defaults = defaults)
        }
        if (!contentSet) {
            contentSet = true
            if (manualClock) rule.mainClock.autoAdvance = false
            current = made
            rule.setContent {
                PleadTheme {
                    val shown = current
                    if (shown != null) {
                        key(generation) {
                            Box {
                                RootScreen(shown)
                                WidgetPreviewOverlay()
                            }
                        }
                    }
                }
            }
        } else {
            rule.runOnUiThread {
                current = made
                generation += 1
            }
        }
        if (manualClock) step(2)
        return made
    }

    /** Stops the demo simulator and clears the process-wide flags so nothing leaks into the next test. */
    @After fun tearDownPleadApp() {
        runCatching { rule.runOnUiThread { current?.store?.demo?.cancelAll() } }
        LaunchArguments.set(emptyMap())
        PaywallOpeningRule.shownThisLaunch = false
    }

    // MARK: Matchers (PleadUITestCase.element…)

    /** The content descriptions, texts and edited text of a node: what XCUITest calls its label (and value). */
    fun labels(node: SemanticsNode): List<String> =
        (node.config.getOrNull(SemanticsProperties.ContentDescription) ?: emptyList()) +
            (node.config.getOrNull(SemanticsProperties.Text) ?: emptyList()).map { it.text } +
            listOfNotNull(node.config.getOrNull(SemanticsProperties.EditableText)?.text)

    /** iOS `element(_:)`: test tag equals [key], or a label equals it (case-insensitive). */
    fun element(key: String): SemanticsMatcher = SemanticsMatcher("tag or label == \"$key\"") { n ->
        tag(n) == key || labels(n).any { it.equals(key, ignoreCase = true) }
    }

    /** iOS `element(containing:)`: a label contains [text] (case-insensitive). */
    fun containing(text: String): SemanticsMatcher = SemanticsMatcher("label contains \"$text\"") { n ->
        labels(n).any { it.contains(text, ignoreCase = true) }
    }

    /** iOS `element(beginningWith:)`: a label begins with [prefix] (case-insensitive). */
    fun beginningWith(prefix: String): SemanticsMatcher = SemanticsMatcher("label begins with \"$prefix\"") { n ->
        labels(n).any { it.startsWith(prefix, ignoreCase = true) }
    }

    /** A test tag only (iOS `app.buttons["id"]` on an identifier). */
    fun tag(id: String): SemanticsMatcher = SemanticsMatcher("tag == \"$id\"") { tag(it) == id }

    /** iOS `app.buttons`: a node with a click action. */
    fun button(matcher: SemanticsMatcher): SemanticsMatcher = matcher and hasClickAction()

    /** iOS `app.tabBars.buttons[label]`. */
    fun tabButton(label: String): SemanticsMatcher = SemanticsMatcher("tab \"$label\"") { n ->
        n.config.getOrNull(SemanticsProperties.Role) == Role.Tab && labels(n).any { it == label }
    }

    /** iOS `app.navigationBars[title]`: the nav bar title is a heading. */
    fun navigationBar(beginningWith: String): SemanticsMatcher = SemanticsMatcher("nav title begins with \"$beginningWith\"") { n ->
        n.config.contains(SemanticsProperties.Heading) && labels(n).any { it.startsWith(beginningWith) }
    }

    // MARK: Node properties

    fun tag(node: SemanticsNode): String? = node.config.getOrNull(SemanticsProperties.TestTag)

    /** XCUIElement `label`: the content description when there is one (iOS `accessibilityLabel`), else the text. */
    fun label(node: SemanticsNode): String {
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        return if (description.isNotEmpty()) description.joinToString(", ") else labels(node).joinToString(", ")
    }

    /** Everything a node reads out (descriptions and texts), for containers such as `home.primary.label`. */
    fun fullLabel(node: SemanticsNode): String = labels(node).joinToString(", ")

    /** [fullLabel] of the first node matching [key]. */
    fun fullLabel(key: String): String = fullLabel(node(element(key)))

    fun isSelected(node: SemanticsNode): Boolean = node.config.getOrNull(SemanticsProperties.Selected) == true

    fun isEnabled(node: SemanticsNode): Boolean = !node.config.contains(SemanticsProperties.Disabled)

    fun stateDescription(node: SemanticsNode): String = node.config.getOrNull(SemanticsProperties.StateDescription) ?: ""

    /** XCUIElement `frame.minY` in root pixels, unclipped (see the file header). */
    fun top(node: SemanticsNode): Float = node.positionInRoot.y

    /** XCUIElement `frame.maxY` in root pixels, unclipped. */
    fun bottom(node: SemanticsNode): Float = node.positionInRoot.y + node.size.height

    // MARK: Queries

    /** Which tree to search: the merged one when it has a match, else the unmerged one. */
    private fun useUnmergedTree(matcher: SemanticsMatcher): Boolean = semanticsNodes(matcher, unmerged = false).isEmpty()

    private fun semanticsNodes(matcher: SemanticsMatcher, unmerged: Boolean): List<SemanticsNode> = runCatching {
        rule.onAllNodes(matcher, useUnmergedTree = unmerged).fetchSemanticsNodes(atLeastOneRootRequired = false)
    }.getOrDefault(emptyList())

    /** Every node matching [matcher], from the merged tree, or the unmerged one when the merged tree has none. */
    fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
        semanticsNodes(matcher, unmerged = false).ifEmpty { semanticsNodes(matcher, unmerged = true) }

    /** The same nodes as [nodes], as interactions. */
    fun all(matcher: SemanticsMatcher): List<SemanticsNodeInteraction> {
        val unmerged = useUnmergedTree(matcher)
        val all = rule.onAllNodes(matcher, useUnmergedTree = unmerged)
        val count = semanticsNodes(matcher, unmerged).size
        return (0 until count).map { all[it] }
    }

    /** The first node matching [matcher] (fails when used if there is none). */
    fun first(matcher: SemanticsMatcher): SemanticsNodeInteraction =
        rule.onAllNodes(matcher, useUnmergedTree = useUnmergedTree(matcher))[0]

    /** The first matching node's semantics; fails the test if there is none. */
    fun node(matcher: SemanticsMatcher): SemanticsNode =
        nodes(matcher).firstOrNull() ?: run { fail("No node matches $matcher"); error("unreachable") }

    /** `exists`. */
    fun exists(matcher: SemanticsMatcher): Boolean = nodes(matcher).isNotEmpty()

    fun exists(key: String): Boolean = exists(element(key))

    /** iOS `exists && isEnabled` for the first match. */
    fun existsAndEnabled(key: String): Boolean = nodes(element(key)).firstOrNull()?.let(::isEnabled) ?: false

    /** XCUIElement `isHittable`: displayed (inside its window) and enabled. */
    fun isHittable(node: SemanticsNodeInteraction): Boolean = runCatching {
        node.isDisplayed() && isEnabled(node.fetchSemanticsNode())
    }.getOrDefault(false)

    /** The first matching node that is displayed and enabled. */
    fun hittable(matcher: SemanticsMatcher): SemanticsNodeInteraction? = all(matcher).firstOrNull(::isHittable)

    fun isHittable(matcher: SemanticsMatcher): Boolean = hittable(matcher) != null

    /** The edited text of the first matching text field (`element(key).value`). */
    fun editableText(matcher: SemanticsMatcher): String? {
        val fields = nodes(matcher and hasSetTextAction()).ifEmpty { nodes(matcher) }
        return fields.firstOrNull()?.config?.getOrNull(SemanticsProperties.EditableText)?.text
    }

    /** `element(key).value` for a text field. */
    fun value(key: String): String? = editableText(element(key))

    // MARK: Clock and waits

    /** Advances the main clock [frames] frames. */
    fun step(frames: Int = 1) {
        repeat(frames) { rule.mainClock.advanceTimeByFrame() }
    }

    /** `waitUntil(timeout:_:)`: polls [condition] (the clock keeps moving) until it holds or [timeoutMs] passes. */
    fun waitUntil(timeoutMs: Long = DEFAULT_TIMEOUT_MS, condition: () -> Boolean): Boolean {
        val check = { runCatching(condition).getOrDefault(false) }
        if (manualClock) return poll(timeoutMs, POLL_STEP_MS, check)
        return try {
            rule.waitUntil(timeoutMs) { check() }
            true
        } catch (_: ComposeTimeoutException) {
            check()
        }
    }

    /**
     * Like [waitUntil], but moves the clock [stepMs] per poll: for long scripted sequences (the mock trial's ~55 s of
     * autoplay) that iOS waits out in real time. With a manual clock this is [waitUntil].
     */
    fun waitUntilAdvancing(timeoutMs: Long, stepMs: Long = 100, condition: () -> Boolean): Boolean {
        val check = { runCatching(condition).getOrDefault(false) }
        return poll(timeoutMs, if (manualClock) POLL_STEP_MS else stepMs, check)
    }

    private fun poll(timeoutMs: Long, stepMs: Long, check: () -> Boolean): Boolean {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            if (check()) return true
            if (System.nanoTime() > end) return check()
            rule.mainClock.advanceTimeBy(stepMs)
            // Real time for what runs off the Compose clock (viewModelScope on Dispatchers.Main, auth callbacks).
            if (manualClock) Thread.sleep(4)
        }
    }

    /** Lets time pass without a condition (iOS `RunLoop.current.run(until:)`). */
    fun pause(ms: Long) {
        waitUntil(ms) { false }
    }

    /** `waitForExistence(timeout:)`. */
    fun waitForExistence(matcher: SemanticsMatcher, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean =
        waitUntil(timeoutMs) { exists(matcher) }

    /** `waitForNonExistence(timeout:)`. */
    fun waitForNonExistence(matcher: SemanticsMatcher, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean =
        waitUntil(timeoutMs) { !exists(matcher) }

    /** Asserts a node matching [matcher] appears; returns the first match. */
    fun waitFor(matcher: SemanticsMatcher, name: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): SemanticsNodeInteraction {
        assertTrue("\"$name\" did not appear within ${timeoutMs / 1000} s", waitForExistence(matcher, timeoutMs))
        return first(matcher)
    }

    /** `waitFor(_:timeout:)`: asserts [key] (tag or label) appears. */
    fun waitFor(key: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): SemanticsNodeInteraction = waitFor(element(key), key, timeoutMs)

    /** `waitFor(containing:timeout:)`. */
    fun waitForContaining(text: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): SemanticsNodeInteraction {
        val matcher = containing(text)
        assertTrue("Nothing containing \"$text\" appeared within ${timeoutMs / 1000} s", waitForExistence(matcher, timeoutMs))
        return first(matcher)
    }

    fun waitForGone(matcher: SemanticsMatcher, name: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        assertTrue("\"$name\" was still on screen after ${timeoutMs / 1000} s", waitForNonExistence(matcher, timeoutMs))
    }

    /** `waitForGone(_:timeout:)`. */
    fun waitForGone(key: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS) = waitForGone(element(key), key, timeoutMs)

    // MARK: Acting

    /** `tap(_:timeout:)`: waits for [key] (tag or label) to exist and be hittable, then clicks it. */
    fun tap(key: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS) = tap(element(key), key, timeoutMs)

    /**
     * `tap(_:named:timeout:)`: waits for a match, then for one that is hittable (scrolling matches in a scroll container
     * into view), and clicks it; a node that left the tree before the click is looked up again.
     */
    fun tap(matcher: SemanticsMatcher, name: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        assertTrue("\"$name\" did not appear within ${timeoutMs / 1000} s", waitForExistence(matcher, timeoutMs))
        assertTrue("\"$name\" never became hittable", waitUntil(timeoutMs) {
            val target = hittable(matcher) ?: scrolledIntoView(matcher)
            target != null && tryClick(target)
        })
        if (manualClock) step()
    }

    /** The first enabled match that lies in a scroll container, scrolled to, if it is then hittable. */
    private fun scrolledIntoView(matcher: SemanticsMatcher): SemanticsNodeInteraction? {
        for (node in all(matcher)) {
            val enabled = runCatching { isEnabled(node.fetchSemanticsNode()) }.getOrDefault(false)
            if (!enabled) continue
            if (runCatching { node.performScrollTo() }.isSuccess && isHittable(node)) return node
        }
        return null
    }

    /** Clicks [node] unless it left the tree since it was found; false if the click could not be delivered. */
    fun tryClick(node: SemanticsNodeInteraction): Boolean = runCatching { node.performClick() }.isSuccess

    /** Scrolls the first match into view when it sits in a scroll container (a no-op otherwise). */
    fun scrollTo(matcher: SemanticsMatcher) {
        runCatching { first(matcher).performScrollTo() }
    }

    /** iOS `swipeThrough(to:)`: brings [key] on screen inside its scroll container and asserts it is hittable. */
    fun swipeThrough(key: String): SemanticsMatcher {
        val matcher = element(key)
        assertTrue("\"$key\" did not appear", waitForExistence(matcher))
        scrollTo(matcher)
        assertTrue("Scrolled without reaching \"$key\"", waitUntil(2_000) { isHittable(matcher) })
        return matcher
    }

    /** iOS `type(_:into:)`: taps the field (a match that accepts text, when there is one), then types. */
    fun type(text: String, into: String) {
        val field = element(into) and hasSetTextAction()
        val matcher = if (waitForExistence(field)) field else element(into)
        tap(matcher, into)
        first(matcher).performTextInput(text)
        if (manualClock) step()
    }

    // MARK: Gate flows

    /** iOS `assertTabs`: the tab bar is up (the gate let us into the app). */
    fun assertTabs() {
        assertTrue("The tabs (Home) never appeared", waitUntil { tabsVisible() })
    }

    /** The Home tab is on screen now (`app.tabBars.buttons["Home"].exists`). */
    fun tabsVisible(): Boolean = exists(tabButton("Home"))

    fun tabsAppear(timeoutMs: Long): Boolean = waitUntil(timeoutMs) { tabsVisible() }

    /** On SecureAccountView, taps the demo "Sign in with Apple" (succeeds in memory) and lands on the tabs. */
    fun secureAccountWithApple() {
        waitFor("Secure your court record")
        tap("secure.apple")
        assertTabs()
    }

    /**
     * iOS `secureAccountWithApple()` where the Android flow matters (amendment az): SecureAccountView offers Sign in with
     * Google first, so the demo account is secured with Google (succeeds in memory).
     */
    fun secureAccountWithGoogle() {
        waitFor("Secure your court record")
        tap("secure.google")
        assertTabs()
    }

    /** The purchase CTA: "START 3-DAY FREE TRIAL" / "CONTINUE WITH ANNUAL" / "CONTINUE , £9.99/WEEK". */
    fun purchaseCTA(): SemanticsMatcher = button(
        SemanticsMatcher("label begins with START or CONTINUE") { n ->
            labels(n).any { it.startsWith("START", ignoreCase = true) || it.startsWith("CONTINUE", ignoreCase = true) }
        },
    )
}
