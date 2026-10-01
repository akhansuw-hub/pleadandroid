// The Android side of ArgueWinUITests/PleadUITestCase.swift for the court / trial / settlement / filing Compose tests:
// the same base launch flags, an app built the way MainActivity builds it from those flags (DemoHarness.model +
// DemoHarness.apply), and the same lookups (identifier-or-label, label contains, label begins with) and waits.
//
// iOS "identifier" = Compose `testTag`; iOS "label" = a node's text or content description (Compose buttons in this app
// speak their title as a content description). Lookups search the merged tree first, then the unmerged one (a tag
// set inside a merged button lives on the merged node; a tag on a child of a merging parent only in the unmerged tree).
package app.plead.android.features.court

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.plead.android.app.AppModel
import app.plead.android.app.DemoHarness
import app.plead.android.app.LaunchArguments
import app.plead.android.app.MainTabEffects
import app.plead.android.app.MainTabScreen
import app.plead.android.app.apply
import app.plead.android.app.model
import app.plead.android.designsystem.PleadTheme
import app.plead.android.services.UserDefaults
import org.junit.Assert.assertTrue

/** One test's app: launch flags in, a demo model out, the real tab shell on screen. */
class PleadComposeApp(private val rule: ComposeContentTestRule) {
    var model: AppModel? = null
        private set

    /**
     * iOS `launch(_:)`: base flags + [arguments] (later values win), then the model MainActivity would build
     * (`DemoHarness.model()`, `start()`, `DemoHarness.apply(to:)`) on in-memory defaults, mounted as the tab shell plus
     * its effects (summons cover, settlement prompts, sheet hand-offs).
     */
    fun launch(vararg arguments: Pair<String, String>): AppModel {
        LaunchArguments.set(BASE_ARGUMENTS + arguments)
        val defaults = UserDefaults.inMemory()
        val made = DemoHarness.model(defaults)
        made.start()
        DemoHarness.apply(to = made, defaults = defaults)
        model = made
        rule.setContent {
            PleadTheme {
                MainTabScreen(made)
                MainTabEffects(made)
            }
        }
        return made
    }

    /** Stops the demo simulator and clears the flags so nothing leaks into the next test. */
    fun tearDown() {
        model?.store?.demo?.cancelAll()
        LaunchArguments.set(emptyMap())
    }

    // MARK: Matchers (PleadUITestCase.element…)

    /** `element(_:)`: test tag equals [key], or a text / content description equals it (case-insensitive). */
    fun element(key: String): SemanticsMatcher = SemanticsMatcher("tag or label == \"$key\"") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag) == key || labels(node.config).any { it.equals(key, ignoreCase = true) }
    }

    /** `element(containing:)`: a text / content description contains [text] (case-insensitive). */
    fun containing(text: String): SemanticsMatcher = SemanticsMatcher("label contains \"$text\"") { node ->
        labels(node.config).any { it.contains(text, ignoreCase = true) }
    }

    /** `element(beginningWith:)`: a text / content description begins with [prefix] (case-insensitive). */
    fun beginningWith(prefix: String): SemanticsMatcher = SemanticsMatcher("label begins with \"$prefix\"") { node ->
        labels(node.config).any { it.startsWith(prefix, ignoreCase = true) }
    }

    /** A test tag only (iOS `app.buttons["id"]`). */
    fun tag(id: String): SemanticsMatcher = SemanticsMatcher("tag == \"$id\"") { it.config.getOrNull(SemanticsProperties.TestTag) == id }

    // MARK: Queries

    /** Every node matching [matcher], merged tree first, then the unmerged one. */
    fun nodes(matcher: SemanticsMatcher): List<SemanticsNodeInteraction> {
        for (unmerged in listOf(false, true)) {
            val all = rule.onAllNodes(matcher, useUnmergedTree = unmerged)
            val count = runCatching { all.fetchSemanticsNodes(atLeastOneRootRequired = false).size }.getOrDefault(0)
            if (count > 0) return (0 until count).map { all[it] }
        }
        return emptyList()
    }

    /** `exists`. */
    fun exists(matcher: SemanticsMatcher): Boolean = nodes(matcher).isNotEmpty()

    /** `isHittable`: on screen and enabled. */
    fun isHittable(node: SemanticsNodeInteraction): Boolean = runCatching {
        val n = node.fetchSemanticsNode()
        node.isDisplayed() && !n.config.contains(SemanticsProperties.Disabled)
    }.getOrDefault(false)

    /** The first matching node that is on screen and enabled (iOS `exists && isHittable && isEnabled`). */
    fun hittable(matcher: SemanticsMatcher): SemanticsNodeInteraction? = nodes(matcher).firstOrNull { isHittable(it) }

    // MARK: Waits

    /** `waitUntil(timeout:_:)`: polls [condition] (the clock keeps advancing) until it holds or [timeoutMs] passes. */
    fun waitUntil(timeoutMs: Long = DEFAULT_TIMEOUT_MS, condition: () -> Boolean): Boolean = try {
        rule.waitUntil(timeoutMs) { runCatching(condition).getOrDefault(false) }
        true
    } catch (_: ComposeTimeoutException) {
        runCatching(condition).getOrDefault(false)
    }

    /** `waitForExistence(timeout:)`. */
    fun waitForExistence(matcher: SemanticsMatcher, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean = waitUntil(timeoutMs) { exists(matcher) }

    /** `waitForNonExistence(timeout:)`. */
    fun waitForNonExistence(matcher: SemanticsMatcher, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean = waitUntil(timeoutMs) { !exists(matcher) }

    /** Lets time pass without a condition (iOS `RunLoop.current.run(until:)`). */
    fun pause(ms: Long) {
        waitUntil(ms) { false }
    }

    /** `waitFor(_:timeout:)`: asserts [key] (tag or label) appears. */
    fun waitFor(key: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        assertTrue("\"$key\" did not appear within ${timeoutMs / 1000} s", waitForExistence(element(key), timeoutMs))
    }

    /** `waitFor(containing:timeout:)`. */
    fun waitForContaining(text: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        assertTrue("Nothing containing \"$text\" appeared within ${timeoutMs / 1000} s", waitForExistence(containing(text), timeoutMs))
    }

    /** `waitForGone(_:timeout:)`. */
    fun waitForGone(key: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        assertTrue("\"$key\" was still on screen after ${timeoutMs / 1000} s", waitForNonExistence(element(key), timeoutMs))
    }

    // MARK: Taps

    /** `tap(_:timeout:)`: waits for [key] (tag or label) to exist and be hittable, then clicks it. */
    fun tap(key: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS) = tap(element(key), key, timeoutMs)

    /** `tap(_:named:timeout:)`. */
    fun tap(matcher: SemanticsMatcher, name: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        assertTrue("\"$name\" did not appear within ${timeoutMs / 1000} s", waitForExistence(matcher, timeoutMs))
        var target: SemanticsNodeInteraction? = null
        assertTrue("\"$name\" never became hittable", waitUntil(timeoutMs) {
            target = hittable(matcher) ?: scrolledIntoView(matcher)
            target != null
        })
        target!!.performClick()
    }

    /**
     * XCUITest scrolls an element into view before tapping it: the first enabled match that lies in a scroll container
     * is scrolled to and returned if it is then on screen.
     */
    private fun scrolledIntoView(matcher: SemanticsMatcher): SemanticsNodeInteraction? {
        for (node in nodes(matcher)) {
            val enabled = runCatching { !node.fetchSemanticsNode().config.contains(SemanticsProperties.Disabled) }.getOrDefault(false)
            if (!enabled) continue
            if (runCatching { node.performScrollTo() }.isSuccess && isHittable(node)) return node
        }
        return null
    }

    /** Clicks [node] unless it left the tree since it was found; false if the click could not be delivered. */
    fun tryClick(node: SemanticsNodeInteraction): Boolean = runCatching { node.performClick() }.isSuccess

    /** The current [SemanticsProperties.EditableText] of a text field node. */
    fun editableText(node: SemanticsNodeInteraction): String? =
        node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text

    companion object {
        /** `PleadUITestCase.defaultTimeout` (10 s). */
        const val DEFAULT_TIMEOUT_MS = 10_000L

        /** `PleadUITestCase.baseArguments` (Android: intent extras without the dash). */
        val BASE_ARGUMENTS = mapOf(
            "AWDemo" to "YES",
            "AWColdOpen" to "none",
            "AWPaywallOpening" to "NO",
            "AWNoReviewPrompt" to "YES",
            "AWNoATTPrompt" to "YES",
            "AWDemoSpeed" to "fast",
        )

        /** The texts and content descriptions of a node: what XCUITest calls its label. */
        private fun labels(config: androidx.compose.ui.semantics.SemanticsConfiguration): List<String> = buildList {
            config.getOrNull(SemanticsProperties.Text)?.forEach { add(it.text) }
            config.getOrNull(SemanticsProperties.EditableText)?.let { add(it.text) }
            config.getOrNull(SemanticsProperties.ContentDescription)?.let { addAll(it) }
        }

        /** Nodes that can be clicked (a semantics `onClick`). */
        val clickable: SemanticsMatcher = SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)
    }
}
