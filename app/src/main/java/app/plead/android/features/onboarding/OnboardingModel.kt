// Port of ArgueWin/Features/Onboarding/OnboardingModel.swift: the onboarding step machine and its persisted fields.
// Android (amendment az, no App Tracking Transparency): the Privacy & tracking step (`.tracking`, raw value 9)
// is not an active step, so the flow is eleven screens ("N OF 11") and passing its position leaves the same
// state behind as an iOS user who answered the ATT prompt (`trackingSeen`; `TrackingPermissionService` is
// always `authorized`). Raw values, screen ids and keys are unchanged, so a step persisted as 9 resumes on the
// next active screen (Widgets). See docs/android-port/STATUS.md, wave 3b.
package app.plead.android.features.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.plead.android.app.OnboardingHost
import app.plead.android.models.Avatar
import app.plead.android.models.Profile
import app.plead.android.services.Analytics
import app.plead.android.services.JSONCoding
import app.plead.android.services.UserDefaults
import app.plead.android.services.WidgetFamily
import app.plead.android.services.WidgetSetupService
import app.plead.android.services.uuidString
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * The onboarding screens, in the brief's exact order (docs/onboarding-motion-brief §1, CONTRACTS-v2
 * amendment at: Privacy & tracking sits between Notifications and Widgets & Live Activities; amendment y: the Mock
 * Trial Demo sits directly after Welcome; amendment ai: the summons explainer follows the demo).
 * Raw values are the persisted `onboarding_step` and never shift (amendment t): 1–10 are the original screens,
 * `.mockTrial` was appended as 11, `.summonsIntro` as 12. Order is therefore **not** raw-value order: `displayOrder`
 * (also `allCases`) is the one source for `<`, `activeSteps()`, positions, "N OF M", next/previous and resume.
 */
enum class OnboardingStep(val rawValue: Int) {
    welcome(1), howItWorks(2), aiCourt(3), examples(4), identity(5), partner(6), notifications(7), widgets(8), tracking(9), ready(10),
    /** Amendment y: onboarding step 2 on screen, raw value 11 on disk. */
    mockTrial(11),
    /** Amendment ai: onboarding step 3 on screen (after the demo, watched or skipped), raw value 12 on disk. */
    summonsIntro(12);

    /** Index in `displayOrder`: the only thing ordering compares. */
    val displayIndex: Int get() = displayOrder.indexOf(this).let { if (it < 0) displayOrder.size else it }

    /** Display-order comparisons (Swift `<`, `>=`…; Kotlin enums compare by declaration order otherwise). */
    infix fun before(other: OnboardingStep): Boolean = displayIndex < other.displayIndex
    infix fun after(other: OnboardingStep): Boolean = displayIndex > other.displayIndex
    infix fun atOrAfter(other: OnboardingStep): Boolean = displayIndex >= other.displayIndex
    infix fun atOrBefore(other: OnboardingStep): Boolean = displayIndex <= other.displayIndex

    /** Analytics `screen_id`. */
    val screenId: String
        get() = when (this) {
            welcome -> "welcome"
            mockTrial -> "mock_trial"
            summonsIntro -> "summons_intro"
            howItWorks -> "how_it_works"
            aiCourt -> "ai_court"
            examples -> "examples"
            identity -> "identity"
            partner -> "partner"
            notifications -> "notifications"
            widgets -> "widgets"
            tracking -> "tracking"
            ready -> "ready"
        }

    /** 1-based position in `steps` (a step not in the list reports the position of the next active one). */
    fun position(steps: List<OnboardingStep> = activeSteps()): Int =
        (steps.indexOfFirst { it atOrAfter this }.takeIf { it >= 0 } ?: (steps.size - 1)) + 1

    fun next(steps: List<OnboardingStep> = activeSteps()): OnboardingStep? = steps.firstOrNull { it after this }
    fun previous(steps: List<OnboardingStep> = activeSteps()): OnboardingStep? = steps.lastOrNull { it before this }

    /** Progress-bar fill (screens 2–N). */
    fun progress(steps: List<OnboardingStep> = activeSteps()): Double = position(steps).toDouble() / steps.size

    /** "1 OF 11": always from the active step count. */
    fun positionLabel(steps: List<OnboardingStep> = activeSteps()): String = "${position(steps)} OF ${steps.size}"

    /** Welcome has no top bar (no progress, nothing to go back to); every later screen does. */
    val showsChrome: Boolean get() = this != welcome

    /**
     * Steps that can be reached without a session. THAT'S ME on `.identity` creates the anonymous user
     * (amendment p); the account is secured with Google / Apple / email after the paywall.
     */
    val isPreAuth: Boolean get() = this atOrBefore identity

    companion object {
        /** The screens in the order they are shown (amendment y). */
        val displayOrder: List<OnboardingStep> = listOf(
            welcome, mockTrial, summonsIntro, howItWorks, aiCourt, examples, identity, partner, notifications, tracking, widgets, ready,
        )

        /** `CaseIterable` in display order, so anything iterating the cases walks the flow as shown. */
        val allCases: List<OnboardingStep> get() = displayOrder

        /** Android has no App Tracking Transparency (amendment az): the Privacy & tracking step is not shown. */
        const val skipsTracking: Boolean = true

        fun fromRaw(raw: Int): OnboardingStep? = entries.firstOrNull { it.rawValue == raw }

        /**
         * A persisted raw value back to a step. Unknown values clamp by magnitude (below 1 → Welcome, above the
         * highest raw value → Court Is Ready), never by raw-value neighbours.
         */
        fun persisted(raw: Int): OnboardingStep = fromRaw(raw) ?: if (raw < 1) welcome else ready

        /**
         * The steps actually shown, in order. Amendment ak (supersedes amendment t's gating): every step, including
         * `.tracking` on iOS; on Android every step but `.tracking` (amendment az: no ATT).
         */
        fun activeSteps(): List<OnboardingStep> = if (skipsTracking) displayOrder.filter { it != tracking } else displayOrder

        fun max(a: OnboardingStep, b: OnboardingStep): OnboardingStep = if (a.displayIndex >= b.displayIndex) a else b
        fun min(a: OnboardingStep, b: OnboardingStep): OnboardingStep = if (a.displayIndex <= b.displayIndex) a else b
    }
}

/** Pure rules for the step machine (unit-tested in `OnboardingFlowTests`). */
object OnboardingFlow {
    /**
     * Where a (re)launch lands: the last incomplete step. Without an account, or without a saved
     * profile, the flow can't be further than the identity step.
     * A saved step that isn't active resumes on the next active one.
     * Amendment at: a step after `.tracking` resumes on `.tracking` until the user has passed it once in the new
     * order (`trackingSeen`), so someone saved on Widgets / Court Is Ready under the old order is still asked.
     * (Android: `.tracking` is not active, so that rule never applies.)
     */
    fun resumeStep(
        user: Int?,
        device: Int?,
        signedIn: Boolean,
        hasProfile: Boolean,
        trackingSeen: Boolean = true,
        steps: List<OnboardingStep> = OnboardingStep.activeSteps(),
    ): OnboardingStep {
        val raw = (if (signedIn) (user ?: device) else device) ?: OnboardingStep.welcome.rawValue
        val clamped = OnboardingStep.persisted(raw)
        var step = steps.firstOrNull { it atOrAfter clamped } ?: steps.lastOrNull() ?: OnboardingStep.welcome
        if (!signedIn || !hasProfile) return OnboardingStep.min(step, OnboardingStep.identity)
        if (!trackingSeen && steps.contains(OnboardingStep.tracking) && step after OnboardingStep.tracking) step = OnboardingStep.tracking
        return step
    }

    /**
     * Whether the primary CTA on `step` may move forward. Identity needs only a name: THAT'S ME
     * creates the (anonymous) session itself.
     */
    fun canAdvance(step: OnboardingStep, displayName: String): Boolean = when (step) {
        OnboardingStep.identity -> trimmedName(displayName).isNotEmpty()
        OnboardingStep.ready -> false
        else -> true
    }

    /** Whether back is offered. Nothing precedes the welcome screen. */
    fun canGoBack(step: OnboardingStep, steps: List<OnboardingStep> = OnboardingStep.activeSteps()): Boolean = step.previous(steps) != null

    /** Existing accounts skip onboarding entirely (CONTRACTS-v2 amendment g). */
    fun isComplete(serverCompletedAt: Instant?, localFlag: Boolean, hasCases: Boolean): Boolean =
        serverCompletedAt != null || localFlag || hasCases

    /** The live docket preview: "ARIF v. SOPHIE". */
    fun docketTitle(me: String, partner: String): String {
        val a = trimmedName(me)
        val b = trimmedName(partner)
        return "${if (a.isEmpty()) "YOU" else a.uppercase()} v. ${if (b.isEmpty()) "YOUR PARTNER" else b.uppercase()}"
    }

    /** Swift `trimmingCharacters(in: .whitespacesAndNewlines)`. */
    fun trimmedName(s: String): String = s.trim()
}

/** The eight preset court identities on screen 5 (`avatar_json` configs; "Customise" opens the creator). */
object OnboardingAvatars {
    val presets: List<Avatar> = listOf(
        Avatar(skin = 1, hair = 5, hairstyle = Avatar.Hairstyle.long, top = 3, outfit = Avatar.Outfit.dress),
        Avatar(skin = 3, hair = 0, hairstyle = Avatar.Hairstyle.curly, top = 5, outfit = Avatar.Outfit.hoodie),
        Avatar(skin = 0, hair = 4, hairstyle = Avatar.Hairstyle.ponytail, top = 0, outfit = Avatar.Outfit.tee),
        Avatar(skin = 4, hair = 0, hairstyle = Avatar.Hairstyle.buzz, top = 6, outfit = Avatar.Outfit.shirt),
        Avatar(skin = 2, hair = 1, hairstyle = Avatar.Hairstyle.bun, top = 4, outfit = Avatar.Outfit.dress),
        Avatar(skin = 5, hair = 0, hairstyle = Avatar.Hairstyle.short, top = 7, outfit = Avatar.Outfit.suit),
        Avatar(skin = 1, hair = 3, hairstyle = Avatar.Hairstyle.short, top = 1, outfit = Avatar.Outfit.hoodie),
        Avatar(skin = 2, hair = 2, hairstyle = Avatar.Hairstyle.long, top = 2, outfit = Avatar.Outfit.shirt),
    )
}

/**
 * Source of truth for the onboarding flow. Persists the step and captured fields after every
 * meaningful interaction: pre-auth under a device scope, afterwards per user id, so a killed app
 * resumes on the last incomplete step. Presentation only: auth, permissions and writes live in
 * the services it's handed. Observable through Compose snapshot state (`@Observable`).
 */
class OnboardingModel(private val defaults: UserDefaults = UserDefaults.standard) : OnboardingHost {
    enum class Direction { forward, backward }

    var step: OnboardingStep by mutableStateOf(OnboardingStep.welcome)
        private set
    var direction: Direction by mutableStateOf(Direction.forward)
        private set

    /** The signed-in user finished onboarding on this device (local mirror of `onboarding_completed_at`). */
    override var completedForUser: Boolean by mutableStateOf(false)
        private set

    private var _displayName by mutableStateOf("")
    var displayName: String
        get() = _displayName
        set(value) {
            val old = _displayName
            _displayName = value
            if (!loading && value != old) save(Key.displayName, value)
        }

    private var _avatar by mutableStateOf(OnboardingAvatars.presets[0])
    var avatar: Avatar
        get() = _avatar
        set(value) {
            val old = _avatar
            _avatar = value
            if (!loading && value != old) saveAvatar()
        }

    private var _togetherSince by mutableStateOf<Instant?>(null)
    var togetherSince: Instant?
        get() = _togetherSince
        set(value) {
            val old = _togetherSince
            _togetherSince = value
            if (!loading && value != old) saveTogetherSince()
        }

    // Screen 8 · Widgets & Live Activities (amendment r), persisted per user.
    /** The tracking step (amendment at) was passed in the current order (drives the old-order resume rule). */
    var trackingSeen: Boolean by persistedFlag(Key.trackingSeen)
        private set
    /** Screen 8 has been shown at least once. */
    var hasSeenWidgetEducation: Boolean by persistedFlag(Key.seenWidgetEducation)
        private set
    /** NOT NOW on screen 8 (never blocks; remembered so we don't nag). */
    var widgetEducationSkipped: Boolean by persistedFlag(Key.widgetEducationSkipped)
        private set
    /** A Plead widget was detected after the setup guidance (drives the success state). */
    var widgetSetupComplete: Boolean by persistedFlag(Key.widgetSetupComplete)
        private set
    /** The launcher reports at least one pinned Plead widget. */
    var hasConfiguredPleadWidget: Boolean by persistedFlag(Key.configuredPleadWidget)
        private set
    /**
     * iOS `ActivityAuthorizationInfo().areActivitiesEnabled` at the last check (true until checked). Android: the
     * court-session notification's channel is enabled (`WidgetSetupService.liveActivitiesEnabled`).
     */
    var liveActivitiesEnabled: Boolean by persistedFlag(Key.liveActivitiesEnabled, initial = true)
        private set
    /** The widget families found at the last check (for the success line; not persisted). */
    var detectedWidgetFamilies: List<WidgetFamily> by mutableStateOf(emptyList())
        private set

    /** Platform seam for screen 8 (the DEBUG harness and tests substitute it). */
    override var widgetSetup: WidgetSetupService = WidgetSetupService.live
    /** Every onboarding analytics event also goes here (tests record them). */
    var analyticsSink: ((String, Map<String, String>) -> Unit)? = null
    private var widgetViewTracked = false
    private var liveActivityOffTracked = false

    var userId: UUID? = null
        private set
    private var viewedThisSession = mutableSetOf<OnboardingStep>()
    private var startedTracked = false
    private var loading = false

    /** The screens actually shown, in order: every count, progress, "1 OF N", back/next and resume uses this. */
    val activeSteps: List<OnboardingStep> get() = OnboardingStep.activeSteps()
    /** "1 OF 11" for the current step. */
    val positionLabel: String get() = step.positionLabel(activeSteps)
    /** Progress fill for the current step. */
    val progress: Double get() = step.progress(activeSteps)
    val canGoBack: Boolean get() = OnboardingFlow.canGoBack(step, activeSteps)

    override val stepRawValue: Int get() = step.rawValue

    init {
        load(scope = null)
        step = resume(user = null, device = int(Key.step, null), signedIn = false, hasProfile = false)
    }

    private fun resume(user: Int?, device: Int?, signedIn: Boolean, hasProfile: Boolean): OnboardingStep =
        OnboardingFlow.resumeStep(user, device, signedIn, hasProfile, trackingSeen = trackingSeen, steps = activeSteps)

    // MARK: Session

    /**
     * The signed-in user changed (or the store finished loading for them). Loads that user's saved
     * state and resumes at their last incomplete step. The current in-session step wins over an
     * older saved one, so signing in on screen 5 never jumps backwards.
     */
    override fun userChanged(uid: UUID?, profile: Profile?) {
        val wasSignedOut = userId == null
        userId = uid
        completedForUser = isCompleted(uid)
        if (uid == null) {
            load(scope = null)
            step = resume(user = null, device = int(Key.step, null), signedIn = false, hasProfile = false)
            return
        }
        val scope = uid.uuidString
        val savedUserStep = int(Key.step, scope)
        if (savedUserStep == null) {
            // First time this account is seen on this device: carry the pre-auth answers across.
            for (key in listOf(Key.displayName, Key.avatar)) {
                val v = defaults.objectForKey(key.key(null))
                if (v != null && !defaults.has(key.key(scope))) defaults.set(v, key.key(scope))
            }
        }
        load(scope = scope)
        if (profile != null) {
            if (displayName.isEmpty()) displayName = profile.displayName
            if (!defaults.has(Key.avatar.key(scope))) avatar = profile.avatar
        }
        // The step reached in this session (signed out → signed in on screen 5) never moves backwards.
        // Compared in display order: raw values are not ordered (`.mockTrial` is 11 but shown second).
        val inSession = if (wasSignedOut) step else null
        val candidate = listOfNotNull(savedUserStep?.let(OnboardingStep::persisted), inSession).maxByOrNull { it.displayIndex }?.rawValue
        step = resume(user = candidate, device = int(Key.step, null), signedIn = true, hasProfile = profile != null)
        persistStep()
    }

    // MARK: Navigation

    /** The primary forward CTA on the current screen (`onboarding_continue_tapped {screen_id}`). */
    override fun advance() {
        if (step.next(activeSteps) == null) return
        track(OnboardingEvent.continueTapped, mapOf("screen_id" to step.screenId))
        moveForward()
    }

    private fun moveForward() {
        val next = step.next(activeSteps) ?: return
        // Passing Privacy & tracking (iOS) — or its position when Android skips it — records `trackingSeen`.
        if (step == OnboardingStep.tracking || (step before OnboardingStep.tracking && next after OnboardingStep.tracking)) trackingSeen = true
        direction = Direction.forward
        step = next
        persistStep()
    }

    fun back() {
        val prev = step.previous(activeSteps) ?: return
        direction = Direction.backward
        step = prev
        persistStep()
    }

    /** Jump (demo harness / previews). A step that isn't active would land on the next active one. */
    fun go(to: OnboardingStep) {
        val target = activeSteps.firstOrNull { it atOrAfter to } ?: to
        direction = if (target atOrAfter step) Direction.forward else Direction.backward
        step = target
        persistStep()
    }

    /** `onboarding_started` once, `onboarding_screen_viewed` once per screen per session. */
    fun screenAppeared(s: OnboardingStep) {
        if (!startedTracked && (s == OnboardingStep.welcome || viewedThisSession.isEmpty())) {
            startedTracked = true
            track(OnboardingEvent.started, mapOf("screen_id" to s.screenId))
        }
        if (viewedThisSession.add(s)) {
            track(OnboardingEvent.screenViewed, mapOf("screen_id" to s.screenId))
        }
    }

    private fun track(event: String, props: Map<String, String> = emptyMap()) {
        Analytics.track(event, props)
        analyticsSink?.invoke(event, props)
    }

    // MARK: Screen 2 · Mock Trial Demo (amendment y)

    /** The beat the demo last reported (the `beat` prop on skip). Not persisted: a reopened demo replays. */
    var mockTrialBeat: MockTrialBeat by mutableStateOf(MockTrialBeat.opening)
        private set

    /** The demo appeared (every entry, including back from the summons explainer): `onboarding_mock_trial_viewed`. */
    fun mockTrialAppeared() {
        mockTrialBeat = MockTrialBeat.opening
        track(OnboardingEvent.mockTrialViewed)
    }

    /**
     * The scene moved to `beat` (autoplay or tap): `onboarding_mock_trial_advanced {beat}` with the beat's
     * analytics name (amendment aj: `plaintiff_opening` … `cross_examination_1` … `judgement`, `closed`).
     */
    fun mockTrialAdvanced(beat: MockTrialBeat) {
        if (step != OnboardingStep.mockTrial) return
        mockTrialBeat = beat
        track(OnboardingEvent.mockTrialAdvanced, mapOf("beat" to beat.analyticsName))
    }

    /**
     * I'M READY FOR COURT on CASE CLOSED (amendment aj): `onboarding_mock_trial_completed`, then the usual continue →
     * the summons explainer (amendment ai). Ignored once the step has moved on (a second tap during the page transition).
     */
    fun completeMockTrial() {
        if (step != OnboardingStep.mockTrial) return
        track(OnboardingEvent.mockTrialCompleted)
        advance()
    }

    /**
     * SKIP DEMO skips the demo, not onboarding: `onboarding_mock_trial_skipped {beat}` → the summons explainer
     * (amendment ai: both exits land there).
     */
    fun skipMockTrial() {
        if (step != OnboardingStep.mockTrial) return
        track(OnboardingEvent.mockTrialSkipped, mapOf("beat" to mockTrialBeat.analyticsName))
        moveForward()
    }

    // MARK: Screen 8 · Widgets & Live Activities

    /** Screen 8 appeared: remember it, `widget_education_viewed` once per session, then check what's set up. */
    suspend fun widgetEducationAppeared() {
        hasSeenWidgetEducation = true
        if (!widgetViewTracked) {
            widgetViewTracked = true
            track(OnboardingEvent.widgetEducationViewed)
        }
        refreshWidgetSetup()
    }

    /** A Home Screen / Lock Screen card was opened in the instructions sheet. */
    fun widgetInstructionsOpened(surface: WidgetSetupSurface) {
        track(OnboardingEvent.widgetInstructionsOpened, mapOf("surface" to surface.rawValue))
    }

    /** NOT NOW: remembered, never blocks. */
    fun skipWidgetEducation() {
        widgetEducationSkipped = true
        track(OnboardingEvent.widgetEducationSkipped)
        moveForward()
    }

    /**
     * Screen 8 (or its sheet) is up and the app became active / appeared: ask the launcher which Plead widgets
     * exist and whether the court-session notification is allowed. Never prompts for anything.
     */
    suspend fun refreshWidgetSetup() {
        val service = widgetSetup
        val enabled = service.liveActivitiesEnabled
        liveActivitiesEnabled = enabled
        if (!enabled && !liveActivityOffTracked) {
            liveActivityOffTracked = true
            track(OnboardingEvent.liveActivityDisabledDetected)
        }
        val families = service.detectConfiguredWidgets()
        detectedWidgetFamilies = families
        if (families.isEmpty()) return
        hasConfiguredPleadWidget = true
        if (!widgetSetupComplete) {
            widgetSetupComplete = true
            track(OnboardingEvent.widgetDetectedAfterSetup, mapOf("family" to WidgetSetupService.analyticsName(families[0])))
        }
    }

    // MARK: Completion

    override fun markCompleted() {
        val uid = userId ?: return
        defaults.set(true, Key.completed.key(uid.uuidString))
        completedForUser = true
    }

    fun isCompleted(uid: UUID?): Boolean {
        if (uid == null) return false
        return defaults.bool(Key.completed.key(uid.uuidString))
    }

    /**
     * DEBUG: demo launches start clean unless `AWOnboardResume YES`. `users` are extra per-user scopes to wipe: the
     * signed-out demo passes the fixed id THAT'S ME signs in as (`PreviewData.anonId`), whose saved step and
     * "completed" flag would otherwise make every later run skip screens 6–10.
     */
    override fun resetForDemo(users: List<UUID>) {
        val scopes: List<String?> = listOf(null, userId?.uuidString) + users.map { it.uuidString }
        for (scope in scopes) {
            for (key in Key.entries) defaults.removeObject(key.key(scope))
        }
        load(scope = userId?.uuidString)
        step = OnboardingStep.welcome
        completedForUser = false
        viewedThisSession = mutableSetOf()
        widgetViewTracked = false
        liveActivityOffTracked = false
        mockTrialBeat = MockTrialBeat.opening
    }

    /** DEBUG `AWOnboardStep` (DemoHarness.applyOnboarding): the demo name / avatar / together-since, then the jump. */
    override fun applyDemoStep(stepRaw: Int) {
        val target = OnboardingStep.fromRaw(stepRaw) ?: return
        if (target atOrAfter OnboardingStep.identity) {
            displayName = "Arif"
            avatar = OnboardingAvatars.presets[1]
        }
        if (target atOrAfter OnboardingStep.partner) {
            togetherSince = LocalDate.of(2024, 2, 14).atStartOfDay(ZoneId.systemDefault()).toInstant()
        }
        go(target)
    }

    // MARK: Persistence

    enum class Key(val rawValue: String) {
        step("step"), displayName("name"), avatar("avatar"),
        /** `partnerName` is retired (amendment aw: no partner-name entry); kept so the demo reset still wipes old values. */
        partnerName("partnerName"), togetherSince("togetherSince"), completed("completed"),
        seenWidgetEducation("seenWidgetEducation"), widgetEducationSkipped("widgetEducationSkipped"),
        widgetSetupComplete("widgetSetupComplete"), configuredPleadWidget("configuredPleadWidget"),
        liveActivitiesEnabled("liveActivitiesEnabled"),
        /** Amendment at: the tracking step was passed after the reorder. */
        trackingSeen("trackingSeen");

        /** Swift `Key.name(_:)`: `onboarding.<scope or device>.<rawValue>`. */
        fun key(scope: String?): String = "onboarding.${scope ?: "device"}.$rawValue"
    }

    private val scope: String? get() = userId?.uuidString

    /** Signed out: the device scope. Signed in: the user's scope. */
    private fun persistStep() = defaults.set(step.rawValue, Key.step.key(scope))

    private fun save(key: Key, value: String) = defaults.set(value, key.key(scope))
    private fun save(key: Key, value: Boolean) = defaults.set(value, key.key(scope))

    private fun saveAvatar() {
        runCatching { JSONCoding.json.encodeToJsonElement(Avatar.serializer(), avatar) }.getOrNull()?.let { defaults.set(it, Key.avatar.key(scope)) }
    }

    private fun saveTogetherSince() {
        val d = togetherSince
        if (d != null) defaults.set(d.toEpochMilli() / 1000.0, Key.togetherSince.key(scope))
        else defaults.removeObject(Key.togetherSince.key(scope))
    }

    private fun int(key: Key, scope: String?): Int? = defaults.int(key.key(scope))

    /** Reads a scope's fields without re-persisting them. */
    private fun load(scope: String?) {
        val name = defaults.string(Key.displayName.key(scope)) ?: ""
        val avatar = defaults.objectForKey(Key.avatar.key(scope))?.let { el ->
            runCatching { JSONCoding.json.decodeFromJsonElement(Avatar.serializer(), el) }.getOrNull()
        }
        val since = defaults.doubleOrNull(Key.togetherSince.key(scope))?.let { Instant.ofEpochMilli((it * 1000).toLong()) }
        fun flag(key: Key, value: Boolean = false): Boolean = defaults.boolOrNull(key.key(scope)) ?: value
        loading = true
        try {
            displayName = name
            this.avatar = avatar ?: OnboardingAvatars.presets[0]
            togetherSince = since
            hasSeenWidgetEducation = flag(Key.seenWidgetEducation)
            widgetEducationSkipped = flag(Key.widgetEducationSkipped)
            widgetSetupComplete = flag(Key.widgetSetupComplete)
            hasConfiguredPleadWidget = flag(Key.configuredPleadWidget)
            liveActivitiesEnabled = flag(Key.liveActivitiesEnabled, value = true)
            trackingSeen = flag(Key.trackingSeen)
            detectedWidgetFamilies = emptyList()
        } finally {
            loading = false
        }
    }

    /** A snapshot-state Bool that persists itself under `key` (Swift's `didSet { save(...) }`) unless loading. */
    private fun persistedFlag(key: Key, initial: Boolean = false) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        private val state = mutableStateOf(initial)
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): Boolean = state.value
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) {
            val old = state.value
            state.value = value
            if (!loading && value != old) save(key, value)
        }
    }
}

/** Where the setup sheet's guidance applies (`widget_setup_instructions_opened {surface}`). */
enum class WidgetSetupSurface(val rawValue: String) {
    home("home"), lock("lock");
}

/** Onboarding analytics event names (brief §5). */
object OnboardingEvent {
    const val started = "onboarding_started"
    const val screenViewed = "onboarding_screen_viewed"
    const val continueTapped = "onboarding_continue_tapped"
    const val widgetEducationViewed = "widget_education_viewed"
    const val widgetInstructionsOpened = "widget_setup_instructions_opened"
    const val widgetDetectedAfterSetup = "widget_detected_after_setup"
    const val widgetEducationSkipped = "widget_education_skipped"
    const val liveActivityDisabledDetected = "live_activity_disabled_detected"
    // Mock Trial Demo (amendment y, brief §9).
    const val mockTrialViewed = "onboarding_mock_trial_viewed"
    const val mockTrialAdvanced = "onboarding_mock_trial_advanced"
    const val mockTrialCompleted = "onboarding_mock_trial_completed"
    const val mockTrialSkipped = "onboarding_mock_trial_skipped"
}

/** Swift `String.capitalized`: every word's first letter upper-cased, the rest lower-cased. */
internal fun String.swiftCapitalized(): String =
    split(" ").joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }

/**
 * Swift `app.onboarding` (the concrete model): AppModel holds it behind the wave 2a `OnboardingHost` seam. Requires
 * the integrator's wiring (`OnboardingModel(defaults)` as AppModel's onboarding; see STATUS.md, wave 3b).
 */
val app.plead.android.app.AppModel.onboardingModel: OnboardingModel
    get() = onboarding as? OnboardingModel
        ?: error("AppModel.onboarding is not an OnboardingModel: wire features/onboarding/OnboardingModel into AppModel (STATUS.md, wave 3b)")
