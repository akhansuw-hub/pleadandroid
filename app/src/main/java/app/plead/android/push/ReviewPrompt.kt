// Google Play In-App Review at the moment iOS calls `requestReview()` (PORT.md §2): once per install, after the mock
// trial is watched to the end (amendment al; ArgueWin/Features/Onboarding/OnboardingContainer.swift
// `askForRatingOnce`). `AWNoReviewPrompt YES` never asks (UI tests).
//
// Call site (wave 3b's OnboardingContainer): `val requestReview = rememberRequestReview()` then `requestReview()` where
// iOS calls `askForRatingOnce()`.
package app.plead.android.push

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import app.plead.android.app.DemoHarness
import app.plead.android.services.Analytics
import app.plead.android.services.UserDefaults
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.android.play.core.review.testing.FakeReviewManager
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

object ReviewPrompt {
    /** Same UserDefaults key as iOS (`ratingPromptRequested`, standard domain). */
    const val requestedKey = "ratingPromptRequested"

    /** "Let the next screen settle first so the prompt doesn't land mid-transition." */
    const val settleMillis = 800L

    /** The Play review manager (tests / demo swap in `FakeReviewManager`). */
    var managerFactory: (Context) -> ReviewManager = { context ->
        if (DemoHarness.isDemo) FakeReviewManager(context) else ReviewManagerFactory.create(context)
    }

    /** Not yet asked on this install, and not suppressed by `AWNoReviewPrompt YES`. */
    fun shouldAsk(defaults: UserDefaults = UserDefaults.standard): Boolean =
        !defaults.bool(requestedKey) && !DemoHarness.noReviewPrompt

    /**
     * Swift `askForRatingOnce()`: records the ask (so it never repeats), tracks `rating_prompt_requested`, waits for
     * the next screen to settle, then asks Google Play. Returns whether it asked.
     */
    fun askForRatingOnce(activity: Activity, scope: CoroutineScope, defaults: UserDefaults = UserDefaults.standard): Boolean {
        if (!shouldAsk(defaults)) return false
        defaults.set(true, requestedKey)
        Analytics.track("rating_prompt_requested", mapOf("after" to "mock_trial"))
        scope.launch {
            delay(settleMillis)
            requestReview(activity)
        }
        return true
    }

    /** Play's flow: request a ReviewInfo, then launch it. Play decides whether a card actually shows. Never throws. */
    suspend fun requestReview(activity: Activity): Boolean {
        val manager = runCatching { managerFactory(activity) }.getOrNull() ?: return false
        val info = suspendCancellableCoroutine<com.google.android.play.core.review.ReviewInfo?> { cont ->
            runCatching {
                manager.requestReviewFlow().addOnCompleteListener { task ->
                    if (cont.isActive) cont.resume(if (task.isSuccessful) task.result else null)
                }
            }.onFailure { if (cont.isActive) cont.resume(null) }
        } ?: return false
        return suspendCancellableCoroutine<Boolean> { cont ->
            runCatching {
                manager.launchReviewFlow(activity, info).addOnCompleteListener { task ->
                    if (cont.isActive) cont.resume(task.isSuccessful)
                }
            }.onFailure { if (cont.isActive) cont.resume(false) }
        }
    }

    internal fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}

/**
 * SwiftUI's `@Environment(\.requestReview)` for the mock-trial ending: a function that asks once per install
 * ([ReviewPrompt.askForRatingOnce]).
 */
@Composable
fun rememberRequestReview(): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, scope) {
        {
            with(ReviewPrompt) { context.findActivity() }?.let { ReviewPrompt.askForRatingOnce(it, scope) }
        }
    }
}
