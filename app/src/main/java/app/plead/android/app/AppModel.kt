// Port of ArgueWin/App/AppModel.swift: the composition root. Owns every service and derives the app's top-level
// phase. Held by PleadApplication for the life of the process (the iOS `@State` model of the App struct).
package app.plead.android.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.plead.android.models.Avatar
import app.plead.android.models.EdgeError
import app.plead.android.services.Analytics
import app.plead.android.services.AppNotifications
import app.plead.android.services.AuthService
import app.plead.android.services.CaseStore
import app.plead.android.services.DeepLinkRouter
import app.plead.android.services.NotificationPermissionService
import app.plead.android.services.NotificationPrefsModel
import app.plead.android.services.PreviewData
import app.plead.android.services.PurchasesService
import app.plead.android.services.PushService
import app.plead.android.services.TrackingPermissionService
import app.plead.android.services.UserDefaults
import app.plead.android.services.WidgetSnapshotStore
import app.plead.android.services.isRevealed
import app.plead.android.services.uuidString
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Composition root: owns every service and derives the app's top-level phase. */
class AppModel(
    val auth: AuthService,
    val store: CaseStore,
    val purchases: PurchasesService,
    val push: PushService,
    val router: AppRouter = AppRouter(),
    val links: DeepLinkRouter = DeepLinkRouter(),
    onboarding: OnboardingHost? = null,
    private val defaults: UserDefaults = UserDefaults.standard,
    coldOpen: ColdOpenHost? = null,
) : ViewModel() {
    /** Onboarding (wave 3b's `OnboardingModel`; the shell's step machine until then). */
    val onboarding: OnboardingHost = onboarding ?: ShellOnboarding(defaults)
    val notifications = NotificationPermissionService(push = push, defaults = defaults)

    /** App Tracking Transparency on iOS (amendment at); always authorised on Android (amendment az). */
    val tracking = TrackingPermissionService(defaults = defaults)

    /** The court-session notification (wave 3f; replaces Live Activities). Null until 3f provides it. */
    var courtSession: CourtSessionPresenter? = null

    /** Settings → Notifications: `profiles.notification_prefs` (amendment o). */
    val notificationPrefs = NotificationPrefsModel(defaults = defaults)

    /** Launch cinematic / logo sting (CONTRACTS-v2 amendment h). RootScreen shows it over everything while playing. */
    val coldOpen: ColdOpenHost = coldOpen ?: NoColdOpen()

    /** Where screen 9 asked to go; applied once the gate lets the user into the tabs. */
    enum class OnboardingExit { fileCase, invite }

    var pendingOnboardingExit: OnboardingExit? by mutableStateOf(null)
        private set

    /** The user finished (or skipped) the link step of onboarding. */
    var linkStepDone: Boolean by mutableStateOf(false)
        private set

    /** Session: the user closed the paywall gate (or opened an invite link) and is back at the link step. */
    var atLinkStep: Boolean by mutableStateOf(false)
        private set

    /** Session: the user reached the gate and hasn't continued into the app yet (see `AppGate.Input.gateLatched`). */
    var gateLatched: Boolean by mutableStateOf(false)

    /** The onboarding flow has been on screen this session (see `AppGate.Input.onboardingInProgress`). */
    var onboardingInProgress: Boolean by mutableStateOf(false)

    /** An edge call answered 403 `identity_required` while anonymous (see `AppGate.Input.identityRequired`). */
    var identityRequired: Boolean by mutableStateOf(false)
        private set

    /** The uid `userChanged` last loaded (detects an account switch: "sign in to that account instead"). */
    private var loadedUserId: UUID? = null
    private var started = false
    private var liveActivityTask: Job? = null

    init {
        // A fresh ATT answer: re-send the device identifiers to RevenueCat (amendment at).
        tracking.onAnswer = { purchases.syncAttribution() }
        // Preview / demo models start signed in without an auth event: pick up that user's state.
        auth.userId?.let { this.onboarding.userChanged(it, store.me) }
        loadedUserId = auth.userId
    }

    /** Existing accounts (server flag, local mirror, or any case history) never see onboarding. */
    val onboardingDone: Boolean
        get() = store.me?.onboardingCompletedAt != null || onboarding.completedForUser || store.cases.isNotEmpty()

    val gateInput: AppGate.Input
        get() {
            val me = store.me
            val couple = store.couple
            return AppGate.Input(
                authResolved = auth.isResolved,
                signedIn = auth.userId != null,
                storeLoaded = store.hasLoaded,
                hasProfile = me != null,
                onboardingDone = onboardingDone,
                onboardingInProgress = onboardingInProgress,
                loadFailed = store.loadError != null,
                hasCouple = couple != null,
                coupleLinked = couple?.isLinked == true,
                couplePremium = couple?.isPremium == true,
                linkStepDone = linkStepDone,
                atLinkStep = atLinkStep,
                gateLatched = gateLatched,
                unlockedByMe = purchases.unlockedThisSession || (me != null && couple?.payerUserId == me.id),
                pendingJoin = links.pendingJoinCode != null,
                anonymous = auth.userId != null && auth.isAnonymous,
                identityRequired = identityRequired,
            )
        }

    val phase: AppGate.Destination get() = AppGate.destination(gateInput)

    /** The Court tab's dark courtroom art needs light status-bar icons; the rest of the app stays light. */
    val wantsLightStatusBar: Boolean
        get() {
            if (coldOpen.isPlaying) return false
            // The summons explainer (amendment ai) runs the courtroom art edge to edge like the Court tab.
            if (phase == AppGate.Destination.onboarding && onboarding.stepRawValue == ShellOnboarding.summonsIntro) return true
            return phase == AppGate.Destination.tabs && router.tab == AppTab.court
        }

    fun start() {
        if (started) return
        started = true
        store.backend?.let { backend ->
            val edge = backend.edge
            push.sender = { registration -> edge.registerPush(registration) }
        }
        // Silent (data-only) push: refresh the store before the widget snapshot is rewritten,
        // then let the court-session notification catch up.
        push.onSilentRefresh = {
            if (auth.userId != null) {
                store.refresh()
                scheduleLiveActivityReconcile(delayMillis = 0)
            }
        }
        observeLiveActivityInputs()
        // `register_push` needs the profile row: retry once onboarding creates it.
        store.onProfileSaved = { push.retryPending() }
        links.onAuthCallback = { url -> auth.handleAuthCallback(url) }
        auth.onUserChange = { uid -> userChanged(uid) }
        viewModelScope.launch {
            AppNotifications.events.collect { name ->
                runCatching {
                    when (name) {
                        AppNotifications.Name.awPremiumRequired -> premiumRequired()
                        AppNotifications.Name.awIdentityRequired -> identityRequiredByServer()
                        AppNotifications.Name.pleadSilentRefresh -> Unit
                    }
                }
            }
        }
        // Deep links from MainActivity (iOS `.onOpenURL` / `onContinueUserActivity`).
        viewModelScope.launch {
            LaunchLinks.links.collect { uri ->
                runCatching { URI(uri.toString()) }.getOrNull()?.let(links::handle)
                LaunchLinks.consumed()
            }
        }
        auth.start()
    }

    private suspend fun userChanged(uid: UUID?) {
        val previous = loadedUserId
        loadedUserId = uid
        if (uid != null) {
            // Account switch (the anonymous user signed in to an existing account instead): drop
            // everything the anonymous account had loaded before loading the other one.
            val switched = previous != null && previous != uid
            if (switched) clearUserState()
            linkStepDone = defaults.bool(linkKey(uid))
            purchases.configure(uid)
            store.load(uid)
            if (switched && store.backend == null) DemoHarness.adoptExistingAccount(this)
            onboarding.userChanged(uid, store.me)
            // Launch / sign-in: `register_push {timezone}` (even with notifications denied), plus any
            // FCM token that arrived before the session.
            push.userChanged(uid)
            push.registerIfAuthorized()
            courtSession?.userChanged(uid)
            configureNotificationPrefs(uid)
            scheduleLiveActivityReconcile(delayMillis = 0) // launch reconciliation: end anything stale
            purchases.loadOfferings()
        } else {
            clearUserState()
            onboarding.userChanged(null, null)
        }
    }

    /** Signed out, or about to load a different account. */
    private suspend fun clearUserState() {
        push.userChanged(null)
        courtSession?.userChanged(null)
        notificationPrefs.reset()
        store.reset()
        purchases.logOut()
        router.reset()
        linkStepDone = false
        atLinkStep = false
        gateLatched = false
        identityRequired = false
        onboardingInProgress = false
        pendingOnboardingExit = null
    }

    fun finishLinkStep() {
        linkStepDone = true
        atLinkStep = false
        auth.userId?.let { defaults.set(true, linkKey(it)) }
        // Notification permission is asked only from onboarding's pre-prompt (brief §7); here we
        // only (re)register when it was already granted.
        viewModelScope.launch { push.registerIfAuthorized() }
    }

    // MARK: Onboarding

    sealed class CourtIdentityError(message: String) : Exception(message) {
        data object session : CourtIdentityError("session")
        data object profile : CourtIdentityError("profile")
    }

    /**
     * Screen 5's THAT'S ME (amendment p): no login. Without a session, creates the Supabase anonymous
     * user first (RevenueCat is identified with its uid before this continues), then writes
     * `display_name` + `avatar_json` and moves on to the partner step. Throws [CourtIdentityError].
     */
    suspend fun saveCourtIdentity(displayName: String, avatar: Avatar) {
        if (auth.userId == null) {
            try {
                auth.signInAnonymously()
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (_: Exception) {
                throw CourtIdentityError.session
            }
        }
        try {
            store.saveProfile(displayName, avatar)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            throw CourtIdentityError.profile
        }
        // Wave 3b: `OnboardingAvatars.presets` (the same eight identities as PreviewData.onboardingAvatarPresets).
        Analytics.track("onboarding_identity_completed", mapOf("custom_avatar" to (!PreviewData.onboardingAvatarPresets.contains(avatar)).toString()))
        onboarding.advance()
    }

    /**
     * Screen 9's CTA: mark onboarding complete (server + local mirror), then hand off to the gate.
     * Unpaid couples meet the paywall next; paid ones land in the tabs with `exit` opened.
     */
    suspend fun completeOnboarding(exit: OnboardingExit) {
        onboarding.markCompleted()
        Analytics.track("onboarding_completed")
        if (exit == OnboardingExit.fileCase) Analytics.track("first_case_cta_tapped")
        // The link step's job was done by the partner screen.
        linkStepDone = true
        atLinkStep = false
        auth.userId?.let { defaults.set(true, linkKey(it)) }
        pendingOnboardingExit = if (exit == OnboardingExit.invite && store.couple?.isLinked == true) null else exit
        onboardingInProgress = false
        try {
            store.markOnboardingCompleted()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            // The local flag already lets the user through; the server column is re-set next time.
        }
        if (phase == AppGate.Destination.tabs) applyOnboardingExit()
    }

    /** Opens what screen 9 asked for, once the user is in the tabs. */
    fun applyOnboardingExit() {
        val exit = pendingOnboardingExit ?: return
        if (phase != AppGate.Destination.tabs) return
        pendingOnboardingExit = null
        when (exit) {
            OnboardingExit.fileCase -> {
                router.tab = AppTab.home; router.sheet = AppSheet.fileCase
            }
            OnboardingExit.invite -> router.sheet = AppSheet.invite
        }
    }

    /** LinkCoupleView during onboarding: back to the flow (the join link was used or dismissed). */
    fun leaveLinkStepToOnboarding() {
        atLinkStep = false
    }

    /** Foreground / launch: mirror the OS permission states (users returning from system settings). */
    fun refreshPermissions() {
        notifications.refresh()
        tracking.refresh()
    }

    /**
     * Activity resumed (launch included): permissions, then `register_push {timezone}`
     * (coalesced with the launch call inside `PushService`).
     */
    suspend fun becameActive() {
        courtSession?.isForeground = true
        refreshPermissions()
        push.retryPending()
        WidgetSnapshotStore.shared.appBecameActive()
        scheduleLiveActivityReconcile(delayMillis = 0)
    }

    /** Activity left the foreground: no more local court-session starts until it returns. */
    fun resignedActive() {
        courtSession?.isForeground = false
    }

    // MARK: Court session + notification prefs (amendment o)

    private fun configureNotificationPrefs(uid: UUID) {
        val profiles = store.backend?.profiles ?: return
        notificationPrefs.loader = { profiles.fetchNotificationPrefs(uid) }
        notificationPrefs.saver = { prefs -> profiles.updateNotificationPrefs(prefs, uid) }
        viewModelScope.launch { notificationPrefs.load() }
    }

    /** Verdicts the user has on screen or has read this session (ends the verdict session). */
    val openedVerdicts: Set<UUID>
        get() {
            val opened = WidgetSnapshotStore.shared.openedVerdicts.toMutableSet()
            val id = router.courtCaseId ?: store.courtroomCase?.id
            if (phase == AppGate.Destination.tabs && router.tab == AppTab.court && id != null && store.caseById(id)?.isRevealed == true) {
                opened.add(id)
            }
            return opened
        }

    /** Re-runs the court-session planner whenever the cases, verdicts or the visible court change. */
    private fun observeLiveActivityInputs() {
        viewModelScope.launch {
            snapshotFlow { listOf(store.cases, store.verdicts, store.settlements, router.tab, router.courtCaseId) }
                .drop(1)
                .collect { scheduleLiveActivityReconcile() }
        }
    }

    fun scheduleLiveActivityReconcile(delayMillis: Long = 600) {
        if (!liveActivitiesAutomatic) return
        val presenter = courtSession ?: return
        liveActivityTask?.cancel()
        liveActivityTask = viewModelScope.launch {
            if (delayMillis > 0) delay(delayMillis)
            if (auth.userId == null) return@launch
            presenter.reconcile(store, openedVerdicts)
        }
    }

    /**
     * The live app manages the court session from case state. Demo runs only do so with `AWLiveActivity auto`
     * (`summons|verdict|verdictReady` start a fixed demo session instead).
     */
    private val liveActivitiesAutomatic: Boolean
        get() = store.backend != null || DemoHarness.liveActivity == "auto"

    /**
     * Close on the paywall gate: back to the link step (share the invite, wait for the partner),
     * never into the tabs.
     */
    fun closeGate() {
        atLinkStep = true
        router.reset()
    }

    /** The link step's Continue: forward to the gate again. */
    fun returnToGate() {
        atLinkStep = false
        if (store.couple?.isLinked != true) finishLinkStep()
    }

    /** Stay on the link step while an invite link is being used. */
    fun holdLinkStep() {
        atLinkStep = true
    }

    /**
     * "Continue" on the partner-already-paid state: hands back to the gate, which shows
     * SecureAccountView next while the session is anonymous (amendment p), else the tabs.
     */
    fun enterApp() {
        gateLatched = false
    }

    /** Any edge call answered 403 `identity_required`: only possible while anonymous. Show SecureAccountView. */
    fun identityRequiredByServer() {
        if (!auth.isAnonymous) return
        Analytics.track("identity_required_received")
        identityRequired = true
        router.reset()
    }

    /** Joined a couple that is already premium: greet with "your partner already unlocked". */
    fun joinedCouple() {
        if (store.isPremium) gateLatched = true
    }

    /**
     * Joined with a code on the onboarding partner step (amendment as): the link step is done, and a couple the
     * partner already paid for latches the gate so onboarding ends on "already unlocked", never the paywall.
     */
    fun joinedCoupleDuringOnboarding() {
        joinedCouple()
        finishLinkStep()
    }

    /** Any edge call answered 402 `premium_required`: the server is the truth; show the gate. */
    fun premiumRequired() {
        store.markPremiumLapsed()
        router.reset()
        atLinkStep = false
        // `refresh()` is a no-op without a backend (demo / previews), so only the live store needs the call.
        if (store.backend != null) viewModelScope.launch { store.refresh() }
    }

    suspend fun retryLoad() {
        val uid = auth.userId ?: return
        store.load(uid)
    }

    /**
     * `register_push {token: null}` first (it needs the session), so a shared device stops
     * receiving this account's pushes.
     */
    suspend fun signOut() {
        push.unregister()
        auth.signOut()
    }

    /** Calls `delete_account` if the backend has it; otherwise just signs out. */
    suspend fun deleteAccount() {
        val edge = store.backend?.edge
        if (edge != null) {
            push.unregister()
            try {
                edge.deleteAccount()
            } catch (e: EdgeError) {
                if (e.code != "not_found" && e.code != "not_implemented") {
                    push.resume()
                    throw e
                }
                // The backend has no `delete_account` yet: sign out (store policy requires real deletion before release).
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                push.resume()
                throw e
            }
        }
        auth.signOut()
    }

    companion object {
        fun live(): AppModel = AppModel(auth = AuthService(), store = CaseStore(), purchases = PurchasesService(), push = PushService.shared)

        fun linkKey(uid: UUID): String = "linkStepDone.${uid.uuidString}"
    }
}
