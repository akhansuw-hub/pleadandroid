// AppModel through the real services: ports of PaywallGateTests.PaywallModelTests, OnboardingTests.OnboardingCompletionTests,
// PartnerCodeJoinTests.PartnerCodeJoinFlowTests and SecureAccountTests.AnonymousAuthTests (the service half; the
// SecureAccountModel state machine is wave 3b), plus the demo harness factory and AppRouter's routing (wave 2a).
package app.plead.android.app

import app.plead.android.models.Couple
import app.plead.android.models.EdgeError
import app.plead.android.models.Profile
import app.plead.android.services.AppleCredential
import app.plead.android.services.AuthBackend
import app.plead.android.services.AuthLinkError
import app.plead.android.services.AuthService
import app.plead.android.services.AuthUserState
import app.plead.android.services.CaseAction
import app.plead.android.services.CaseRoute
import app.plead.android.services.CaseScreen
import app.plead.android.services.CaseStore
import app.plead.android.services.EdgeErrors
import app.plead.android.services.GoogleCredential
import app.plead.android.services.MainDispatcherRule
import app.plead.android.services.PreviewData
import app.plead.android.services.PurchasesService
import app.plead.android.services.PushService
import app.plead.android.services.SettlementPrompt
import app.plead.android.services.UserDefaults
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

private fun freshDefaults() = UserDefaults.inMemory()

/** A fake Supabase Auth: fixed uids, counts calls, can report "identity already in use". */
private class FakeAuthBackend(
    val anonymousId: UUID = UUID.randomUUID(),
    val existingId: UUID = UUID.randomUUID(),
    var identityInUse: Boolean = false,
) : AuthBackend {
    private var current: AuthUserState? = null
    var anonymousSignIns = 0
    var appleSignIns = 0
    var appleLinks = 0
    val emailLinks = mutableListOf<String>()

    override suspend fun signInAnonymously(): AuthUserState {
        anonymousSignIns += 1
        return AuthUserState(anonymousId, isAnonymous = true).also { current = it }
    }

    override suspend fun signInWithApple(credential: AppleCredential): AuthUserState {
        appleSignIns += 1
        return AuthUserState(existingId, isAnonymous = false).also { current = it }
    }

    override suspend fun linkApple(credential: AppleCredential): AuthUserState {
        appleLinks += 1
        if (identityInUse) throw AuthLinkError.identityInUse
        val s = current ?: throw AuthLinkError.identityInUse
        return s.copy(isAnonymous = false).also { current = it }
    }

    override suspend fun sendSignInCode(email: String) = Unit

    override suspend fun verifySignInCode(email: String, code: String): AuthUserState =
        AuthUserState(existingId, isAnonymous = false, email = email).also { current = it }

    override suspend fun linkEmail(email: String) {
        if (identityInUse) throw AuthLinkError.identityInUse
        emailLinks.add(email)
    }

    override suspend fun verifyEmailLink(email: String, code: String): AuthUserState {
        val s = current ?: throw AuthLinkError.identityInUse
        return s.copy(isAnonymous = false, email = email).also { current = it }
    }

    override suspend fun signOut() {
        current = null
    }
}

private val credential = AppleCredential(idToken = "token", nonce = "nonce")

class PaywallModelTests {
    @get:Rule val main = MainDispatcherRule()

    @Test fun previewCoupleIsPremiumByDefault() {
        assertTrue(PreviewData.store().isPremium)
        assertFalse(PreviewData.unpaidStore().isPremium)
    }

    @Test fun markPremiumLapsedShowsGate() {
        val m = PreviewData.model()
        assertEquals(AppGate.Destination.tabs, m.phase)
        m.store.markPremiumLapsed()
        assertEquals(AppGate.Destination.paywall, m.phase)
        m.closeGate()
        assertEquals(AppGate.Destination.linkCouple, m.phase)
        m.returnToGate()
        assertEquals(AppGate.Destination.paywall, m.phase)
    }

    @Test fun partnerPaidThenContinue() {
        val m = PreviewData.partnerPaidModel()
        assertEquals(AppGate.Destination.partnerPaid, m.phase)
        m.enterApp()
        assertEquals(AppGate.Destination.tabs, m.phase)
    }

    @Test fun openCaseCapIsTheOnlyFilingLimit() {
        assertNull(PreviewData.emptyStore().filingBlocker)
    }
}

/** Screen 9 hand-off through the real AppModel. */
class OnboardingCompletionTests {
    @get:Rule val main = MainDispatcherRule()

    private fun model(couple: Couple?, partner: Profile? = null): AppModel {
        val me = PreviewData.onboardingMe.copy(coupleId = couple?.id)
        val store = CaseStore(preview = me, partner = partner, couple = couple)
        return AppModel(auth = AuthService(previewUserId = me.id), store = store, purchases = PurchasesService(), push = PushService(context = null), defaults = freshDefaults())
    }

    @Test fun newAccountStartsInOnboarding() {
        assertEquals(AppGate.Destination.onboarding, model(null).phase)
        assertEquals(AppGate.Destination.onboarding, model(PreviewData.unlinkedCouple).phase)
    }

    @Test fun establishedAccountsSkip() {
        assertEquals(AppGate.Destination.tabs, PreviewData.model().phase)
        assertEquals(AppGate.Destination.paywall, PreviewData.model(PreviewData.unpaidStore()).phase)
    }

    @Test fun unpaidCoupleMeetsTheGateAfterScreen9() = runTest(main.dispatcher) {
        val m = model(PreviewData.unlinkedCouple)
        m.completeOnboarding(AppModel.OnboardingExit.fileCase)
        assertNotNull(m.store.me?.onboardingCompletedAt)
        assertEquals(AppGate.Destination.paywall, m.phase)
        assertNull(m.router.sheet)
    }

    @Test fun paidCoupleLandsInTabsWithTheFilingSheet() = runTest(main.dispatcher) {
        val m = model(PreviewData.premiumCouple, PreviewData.partner)
        m.completeOnboarding(AppModel.OnboardingExit.fileCase)
        assertEquals(AppGate.Destination.tabs, m.phase)
        assertEquals(AppSheet.fileCase, m.router.sheet)
    }

    @Test fun inviteExitOpensTheInviteOnceInTabs() = runTest(main.dispatcher) {
        val paidSolo = PreviewData.unlinkedCouple.copy(premiumUntil = Instant.now().plusSeconds(86_400))
        val m = model(paidSolo)
        m.completeOnboarding(AppModel.OnboardingExit.invite)
        // Solo but paid: the link step is done (screen 6), so straight into the tabs.
        assertEquals(AppGate.Destination.tabs, m.phase)
        assertEquals(AppSheet.invite, m.router.sheet)
    }
}

/** Join on the partner step through the real AppModel + demo `join_couple`. */
class PartnerCodeJoinFlowTests {
    @get:Rule val main = MainDispatcherRule()

    /** An anonymous user mid-onboarding (profile saved by THAT'S ME), optionally with their own solo invite. */
    private fun model(ownCouple: Couple? = null, outcome: String? = null): AppModel {
        val me = PreviewData.onboardingMe.copy(coupleId = ownCouple?.id)
        val store = CaseStore(preview = me, partner = null, couple = ownCouple)
        store.demoJoinOutcome = outcome
        return AppModel(
            auth = AuthService(previewUserId = me.id, anonymous = true), store = store,
            purchases = PurchasesService(), push = PushService(context = null), defaults = freshDefaults(),
        )
    }

    private suspend fun join(m: AppModel, code: String = "ALEX22") {
        m.store.joinCouple(code, celebrate = false)
        m.joinedCoupleDuringOnboarding()
    }

    @Test fun paidCoupleSkipsThePaywall() = runTest(main.dispatcher) {
        val m = model()
        join(m)
        assertEquals(true, m.store.couple?.isLinked)
        assertEquals("Alex", m.store.partner?.displayName)
        assertFalse(m.store.linkCelebration)
        assertEquals(AppGate.Destination.onboarding, m.phase)
        m.completeOnboarding(AppModel.OnboardingExit.fileCase)
        assertEquals(AppGate.Destination.partnerPaid, m.phase)
        m.enterApp()
        assertEquals(AppGate.Destination.secureAccount, m.phase)
    }

    @Test fun unpaidCoupleMeetsThePaywall() = runTest(main.dispatcher) {
        val m = model(outcome = "unpaid")
        join(m)
        assertEquals(true, m.store.couple?.isLinked)
        assertFalse(m.store.isPremium)
        m.completeOnboarding(AppModel.OnboardingExit.fileCase)
        assertEquals(AppGate.Destination.paywall, m.phase)
    }

    @Test fun joiningFromMyOwnInviteLeavesOneCouple() = runTest(main.dispatcher) {
        val own = PreviewData.unlinkedCouple
        val m = model(ownCouple = own)
        join(m)
        assertNotEquals(own.id, m.store.couple?.id)
        assertEquals(m.store.couple?.id, m.store.me?.coupleId)
        assertEquals(true, m.store.couple?.isLinked)
        // The explore exit never opens the invite share for someone who joined.
        m.completeOnboarding(AppModel.OnboardingExit.invite)
        m.enterApp()
        assertNull(m.router.sheet)
    }

    @Test fun myOwnCodeIsRefused() = runTest(main.dispatcher) {
        val own = PreviewData.unlinkedCouple
        val m = model(ownCouple = own)
        try {
            m.store.joinCouple(own.inviteCode, celebrate = false)
            fail("expected an EdgeError")
        } catch (_: EdgeError) {
        }
        assertEquals(own.id, m.store.couple?.id)
    }

    @Test fun serverErrorsLeaveTheUserUnlinked() = runTest(main.dispatcher) {
        for (outcome in listOf("invalid", "limited")) {
            val m = model(outcome = outcome)
            try {
                m.store.joinCouple("ALEX22", celebrate = false)
                fail("$outcome joined")
            } catch (e: EdgeError) {
                assertTrue(EdgeErrors.joinMessage(e).isNotEmpty())
            }
            assertNull(m.store.couple)
        }
    }

    @Test fun linkStepJoinStillCelebrates() = runTest(main.dispatcher) {
        val m = model()
        m.store.joinCouple("ALEX22")
        assertTrue(m.store.linkCelebration)
    }
}

/** Amendment p: anonymous-first onboarding, login after the paywall (amendment az: Google / Apple OAuth / email). */
class AnonymousAuthTests {
    @get:Rule val main = MainDispatcherRule()

    private fun signedOutModel(backend: FakeAuthBackend): AppModel {
        val m = AppModel(
            auth = AuthService(previewUserId = null, backend = backend), store = CaseStore(preview = null, partner = null, couple = null),
            purchases = PurchasesService(), push = PushService(context = null), defaults = freshDefaults(),
        )
        m.start()
        return m
    }

    /**
     * Screen 5 without any login: THAT'S ME creates the anonymous user, identifies RevenueCat with its
     * uid, writes the profile and moves to the partner step.
     */
    @Test fun thatsMeSignsInAnonymouslyAndSavesTheProfile() = runTest(main.dispatcher) {
        val backend = FakeAuthBackend()
        val m = signedOutModel(backend)
        m.onboarding.applyDemoStep(ShellOnboarding.identity)
        assertNull(m.auth.userId)

        m.saveCourtIdentity("Arif", PreviewData.onboardingAvatarPresets[2])

        assertEquals(backend.anonymousId, m.auth.userId)
        assertTrue(m.auth.isAnonymous)
        assertEquals(backend.anonymousId, m.purchases.identifiedUserId)
        assertEquals("Arif", m.store.me?.displayName)
        assertEquals(PreviewData.onboardingAvatarPresets[2], m.store.me?.avatar)
        assertEquals(ShellOnboarding.partner, m.onboarding.stepRawValue)
        // Back on screen 5 later: the existing anonymous session is reused.
        m.onboarding.applyDemoStep(ShellOnboarding.identity)
        m.saveCourtIdentity("Arif K", PreviewData.onboardingAvatarPresets[2])
        assertEquals(1, backend.anonymousSignIns)
        assertEquals("Arif K", m.store.me?.displayName)
    }

    /** Linking Apple / email never changes the uid: no user-change reload, RevenueCat keeps the same id. */
    @Test fun linkingKeepsTheUid() = runTest(main.dispatcher) {
        val backend = FakeAuthBackend()
        val m = signedOutModel(backend)
        m.saveCourtIdentity("Arif", PreviewData.onboardingAvatarPresets[0])
        val uid = requireNotNull(m.auth.userId)

        val changes = mutableListOf<UUID?>()
        val original = m.auth.onUserChange
        m.auth.onUserChange = { id -> changes.add(id); original?.invoke(id) }

        m.auth.linkApple(credential)
        assertEquals(uid, m.auth.userId)
        assertFalse(m.auth.isAnonymous)
        assertEquals(uid, m.purchases.identifiedUserId)
        assertTrue(changes.isEmpty())
        assertEquals(1, backend.appleLinks)
        assertEquals(0, backend.appleSignIns)
    }

    /** Amendment az: Sign in with Google links the same way (Credential Manager id token → linkIdentityWithIdToken). */
    @Test fun googleLinkKeepsTheUid() = runTest(main.dispatcher) {
        val backend = FakeAuthBackend()
        val auth = AuthService(previewUserId = null, backend = backend)
        var changes = 0
        auth.onUserChange = { changes += 1 }
        auth.signInAnonymously()
        val uid = auth.userId
        auth.linkGoogle(GoogleCredential(idToken = "g", nonce = "n"))
        assertEquals(uid, auth.userId)
        assertFalse(auth.isAnonymous)
        assertEquals(1, changes)
    }

    @Test fun emailLinkKeepsTheUid() = runTest(main.dispatcher) {
        val backend = FakeAuthBackend()
        val auth = AuthService(previewUserId = null, backend = backend)
        var changes = 0
        auth.onUserChange = { changes += 1 }
        auth.signInAnonymously()
        val uid = auth.userId
        auth.linkEmail("arif@example.com")
        auth.verifyEmailLink("123456")
        assertEquals(uid, auth.userId)
        assertFalse(auth.isAnonymous)
        assertEquals("arif@example.com", auth.email)
        assertEquals(1, changes) // the anonymous sign-in only
    }

    @Test fun signOutClears() = runTest(main.dispatcher) {
        val auth = AuthService(previewUserId = null, backend = FakeAuthBackend())
        auth.signInAnonymously()
        assertTrue(auth.isAnonymous)
        auth.signOut()
        assertNull(auth.userId)
        assertFalse(auth.isAnonymous)
    }

    /** The gate after the paywall: anonymous + paid → secure; linking → tabs. */
    @Test fun paidAnonymousUserSecuresThenEntersTheTabs() = runTest(main.dispatcher) {
        val m = PreviewData.anonymousPaidModel()
        m.start()
        assertEquals(AppGate.Destination.secureAccount, m.phase)
        m.auth.linkApple(credential)
        assertEquals(AppGate.Destination.tabs, m.phase)
    }

    @Test fun partnerPaidContinueHandsBackToTheGate() {
        val m = PreviewData.anonymousPaidModel()
        val couple = requireNotNull(m.store.couple).copy(payerUserId = PreviewData.partnerId)
        m.store.demoAdopt(CaseStore(preview = m.store.me, partner = PreviewData.partner, couple = couple))
        m.gateLatched = true
        assertEquals(AppGate.Destination.partnerPaid, m.phase)
        m.enterApp()
        assertEquals(AppGate.Destination.secureAccount, m.phase)
    }

    @Test fun identityRequiredPresentsSecureAccountOnlyWhileAnonymous() {
        val anon = AppModel(
            auth = AuthService(previewUserId = PreviewData.anonId, anonymous = true), store = PreviewData.unpaidStore(),
            purchases = PurchasesService(), push = PushService(context = null), defaults = freshDefaults(),
        )
        assertEquals(AppGate.Destination.paywall, anon.phase)
        anon.identityRequiredByServer()
        assertEquals(AppGate.Destination.secureAccount, anon.phase)

        val linked = PreviewData.model()
        linked.identityRequiredByServer()
        assertEquals(AppGate.Destination.tabs, linked.phase)
    }

    /**
     * Demo `AWSecureConflict`: signing in to the existing account loads that account (Sam & Alex) and
     * the normal gate applies (premium, onboarded → tabs). (SecureAccountModel drives this in wave 3b.)
     */
    @Test fun conflictSignInSwitchesAccountThroughTheGate() = runTest(main.dispatcher) {
        val backend = FakeAuthBackend(anonymousId = PreviewData.anonId, existingId = PreviewData.meId, identityInUse = true)
        val paid = PreviewData.anonymousPaidModel()
        val m = AppModel(
            auth = AuthService(previewUserId = PreviewData.anonId, anonymous = true, backend = backend), store = paid.store,
            purchases = PurchasesService(), push = PushService(context = null), defaults = freshDefaults(),
        )
        m.start()
        backend.signInAnonymously() // the fake's current session = the anonymous user
        assertEquals(AppGate.Destination.secureAccount, m.phase)
        try {
            m.auth.linkApple(credential)
            fail("expected identityInUse")
        } catch (e: AuthLinkError) {
            assertEquals(AuthLinkError.identityInUse, e)
        }
        m.auth.signInInstead(apple = credential)
        assertEquals(PreviewData.meId, m.auth.userId)
        assertEquals(PreviewData.meId, m.store.me?.id)
        assertEquals(PreviewData.meId, m.purchases.identifiedUserId)
        assertEquals(AppGate.Destination.tabs, m.phase)
    }
}

/** The demo harness factory and `apply(to:)` (DemoHarness.swift). */
class DemoHarnessModelTests {
    @get:Rule val main = MainDispatcherRule()

    @Test fun demoStoresMapToTheirGates() {
        fun phase(vararg flags: Pair<String, String>): AppGate.Destination {
            LaunchArguments.set(mapOf("AWDemo" to "YES", *flags))
            return DemoHarness.model(defaults = freshDefaults()).phase
        }
        assertEquals(AppGate.Destination.tabs, phase())
        assertEquals(AppGate.Destination.paywall, phase("AWDemoStore" to "unpaid"))
        assertEquals(AppGate.Destination.partnerPaid, phase("AWDemoStore" to "partnerPaid"))
        assertEquals(AppGate.Destination.onboarding, phase("AWDemoStore" to "signedOut"))
        assertEquals(AppGate.Destination.secureAccount, phase("AWDemoStore" to "anonymousPaid"))
        assertEquals(AppGate.Destination.onboarding, phase("AWDemoStore" to "profile"))
        assertEquals(AppGate.Destination.onboarding, phase("AWOnboardStep" to "partner"))
        assertEquals(AppGate.Destination.tabs, phase("AWDemoStore" to "settled"))
    }

    @Test fun applySetsTabsSheetsAndOnboarding() {
        LaunchArguments.set(mapOf("AWDemo" to "YES", "AWTab" to "court", "AWSheet" to "settlementRoom"))
        val defaults = freshDefaults()
        val m = DemoHarness.model(defaults)
        DemoHarness.apply(to = m, defaults = defaults)
        assertEquals(AppTab.court, m.router.tab)
        assertEquals(AppSheet.settlementRoom(PreviewData.summonedCase.id), m.router.sheet)
        assertTrue(m.router.deferredSummons.contains(PreviewData.summonedCase.id))

        LaunchArguments.set(mapOf("AWDemo" to "YES", "AWOnboardStep" to "summons_intro"))
        val o = DemoHarness.model(defaults)
        DemoHarness.apply(to = o, defaults = defaults)
        assertEquals(12, o.onboarding.stepRawValue)
        assertTrue(o.wantsLightStatusBar)

        // Android: the mahogany summons cover gets light status-bar icons; Home underneath does not.
        LaunchArguments.set(mapOf("AWDemo" to "YES", "AWSheet" to "summons"))
        val su = DemoHarness.model(defaults)
        DemoHarness.apply(to = su, defaults = defaults)
        assertEquals(AppGate.Destination.tabs, su.phase)
        assertTrue(su.wantsLightStatusBar)
        su.router.summonsCaseId = null
        assertFalse(su.wantsLightStatusBar)

        LaunchArguments.set(mapOf("AWDemo" to "YES", "AWSheet" to "exitOffer"))
        val p = DemoHarness.model(defaults)
        DemoHarness.apply(to = p, defaults = defaults)
        assertEquals(AppGate.Destination.paywall, p.phase)
    }

    @Test fun sampleDraftsLabelAtoG() {
        assertEquals(7, DemoHarness.sampleDrafts.size)
        assertEquals(11, run { LaunchArguments.set(mapOf("AWOnboardStep" to "mock_trial")); DemoHarness.onboardStep })
        assertEquals(6, run { LaunchArguments.set(mapOf("AWOnboardStep" to "6")); DemoHarness.onboardStep })
    }
}

/** AppRouter's case routing (AppRouter.swift `open`, `route`, `presentSettlementPrompt`). */
class AppRouterRoutingTests {
    @get:Rule val main = MainDispatcherRule()

    @Test fun openRoutesEachAction() {
        val r = AppRouter()
        assertTrue(r.open(CaseAction.fileDefence, PreviewData.defenceCase))
        assertEquals(AppSheet.defence(PreviewData.defenceCase.id), r.sheet)
        r.sheet = null
        assertTrue(r.open(CaseAction.enterPlea, PreviewData.summonedCase))
        assertEquals(PreviewData.summonedCase.id, r.summonsCaseId)
        assertTrue(r.open(CaseAction.yourTurnInCourt, PreviewData.trialCase))
        assertEquals(AppTab.court, r.tab)
        assertEquals(PreviewData.trialCase.id, r.courtCaseId)
        assertFalse(r.open(CaseAction.requestDefault, PreviewData.summonedCase))
        assertFalse(r.open(CaseAction.markSettlementFulfilled, PreviewData.settledCase))
        assertTrue(r.open(CaseAction.viewRecord, PreviewData.wonCase))
        assertEquals(AppTab.cases, r.tab)
        assertEquals(listOf(PreviewData.wonCase.id), r.casesPath)
    }

    @Test fun routeLandsOnTheRightScreen() {
        val store = PreviewData.store()
        val r = AppRouter()
        r.route(CaseRoute(PreviewData.summonedCase.id, CaseScreen.summons), store)
        assertEquals(PreviewData.summonedCase.id, r.summonsCaseId)
        r.route(CaseRoute(PreviewData.deliberatingCase.id, CaseScreen.deliberation), store)
        assertEquals(AppTab.court, r.tab)
        assertEquals(PreviewData.deliberatingCase.id, r.courtCaseId)
        r.route(CaseRoute(PreviewData.wonCase.id, CaseScreen.judgement), store)
        assertEquals(AppTab.cases, r.tab)
        assertEquals(listOf(PreviewData.wonCase.id), r.casesPath)
    }

    @Test fun anOfferAwaitingMeComesFirstAndPromptsOncePerRound() {
        val store = PreviewData.settlementOfferStore(round = 1)
        val r = AppRouter()
        r.route(CaseRoute(PreviewData.trialCase.id, CaseScreen.court), store)
        assertEquals(AppTab.court, r.tab)
        assertEquals(AppSheet.settlementResponse(PreviewData.trialCase.id), r.sheet)

        val fresh = AppRouter()
        val prompt = requireNotNull(store.settlementPrompt)
        assertEquals(SettlementPrompt(PreviewData.trialCase.id, PreviewData.settlementId, 1), prompt)
        assertTrue(fresh.presentSettlementPrompt(prompt))
        fresh.sheet = null
        assertFalse(fresh.presentSettlementPrompt(prompt)) // once per offer round
        fresh.summonsCaseId = UUID.randomUUID()
        assertFalse(fresh.presentSettlementPrompt(prompt.copy(round = 2))) // never over the summons
    }
}
