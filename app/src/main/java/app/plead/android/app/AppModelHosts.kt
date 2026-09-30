// What AppModel needs from models that later waves port: OnboardingModel + ColdOpenCoordinator (wave 3b) and the
// court-session notification that replaces LiveActivityService (wave 3f). Not a Swift file: in Swift AppModel holds the
// concrete classes. Each later wave makes its class implement the interface here and passes it to AppModel (see
// docs/android-port/STATUS.md, wave 2a). The defaults below keep the shell working until then.
package app.plead.android.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.plead.android.models.Profile
import app.plead.android.services.CaseStore
import app.plead.android.services.UserDefaults
import app.plead.android.services.WidgetSetupService
import app.plead.android.services.uuidString
import java.util.UUID

/** The members of Swift `OnboardingModel` that AppModel, RootView and DemoHarness use. */
interface OnboardingHost {
    /** Persisted `OnboardingStep` raw value (1 Welcome … 10 Ready, 11 Mock Trial, 12 Summons explainer). */
    val stepRawValue: Int

    /** The signed-in user finished onboarding on this device (local mirror of `onboarding_completed_at`). */
    val completedForUser: Boolean

    /** Platform seam for screen 8 (`AWWidgetDetected` / `AWLiveActivitiesOff`). */
    var widgetSetup: WidgetSetupService

    fun userChanged(uid: UUID?, profile: Profile?)
    fun advance()
    fun markCompleted()

    /** DEBUG: demo launches start clean (plus the fixed anonymous demo user's scope when signed out). */
    fun resetForDemo(users: List<UUID>)

    /** DEBUG `AWOnboardStep`: prefill the demo name / avatar / together-since for `stepRaw`, then jump there. */
    fun applyDemoStep(stepRaw: Int)
}

/**
 * The shell's own onboarding state until wave 3b's `OnboardingModel` takes over: the step machine (raw values and
 * display order of `OnboardingStep`, persisted under the same `onboarding.<scope>.step` / `.completed` keys) without
 * any of the screens' fields. Replaced by `OnboardingModel(defaults)` (which implements [OnboardingHost]).
 */
class ShellOnboarding(private val defaults: UserDefaults) : OnboardingHost {
    override var stepRawValue: Int by mutableIntStateOf(welcome)
        private set
    override var completedForUser: Boolean by mutableStateOf(false)
        private set
    override var widgetSetup: WidgetSetupService = WidgetSetupService.live
    private var userId: UUID? = null

    init {
        stepRawValue = resume(defaults.int(key("step", null)), signedIn = false, hasProfile = false)
    }

    override fun userChanged(uid: UUID?, profile: Profile?) {
        val wasSignedOut = userId == null
        userId = uid
        completedForUser = uid != null && defaults.bool(key("completed", uid.uuidString))
        if (uid == null) {
            stepRawValue = resume(defaults.int(key("step", null)), signedIn = false, hasProfile = false)
            return
        }
        val saved = defaults.int(key("step", uid.uuidString))
        val inSession = if (wasSignedOut) stepRawValue else null
        val candidate = listOfNotNull(saved, inSession).maxByOrNull(::displayIndex)
        stepRawValue = resume(candidate ?: defaults.int(key("step", null)), signedIn = true, hasProfile = profile != null)
        persist()
    }

    override fun advance() {
        val i = displayIndex(stepRawValue)
        if (i + 1 >= displayOrder.size) return
        stepRawValue = displayOrder[i + 1]
        persist()
    }

    override fun markCompleted() {
        val uid = userId ?: return
        defaults.set(true, key("completed", uid.uuidString))
        completedForUser = true
    }

    override fun resetForDemo(users: List<UUID>) {
        val scopes = listOf(null, userId?.uuidString) + users.map { it.uuidString }
        for (scope in scopes) for (k in keys) defaults.removeObject(key(k, scope))
        stepRawValue = welcome
        completedForUser = false
    }

    override fun applyDemoStep(stepRaw: Int) {
        stepRawValue = stepRaw
        persist()
    }

    private fun persist() = defaults.set(stepRawValue, key("step", userId?.uuidString))

    private fun resume(raw: Int?, signedIn: Boolean, hasProfile: Boolean): Int {
        val r = raw ?: welcome
        val clamped = if (r in displayOrder) r else if (r < 1) welcome else ready
        return if (!signedIn || !hasProfile) displayOrder[minOf(displayIndex(clamped), displayIndex(identity))] else clamped
    }

    companion object {
        const val welcome = 1
        const val identity = 5
        const val partner = 6
        const val ready = 10
        const val summonsIntro = 12

        /** `OnboardingStep.displayOrder` as raw values (amendments y, ai, at). */
        val displayOrder = listOf(1, 11, 12, 2, 3, 4, 5, 6, 7, 9, 8, 10)

        /** `OnboardingStep.screenId` by raw value (for `AWOnboardStep <screen_id>`). */
        val screenIds = mapOf(
            1 to "welcome", 11 to "mock_trial", 12 to "summons_intro", 2 to "how_it_works", 3 to "ai_court", 4 to "examples",
            5 to "identity", 6 to "partner", 7 to "notifications", 9 to "tracking", 8 to "widgets", 10 to "ready",
        )

        fun displayIndex(raw: Int): Int = displayOrder.indexOf(raw).let { if (it < 0) displayOrder.size else it }

        private val keys = listOf(
            "step", "name", "avatar", "partnerName", "togetherSince", "completed", "seenWidgetEducation", "widgetEducationSkipped",
            "widgetSetupComplete", "configuredPleadWidget", "liveActivitiesEnabled", "trackingSeen",
        )

        /** `OnboardingModel.Key.name(scope)`: `onboarding.<scope or device>.<key>`. */
        fun key(name: String, scope: String?): String = "onboarding.${scope ?: "device"}.$name"
    }
}

/** The members of Swift `ColdOpenCoordinator` that AppModel and RootView use. */
interface ColdOpenHost {
    val isPlaying: Boolean
    fun launch(forced: DemoHarness.ColdOpen?, demo: Boolean, reduceMotion: Boolean)
    fun resumedFromBackground()
    fun skip()
}

/** No cold open until wave 3b ports `ColdOpenCoordinator` (the app starts on the gate, as `AWColdOpen none`). */
class NoColdOpen : ColdOpenHost {
    override val isPlaying: Boolean = false
    override fun launch(forced: DemoHarness.ColdOpen?, demo: Boolean, reduceMotion: Boolean) = Unit
    override fun resumedFromBackground() = Unit
    override fun skip() = Unit
}

/**
 * What AppModel asks of the court-session notification (wave 3f), in place of Swift's `LiveActivityService`:
 * the local start/update/end fallback driven by `LiveActivityPlanner`. Android never registers Live Activity tokens.
 */
interface CourtSessionPresenter {
    /** Local starts/updates run only while this is true (set from the activity lifecycle). */
    var isForeground: Boolean

    suspend fun userChanged(uid: UUID?)
    suspend fun reconcile(store: CaseStore, openedVerdicts: Set<UUID>)

    /** DEBUG `AWLiveActivity summons|verdict|verdictReady`: a fixed demo session. */
    fun startDemo(mode: String)
}
