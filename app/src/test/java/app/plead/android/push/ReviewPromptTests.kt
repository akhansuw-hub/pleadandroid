// Google Play In-App Review after the mock trial (amendment al): once per install, `AWNoReviewPrompt YES` respected.
package app.plead.android.push

import android.app.Activity
import app.plead.android.app.LaunchArguments
import app.plead.android.services.Analytics
import app.plead.android.services.UserDefaults
import com.google.android.play.core.review.testing.FakeReviewManager
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReviewPromptTests {
    @After fun tearDown() {
        LaunchArguments.set(emptyMap())
        Analytics.sink = null
    }

    @Test fun asksOncePerInstall() {
        LaunchArguments.set(emptyMap())
        val defaults = UserDefaults.inMemory()
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        Analytics.sink = { e, p -> events.add(e to p) }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        ReviewPrompt.managerFactory = { FakeReviewManager(it) }
        // The Play flow itself runs on the scope after the 0.8 s settle (Play's Task callbacks need a real looper);
        // what matters here is that the ask is recorded once and never repeated.
        val scope = TestScope()
        assertTrue(ReviewPrompt.askForRatingOnce(activity, scope, defaults))
        assertTrue(defaults.bool(ReviewPrompt.requestedKey))
        assertFalse(ReviewPrompt.askForRatingOnce(activity, scope, defaults))
        assertEquals(listOf("rating_prompt_requested" to mapOf("after" to "mock_trial")), events)
    }

    @Test fun noReviewPromptFlagNeverAsks() {
        LaunchArguments.set(mapOf("AWNoReviewPrompt" to "YES"))
        val defaults = UserDefaults.inMemory()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertFalse(ReviewPrompt.askForRatingOnce(activity, TestScope(), defaults))
        assertFalse(defaults.bool(ReviewPrompt.requestedKey))
    }
}
