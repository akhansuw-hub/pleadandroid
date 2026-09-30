// Port of ArgueWinTests/WidgetSnapshotTests.swift (widgets, CONTRACTS-v2 amendment o: snapshot priority, copy, JSON,
// store lifecycle) and WidgetSetupTests.swift → WidgetSetupServiceTests. The deep-link half is in CaseFlowTests.kt.
package app.plead.android.services

import app.plead.android.models.Case
import app.plead.android.models.Role
import app.plead.android.models.Settlement
import app.plead.android.models.SettlementOffer
import app.plead.android.models.SettlementSource
import app.plead.android.models.SettlementStatus
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WidgetSnapshotPriorityTests {
    @get:Rule val main = MainDispatcherRule()

    private fun snap(store: CaseStore, opened: Set<UUID> = emptySet(), privacy: WidgetPrivacyMode = WidgetPrivacyMode.generic) =
        WidgetSnapshotStore.makeSnapshot(store, privacy, opened)

    /** The partner is the defendant (their move) when `plaintiff` is true. */
    private fun Case.with(plaintiff: Boolean): Case = if (plaintiff) copy(plaintiffId = PreviewData.meId, defendantId = PreviewData.partnerId) else this

    @Test fun summonsComesFirst() {
        val store = PreviewData.store(cases = listOf(PreviewData.trialCase, PreviewData.summonedCase, PreviewData.deliberatingCase, PreviewData.judgementCase))
        val s = snap(store)
        val p = requireNotNull(s.primary)
        assertEquals(WidgetState.summoned, p.state)
        assertEquals(PreviewData.summonedCase.id, p.caseId)
        assertEquals(15, p.caseNumber)
        assertEquals(WidgetAction.enterPlea, p.nextAction)
        assertEquals("Alex", p.partnerDisplayName)
        assertEquals("The Thermostat Incident", p.caseTitle)
        assertEquals(PreviewData.summonedCase.deadlineAt, p.deadlineAt)
        assertEquals("plead://case/${PreviewData.summonedCase.id.toString().lowercase()}/plea", p.link)
        assertEquals(4, s.activeCaseCount)
    }

    @Test fun myTurnBeatsDeliberation() {
        val s = snap(PreviewData.store(cases = listOf(PreviewData.trialCase, PreviewData.deliberatingCase)))
        assertEquals(WidgetState.yourTurn, s.primary?.state)
        assertEquals(WidgetAction.takeTurn, s.primary?.nextAction)
        assertEquals(PreviewData.trialCase.deadlineAt, s.primary?.deadlineAt)
        assertEquals("turn", s.primary?.link?.lastPathComponent)
    }

    @Test fun partnersTurnIsNotMine() {
        val theirs = PreviewData.trialCase.copy(phaseTurnOwner = Role.defendant)
        val s = snap(PreviewData.store(cases = listOf(theirs, PreviewData.deliberatingCase)))
        assertEquals(WidgetState.deliberating, s.primary?.state)
        assertEquals(PreviewData.deliberatingCase.trialAt, s.primary?.deadlineAt)
        assertEquals("deliberation", s.primary?.link?.lastPathComponent)
    }

    @Test fun settlementOfferAwaitingMe() {
        // One fixed expiry, so the comparison below is exact.
        val offer = PreviewData.settlementOffer(round = 1).copy(expiresAt = Instant.ofEpochSecond(1_900_000_000))
        val store = PreviewData.store(
            cases = listOf(PreviewData.settlementTrialCase, PreviewData.deliberatingCase),
            settlements = listOf(PreviewData.settlementPending(round = 1)),
            settlementOffers = listOf(offer),
        )
        val p = requireNotNull(snap(store).primary)
        assertEquals(WidgetState.settlement, p.state)
        assertEquals(WidgetAction.reviewOffer, p.nextAction)
        assertEquals(Instant.ofEpochSecond(1_900_000_000), p.deadlineAt)
        assertEquals("settlement", p.link.lastPathComponent)
    }

    @Test fun summonsWithAPendingOfferShowsTheOffer() {
        val sid = UUID.randomUUID()
        val summoned = PreviewData.summonedCase.copy(settlementId = sid)
        val offer = Settlement(id = sid, caseId = summoned.id, status = SettlementStatus.proposed, initiatedBy = PreviewData.partnerId)
        val s = snap(PreviewData.store(cases = listOf(summoned), settlements = listOf(offer)))
        assertEquals(WidgetState.settlement, s.primary?.state)
    }

    @Test fun myOwnOfferIsNotAnAction() {
        val settlement = PreviewData.settlementPending(round = 2).copy(initiatedBy = PreviewData.meId)
        val mine = SettlementOffer(id = UUID.randomUUID(), settlementId = settlement.id, roundNumber = 2, proposedBy = PreviewData.meId, body = "x", source = SettlementSource.custom)
        val s = snap(PreviewData.store(cases = listOf(PreviewData.settlementTrialCase), settlements = listOf(settlement), settlementOffers = listOf(mine)))
        assertNull(s.primary)
        assertEquals(1, s.activeCaseCount)
    }

    @Test fun verdictReadyUntilOpenedThenJudgement() {
        val store = PreviewData.judgementStore()
        val s = snap(store)
        assertEquals(WidgetState.verdictReady, s.primary?.state)
        assertEquals(WidgetAction.readVerdict, s.primary?.nextAction)
        assertNull(s.primary?.deadlineAt)
        assertEquals("verdict", s.primary?.link?.lastPathComponent)

        val after = snap(store, opened = setOf(PreviewData.judgementCase.id))
        assertEquals(WidgetState.judgementDue, after.primary?.state)
        assertEquals(PreviewData.judgementCase.id, after.primary?.caseId) // I won and choose
        assertEquals(WidgetAction.viewJudgement, after.primary?.nextAction)
        assertEquals("judgement", after.primary?.link?.lastPathComponent)
    }

    @Test fun outstandingJudgementOnAClosedCase() {
        val s = snap(PreviewData.store(cases = listOf(PreviewData.guiltyCase, PreviewData.wonCase)))
        assertEquals(WidgetState.judgementDue, s.primary?.state)
        assertEquals(PreviewData.guiltyCase.id, s.primary?.caseId)
        assertEquals(PreviewData.guiltyOverdueJudgement.dueAt, s.primary?.deadlineAt)
        assertEquals(0, s.activeCaseCount)
    }

    @Test fun settlementAgreementOutstanding() {
        val store = PreviewData.store(
            cases = listOf(PreviewData.settledCase, PreviewData.wonCase), settlements = listOf(PreviewData.settledSettlement),
            settlementOffers = listOf(PreviewData.settledOffer),
        )
        val p = requireNotNull(snap(store).primary)
        assertEquals(WidgetState.agreementDue, p.state)
        assertEquals(WidgetAction.viewCase, p.nextAction)
        assertEquals(PreviewData.settledSettlement.dueAt, p.deadlineAt)
        assertEquals("settlement", p.link.lastPathComponent)
    }

    @Test fun nothingToDo() {
        val waiting = snap(PreviewData.store(cases = listOf(PreviewData.defenceCase.with(plaintiff = true), PreviewData.wonCase, PreviewData.tiedCase)))
        assertNull(waiting.primary)
        assertEquals(WidgetState.none, waiting.state)
        assertEquals(1, waiting.activeCaseCount)
        assertEquals("plead://home", waiting.link)

        val signedOut = snap(CaseStore(preview = null, partner = null, couple = null))
        assertTrue(signedOut.primary == null && signedOut.activeCaseCount == 0)
    }

    @Test fun privacyModeIsCarried() {
        val store = PreviewData.store(cases = listOf(PreviewData.summonedCase))
        assertEquals(WidgetPrivacyMode.generic, snap(store).privacyMode)
        assertEquals(WidgetPrivacyMode.detailed, snap(store, privacy = WidgetPrivacyMode.detailed).privacyMode)
    }
}

class WidgetCopyTests {
    @Test fun headlinesFollowTheBrief() {
        val expected = mapOf(
            WidgetState.summoned to "You've been summoned", WidgetState.yourTurn to "Your response is due", WidgetState.settlement to "Settlement offer waiting",
            WidgetState.deliberating to "The court is deliberating", WidgetState.verdictReady to "The judge has ruled", WidgetState.judgementDue to "Judgement outstanding",
            WidgetState.agreementDue to "Agreement outstanding", WidgetState.none to "Court adjourned",
        )
        for (state in WidgetState.entries) {
            assertEquals(expected[state], WidgetSnapshot.headline(state, WidgetPrivacyMode.generic))
            assertEquals(expected[state], WidgetSnapshot.headline(state, WidgetPrivacyMode.detailed))
        }
    }

    @Test fun genericCopyNeverLeaksTheCase() {
        for (state in WidgetState.entries) {
            if (state == WidgetState.none) continue
            val snap = WidgetSnapshot.sample(state, privacy = WidgetPrivacyMode.generic)
            val lines = listOf(
                WidgetSnapshot.headline(snap.primary, WidgetPrivacyMode.generic),
                WidgetSnapshot.detailLine(snap.primary, WidgetPrivacyMode.generic),
                WidgetSnapshot.accessibilityLabel(snap, detailed = false),
            )
            for (line in lines) {
                assertFalse("$state: $line", line.contains("Dinner Incident"))
                assertFalse("$state: $line", line.contains("Sophie"))
            }
        }
    }

    @Test fun detailedCopyShowsTheTitle() {
        val snap = WidgetSnapshot.sample(WidgetState.summoned, privacy = WidgetPrivacyMode.detailed)
        assertEquals("The Dinner Incident", WidgetSnapshot.detailLine(snap.primary, WidgetPrivacyMode.detailed))
        assertTrue(WidgetSnapshot.accessibilityLabel(snap, detailed = true).contains("Sophie"))
        assertEquals("No open cases", WidgetSnapshot.detailLine(null, WidgetPrivacyMode.detailed, activeCaseCount = 0))
        assertEquals("Nothing needs you right now", WidgetSnapshot.detailLine(null, WidgetPrivacyMode.generic, activeCaseCount = 2))
    }

    @Test fun actionsAndChips() {
        assertEquals(WidgetAction.enterPlea, WidgetSnapshot.action(WidgetState.summoned))
        assertEquals(WidgetAction.readVerdict, WidgetSnapshot.action(WidgetState.verdictReady))
        assertEquals(WidgetAction.openApp, WidgetSnapshot.action(WidgetState.none))
        assertEquals("Enter plea", WidgetAction.enterPlea.title)
        assertEquals("Awaiting your plea", WidgetSnapshot.statusChip(WidgetState.summoned))
        assertEquals("1 active case", WidgetSnapshot.activeCountLine(1))
        assertEquals("Open Plead", WidgetAction.openApp.title)
    }

    @Test fun countdownOnlyWhileAhead() {
        val now = Instant.ofEpochSecond(1_800_000_000)
        val snap = WidgetSnapshot.sample(WidgetState.summoned, now = now)
        assertNotNull(WidgetSnapshot.activeDeadline(snap.primary, now))
        assertNull(WidgetSnapshot.activeDeadline(snap.primary, now.plusSeconds(6 * 3600)))
        assertNull(WidgetSnapshot.activeDeadline(WidgetSnapshot.sample(WidgetState.verdictReady, now = now).primary, now))
    }
}

class WidgetSnapshotJSONTests {
    private val date: Instant = Instant.ofEpochSecond(1_790_000_000)

    @Test fun roundTrip() {
        for (state in WidgetState.entries) {
            for (privacy in WidgetPrivacyMode.entries) {
                val snap = WidgetSnapshot.sample(state, privacy = privacy, now = date)
                assertEquals(snap, WidgetSnapshot.decode(snap.encoded()))
            }
        }
    }

    @Test fun contractKeys() {
        val json = WidgetSnapshot.sample(WidgetState.yourTurn, privacy = WidgetPrivacyMode.generic, now = date).encoded()
        for (key in listOf(
            "\"updatedAt\"", "\"privacyMode\":\"generic\"", "\"activeCaseCount\":1", "\"primary\"", "\"caseId\"",
            "\"caseNumber\":21", "\"state\":\"yourTurn\"", "\"caseTitle\"", "\"partnerDisplayName\"",
            "\"nextAction\":\"takeTurn\"", "\"deadlineAt\"", "\"link\"",
        )) {
            assertTrue(key, json.contains(key))
        }
        // ISO-8601 dates; no evidence / charge / verdict text.
        assertTrue(json.contains("2026-09-21T"))
        for (banned in listOf("charge", "exhibit", "evidence", "transcript", "remedy", "sentence")) assertFalse(json.lowercase().contains(banned))
        assertEquals("widget-snapshot.json", WidgetSnapshot.fileName)
        assertEquals("group.app.plead.shared", WidgetSnapshot.appGroup)
        // Sorted keys (Swift `.sortedKeys`).
        assertEquals(listOf("activeCaseCount", "primary", "privacyMode", "updatedAt"), (JSONCoding.json.parseToJsonElement(json) as JsonObject).keys.toList())
    }

    @Test fun decodesTheEmptyState() {
        val snap = WidgetSnapshot.decode("""{"updatedAt":"2026-09-24T09:00:00Z","privacyMode":"detailed","activeCaseCount":0}""")
        assertTrue(snap.primary == null && snap.privacyMode == WidgetPrivacyMode.detailed && snap.state == WidgetState.none)
    }

    @Test fun saveAndLoadFromDisk() {
        val defaults = UserDefaults.inMemory()
        val snap = WidgetSnapshot.sample(WidgetState.deliberating, now = date)
        snap.save(defaults)
        assertEquals(snap, WidgetSnapshot.load(defaults))
        assertNull(WidgetSnapshot.load(UserDefaults.inMemory()))
        assertTrue(snap.sameContent(WidgetSnapshot(updatedAt = Instant.now(), privacyMode = snap.privacyMode, activeCaseCount = 1, primary = snap.primary)))
    }
}

// MARK: - Store lifecycle: opened verdicts, debounce, sign-out

class WidgetSnapshotStoreLifecycleTests {
    @get:Rule val main = MainDispatcherRule()

    private fun makeStore(defaults: UserDefaults, now: () -> Instant = { Instant.now() }): WidgetSnapshotStore =
        WidgetSnapshotStore(defaults, now).also {
            it.debounce = 40.milliseconds
            it.reloadTimelines = {}
        }

    /** Polls until `condition` holds (the store writes after its debounce). */
    private suspend fun eventually(timeoutMs: Long = 3_000, condition: () -> Boolean): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            sendApplyNotifications()
            if (condition()) return true
            delay(20)
            waited += 20
        }
        return condition()
    }

    @Test fun defaultDebounceIsTwoSecondsAndCoalesces() = runTest(main.dispatcher) {
        val defaults = UserDefaults.inMemory()
        assertEquals(2.seconds, WidgetSnapshotStore(defaults).debounce)

        val widgets = WidgetSnapshotStore(defaults).also { it.reloadTimelines = {} }
        widgets.debounce = 400.milliseconds
        val store = PreviewData.judgementStore() // held weakly by the widget store
        widgets.start(store)
        delay(100)
        assertNull(WidgetSnapshot.load(defaults)) // still inside the debounce window
        // Further changes inside the window (here: App Group writes) coalesce, never postpone.
        repeat(5) {
            defaults.set(UUID.randomUUID().toString(), "noise")
            delay(60)
        }
        assertTrue(eventually(700) { WidgetSnapshot.load(defaults) != null })
        assertEquals(WidgetState.verdictReady, WidgetSnapshot.load(defaults)?.primary?.state)
        assertNotNull(store)
    }

    @Test fun openingTheVerdictMovesThePrimaryOnAndPersists() = runTest(main.dispatcher) {
        val defaults = UserDefaults.inMemory()
        val caseId = PreviewData.judgementCase.id
        val store = PreviewData.judgementStore()

        val widgets = makeStore(defaults)
        widgets.start(store)
        assertTrue(eventually { WidgetSnapshot.load(defaults)?.primary?.state == WidgetState.verdictReady })
        assertEquals("The judge has ruled", WidgetSnapshot.load(defaults)?.primary?.let { WidgetSnapshot.headline(it.state) })

        // The Court tab's hook.
        widgets.markVerdictOpened(caseId)
        assertTrue(eventually { WidgetSnapshot.load(defaults)?.primary?.state == WidgetState.judgementDue })
        val after = requireNotNull(WidgetSnapshot.load(defaults))
        assertTrue(after.primary?.state != WidgetState.verdictReady)
        assertTrue(WidgetSnapshot.headline(after.primary) != "The judge has ruled")
        assertTrue(after.primary?.caseId == caseId && after.primary?.link?.lastPathComponent == "judgement")

        // Relaunch (a new store on the same App Group) within the day: still opened.
        val relaunched = makeStore(defaults) { Instant.now().plusSeconds(20 * 3600) }
        assertEquals(setOf(caseId), relaunched.openedVerdicts)
        relaunched.start(store)
        assertEquals(WidgetState.judgementDue, relaunched.currentSnapshot()?.primary?.state)
        // After 24 h the entry expires (and is pruned from the App Group on the next write).
        val nextDay = makeStore(defaults) { Instant.now().plusSeconds(24 * 3600 + 60) }
        assertTrue(nextDay.openedVerdicts.isEmpty())
    }

    @Test fun openedIdsExpireAndArePruned() {
        val defaults = UserDefaults.inMemory()
        var clock = Instant.ofEpochSecond(1_790_000_000)
        val widgets = makeStore(defaults) { clock }
        val old = UUID.randomUUID()
        val fresh = UUID.randomUUID()
        widgets.markVerdictOpened(old)
        clock = clock.plusSeconds(23 * 3600)
        widgets.markVerdictOpened(fresh)
        assertEquals(setOf(old, fresh), widgets.openedVerdicts)
        clock = clock.plusSeconds(2 * 3600) // `old` is now 25 h old
        assertEquals(setOf(fresh), widgets.openedVerdicts)
        widgets.markVerdictOpened(UUID.randomUUID()) // any write prunes
        val raw = requireNotNull(defaults.dictionary(WidgetSnapshotStore.openedVerdictsKey))
        assertNull(raw[old.toString().lowercase()])
        assertNotNull(raw[fresh.toString().lowercase()])
        assertEquals(2, raw.size)
    }

    @Test fun signOutWritesCourtAdjourned() = runTest(main.dispatcher) {
        val defaults = UserDefaults.inMemory()
        val store = PreviewData.store(cases = listOf(PreviewData.summonedCase, PreviewData.trialCase))
        val widgets = makeStore(defaults)
        widgets.start(store)
        assertTrue(eventually { WidgetSnapshot.load(defaults)?.primary?.state == WidgetState.summoned })

        store.reset() // what AppModel does on sign-out
        assertTrue(eventually { WidgetSnapshot.load(defaults)?.primary == null })
        val empty = requireNotNull(WidgetSnapshot.load(defaults))
        assertTrue(empty.activeCaseCount == 0 && empty.state == WidgetState.none)
        assertEquals("Court adjourned", WidgetSnapshot.headline(empty.primary))
        assertEquals(WidgetSnapshot.homeLink, empty.link)

        // The explicit reset path writes the same neutral snapshot immediately.
        defaults.removeObject(WidgetSnapshot.fileName)
        widgets.clear()
        assertTrue(WidgetSnapshot.load(defaults)?.primary == null && WidgetSnapshot.headline(WidgetSnapshot.load(defaults)?.primary) == "Court adjourned")
    }
}

class WidgetSetupServiceTests {
    @Test fun onlyPleadKindsCount() = runTest {
        val configs = listOf(
            WidgetConfiguration("com.other.Weather", WidgetFamily.systemSmall),
            WidgetConfiguration("PleadStatusWidget", WidgetFamily.accessoryCircular),
            WidgetConfiguration("PleadStatusWidget", WidgetFamily.systemMedium),
            WidgetConfiguration("PleadStatusWidget", WidgetFamily.systemMedium),
        )
        assertEquals(listOf(WidgetFamily.systemMedium, WidgetFamily.accessoryCircular), WidgetSetupService.pleadFamilies(configs))
        assertTrue(WidgetSetupService.pleadFamilies(listOf(WidgetConfiguration("com.other.Weather", WidgetFamily.systemSmall))).isEmpty())
    }

    @Test fun kindMatchesTheWidgetExtension() {
        assertEquals(setOf("PleadStatusWidget"), WidgetSetupService.pleadWidgetKinds)
    }

    @Test fun fixedServiceAnswers() = runTest {
        val s = WidgetSetupService.fixed(families = listOf(WidgetFamily.systemSmall), activitiesEnabled = false)
        assertEquals(listOf(WidgetFamily.systemSmall), s.detectConfiguredWidgets())
        assertFalse(s.liveActivitiesEnabled)
        assertEquals("accessory_rectangular", WidgetSetupService.analyticsName(WidgetFamily.accessoryRectangular))
    }

    /** Android: a pinned widget's width picks its family (1×1 cell ↔ the Lock Screen accessory). */
    @Test fun pinnedSizesMapToFamilies() {
        assertEquals(WidgetFamily.accessoryCircular, WidgetFamily.fromSize(70))
        assertEquals(WidgetFamily.systemSmall, WidgetFamily.fromSize(150))
        assertEquals(WidgetFamily.systemMedium, WidgetFamily.fromSize(300))
    }
}
