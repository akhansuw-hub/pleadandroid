// Port of ArgueWinTests/SettlementTests.swift. Settle Outside Court (CONTRACTS-v2 amendment n).
package app.plead.android.features.settlement

import app.plead.android.app.AppRouter
import app.plead.android.app.AppSheet
import app.plead.android.app.AppTab
import app.plead.android.models.Case
import app.plead.android.models.CaseStatus
import app.plead.android.models.EdgeError
import app.plead.android.models.Settlement
import app.plead.android.models.SettlementOffer
import app.plead.android.models.SettlementSource
import app.plead.android.models.SettlementStatus
import app.plead.android.models.SettlementSuggestionKind
import app.plead.android.models.TrialPhase
import app.plead.android.services.CaseAction
import app.plead.android.services.CaseRoute
import app.plead.android.services.CaseScreen
import app.plead.android.services.CaseStore
import app.plead.android.services.DeepLink
import app.plead.android.services.DemoTrialSimulator
import app.plead.android.services.EdgeErrors
import app.plead.android.services.EdgeFunctions
import app.plead.android.services.JSONCoding
import app.plead.android.services.MainDispatcherRule
import app.plead.android.services.PreviewData
import app.plead.android.services.SettlementPrompt
import app.plead.android.services.SettlementRules
import app.plead.android.services.StubSupabase
import app.plead.android.services.isSettlementRoundLimit
import app.plead.android.services.isUnsafeTerms
import app.plead.android.services.nextAction
import app.plead.android.services.statusTitle
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

private val me: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")       // PreviewData.meId
private val partner: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")  // PreviewData.partnerId

private fun pending(kase: Case, round: Int = 1, initiatedBy: UUID = partner, status: SettlementStatus? = null): Settlement =
    Settlement(
        id = UUID.randomUUID(), caseId = kase.id,
        status = status ?: if (round == 1) SettlementStatus.proposed else SettlementStatus.countered,
        initiatedBy = initiatedBy, currentRound = round,
    )

private fun offer(s: Settlement, round: Int, by: UUID): SettlementOffer = SettlementOffer(
    id = UUID.randomUUID(), settlementId = s.id, roundNumber = round, proposedBy = by, body = "Replace the meal.",
    source = SettlementSource.ai, dueDays = 3,
)

private suspend fun expectEdgeError(block: suspend () -> Unit): EdgeError {
    try {
        block()
    } catch (e: EdgeError) {
        return e
    }
    fail("expected an EdgeError")
    throw IllegalStateException()
}

class SettlementCaseFlowTests {
    @get:Rule val main = MainDispatcherRule()
    private val trial = PreviewData.trialCase

    @Test fun pendingSettlementReplacesTheCourtAction() {
        val s = pending(trial)
        val o = offer(s, 1, partner)
        // The receiver responds; the proposer waits; the court action is gone for both.
        assertEquals(CaseAction.respondToSettlement, trial.nextAction(me, null, s, o))
        assertEquals(CaseAction.awaitSettlement, trial.nextAction(partner, null, s, o))
        assertEquals("Respond to the settlement offer", CaseAction.respondToSettlement.title)
        assertEquals("Settlement offer sent · waiting", CaseAction.awaitSettlement.title)
        assertTrue(CaseAction.respondToSettlement.isActionable && !CaseAction.awaitSettlement.isActionable)

        // Roles swap on a counter (round 2 proposed by me).
        val s2 = pending(trial, round = 2)
        val o2 = offer(s2, 2, me)
        assertEquals(CaseAction.awaitSettlement, trial.nextAction(me, null, s2, o2))
        assertEquals(CaseAction.respondToSettlement, trial.nextAction(partner, null, s2, o2))
        // Without the offer loaded, the round parity decides (round 2 = the non-initiator proposed).
        assertEquals(CaseAction.awaitSettlement, trial.nextAction(me, null, s2, null))
    }

    @Test fun resolvedSettlementsFallBackToTheCourt() {
        for (status in listOf(SettlementStatus.rejected, SettlementStatus.withdrawn, SettlementStatus.expired)) {
            val s = pending(trial, status = status)
            assertEquals(CaseAction.yourTurnInCourt, trial.nextAction(me, null, s, null))
        }
        // Another case's settlement is ignored.
        val other = pending(PreviewData.summonedCase)
        assertEquals(CaseAction.yourTurnInCourt, trial.nextAction(me, null, other, null))
    }

    @Test fun settledCasesHaveNoCourtAction() {
        val settled = trial.copy(status = CaseStatus.closedSettled, phase = null, phaseTurnOwner = null)
        var s = pending(settled, status = SettlementStatus.accepted)
        assertEquals(CaseAction.viewRecord, settled.nextAction(me))
        assertEquals(CaseAction.markSettlementFulfilled, settled.nextAction(me, null, s, null))
        assertEquals(CaseAction.markSettlementFulfilled, settled.nextAction(partner, null, s, null))
        s = s.copy(status = SettlementStatus.fulfilled)
        assertEquals(CaseAction.viewRecord, settled.nextAction(me, null, s, null))
        assertEquals(CaseAction.viewRecord, settled.nextAction(me, null, null, null))
        assertFalse(CaseStatus.closedSettled.isOpen)
        assertEquals("Settled out of court", settled.statusTitle)
    }

    @Test fun storeOverlaysThePendingSettlement() {
        val store = PreviewData.settlementOfferStore(round = 1)
        val id = PreviewData.trialCase.id
        assertTrue(store.pendingSettlementForMe(id))
        assertEquals(CaseAction.respondToSettlement, store.nextAction(requireNotNull(store.caseById(id))))
        assertEquals(id, store.settlementPrompt?.caseId)
        assertEquals(1, store.latestOffer(id)?.roundNumber)
        assertFalse(store.canProposeSettlement(id))

        val counter = PreviewData.settlementOfferStore(round = 2)
        assertTrue(counter.pendingSettlementForMe(id))
        assertEquals(partner, counter.latestOffer(id)?.proposedBy)
    }

    @Test fun routerOpensTheSettlementSheets() {
        val router = AppRouter()
        val kase = PreviewData.trialCase
        assertTrue(router.open(CaseAction.respondToSettlement, kase))
        assertEquals(AppSheet.settlementResponse(kase.id), router.sheet)
        router.sheet = null
        assertTrue(router.open(CaseAction.awaitSettlement, kase))
        assertEquals(AppSheet.settlementResponse(kase.id), router.sheet)
        router.sheet = null
        assertFalse(router.open(CaseAction.markSettlementFulfilled, kase))

        // A prompt is presented once per offer round, and never over another sheet.
        val prompt = SettlementPrompt(kase.id, UUID.randomUUID(), 1)
        router.sheet = AppSheet.settings
        assertFalse(router.presentSettlementPrompt(prompt))
        router.sheet = null
        assertTrue(router.presentSettlementPrompt(prompt))
        router.sheet = null
        assertFalse(router.presentSettlementPrompt(prompt))
        assertTrue(router.presentSettlementPrompt(SettlementPrompt(kase.id, prompt.settlementId, 2)))
    }

    @Test fun pushAndCourtRoutesGoToTheOfferFirst() {
        val store = PreviewData.settlementOfferStore(round = 1)
        val id = PreviewData.trialCase.id
        val link = requireNotNull(DeepLink.parse(push = mapOf("case_id" to id.toString().uppercase(), "screen" to "settlement")))
        assertEquals(DeepLink.caseRoute(CaseRoute(id, CaseScreen.settlement)), link)
        val router = AppRouter()
        router.route(CaseRoute(id, CaseScreen.settlement), store)
        assertEquals(AppSheet.settlementResponse(id), router.sheet)
        router.sheet = null
        router.route(CaseRoute(id, CaseScreen.court), store)
        assertTrue(router.sheet == AppSheet.settlementResponse(id) && router.tab == AppTab.court)
    }
}

class SettlementCanProposeTests {
    @get:Rule val main = MainDispatcherRule()

    @Test fun matrixByStatusAndPhase() {
        var kase = PreviewData.trialCase
        for (status in CaseStatus.entries) {
            kase = kase.copy(status = status, phase = null)
            val expected = listOf(CaseStatus.summoned, CaseStatus.defence, CaseStatus.scheduling, CaseStatus.trial).contains(status)
            assertEquals("status $status", expected, SettlementRules.canPropose(kase, me, null))
            assertEquals("status $status", expected, SettlementRules.canPropose(kase, partner, null))
        }
        kase = kase.copy(status = CaseStatus.trial)
        for (phase in TrialPhase.entries) {
            kase = kase.copy(phase = phase)
            val expected = phase != TrialPhase.plaintiffClosing && phase != TrialPhase.defendantClosing
            assertEquals("phase $phase", expected, SettlementRules.canPropose(kase, me, null))
        }
    }

    @Test fun blockedByAPendingSettlementOrForStrangers() {
        var kase = PreviewData.trialCase
        assertFalse(SettlementRules.canPropose(kase, UUID.randomUUID(), null))
        assertFalse(SettlementRules.canPropose(kase, null, null))
        assertFalse(SettlementRules.canPropose(kase, me, pending(kase)))
        assertTrue(SettlementRules.canPropose(kase, me, pending(kase, status = SettlementStatus.rejected)))
        kase = kase.copy(settlementId = UUID.randomUUID())
        assertFalse(SettlementRules.canPropose(kase, me, null))
    }

    @Test fun storeAnswers() {
        val store = PreviewData.store()
        assertTrue(store.canProposeSettlement(PreviewData.summonedCase.id))
        assertTrue(store.canProposeSettlement(PreviewData.trialCase.id))
        assertFalse(store.canProposeSettlement(PreviewData.deliberatingCase.id))
        assertFalse(store.canProposeSettlement(PreviewData.wonCase.id))
        assertFalse(store.canProposeSettlement(UUID.randomUUID()))
    }

    @Test fun pendingSummonsHidesWhileASettlementIsPending() {
        val store = PreviewData.store()
        assertEquals(PreviewData.summonedCase.id, store.pendingSummons?.id)
        store.demoSetSettlement(pending(PreviewData.summonedCase, initiatedBy = me))
        assertNull(store.pendingSummons)
        val rejected = requireNotNull(store.settlement(PreviewData.summonedCase.id)).copy(status = SettlementStatus.rejected)
        store.demoSetSettlement(rejected)
        assertEquals(PreviewData.summonedCase.id, store.pendingSummons?.id)
    }
}

class SettlementResponseModelTests {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())

    @Test fun roundCapHidesCounterOnTheFinalRound() {
        val id = PreviewData.trialCase.id
        for (round in 1..3) {
            val store = PreviewData.settlementOfferStore(round = round)
            val model = SettlementResponseModel(id, store)
            assertEquals(SettlementResponseModel.Role.receiver, model.role)
            assertEquals(round, model.round)
            assertEquals("Round $round of 3", model.roundLine)
            assertEquals("round $round", round < 3, model.canCounter)
            assertEquals(round == 3, model.isFinalRound)
        }
        assertEquals(SettlementCopy.finalRound, SettlementResponseModel(id, PreviewData.settlementOfferStore(round = 3)).subline)
    }

    @Test fun proposerSeesTheWaitingState() {
        val store = PreviewData.store()
        val s = pending(PreviewData.trialCase, initiatedBy = me)
        store.demoSetSettlement(s)
        store.demoUpsertOffer(offer(s, 1, me))
        val model = SettlementResponseModel(PreviewData.trialCase.id, store)
        assertEquals(SettlementResponseModel.Role.proposer, model.role)
        assertFalse(model.canCounter)
        assertEquals("Offer sent · waiting for Alex", model.headline)
    }

    @Test fun counterIsRefusedPastTheCap() = runTest(main.dispatcher) {
        val store = PreviewData.settlementOfferStore(round = 3)
        val e = expectEdgeError { store.counterSettlement(PreviewData.trialCase.id, "One more?", SettlementSource.custom) }
        assertTrue(e.isSettlementRoundLimit)
        assertEquals("No more counter-offers. Accept it, or see them in court.", EdgeErrors.settlementMessage(e))
    }

    @Test fun roomModeFollowsTheOffer() {
        val id = PreviewData.trialCase.id
        val counter = SettlementRoomModel(id, PreviewData.settlementOfferStore(round = 2))
        assertTrue(counter.mode == SettlementRoomModel.Mode.counter && counter.nextRound == 3 && counter.isAvailable && counter.title == "Counter offer")
        assertEquals(2, counter.incomingOffer?.roundNumber)
        val final = SettlementRoomModel(id, PreviewData.settlementOfferStore(round = 3))
        assertTrue(final.mode == SettlementRoomModel.Mode.counter && !final.isAvailable)
        val propose = SettlementRoomModel(PreviewData.summonedCase.id, PreviewData.store())
        assertTrue(propose.mode == SettlementRoomModel.Mode.propose && propose.isAvailable && propose.ctaTitle == "PROPOSE SETTLEMENT")
        assertEquals("A disagreement about the Thermostat Incident.", propose.contextLine)
    }

    @Test fun customTermsAreCappedAndValidated() {
        val model = SettlementRoomModel(PreviewData.summonedCase.id, PreviewData.store())
        assertEquals(7, model.customDays)
        model.select(SettlementRoomModel.Choice.custom)
        assertFalse(model.canSend)
        model.customText = "a".repeat(260)
        assertEquals(SettlementRules.bodyLimit, model.customText.length)
        assertTrue(model.canSend)
        model.customText = "Transfer £50 to my account"
        assertNotNull(model.safetyIssue)
        model.customText = "Cook dinner on Friday"
        assertNull(model.safetyIssue)
    }
}

class SettlementTallyTests {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())

    @Test fun settledCasesAreNeitherWinsNorLosses() {
        val base = PreviewData.store().winTally
        val store = PreviewData.settledStore()
        assertEquals(base, store.winTally)
        assertNull(store.outcome(PreviewData.settledCase))
        assertEquals("Settled out of court", store.outcomeLine(PreviewData.settledCase))
        assertEquals(listOf(PreviewData.settledCase.id), store.outstandingSettlementCases.map { it.id })
        assertEquals(CaseAction.markSettlementFulfilled, store.nextAction(PreviewData.settledCase))
    }

    @Test fun fulfilmentLabels() {
        val now = Instant.ofEpochSecond(1_800_000_000)
        var s = PreviewData.settledSettlement.copy(dueAt = now.plusSeconds(3 * 86_400))
        assertEquals(SettlementFulfilment.due(3), SettlementFulfilment.of(s, now, ZoneOffset.UTC))
        assertEquals("FULFILMENT: Outstanding · Due in 3 days", SettlementFulfilment.of(s, now, ZoneOffset.UTC).label)
        s = s.copy(dueAt = now.minusSeconds(60))
        assertEquals("FULFILMENT: Overdue", SettlementFulfilment.of(s, now).label)
        s = s.copy(status = SettlementStatus.fulfilled)
        assertEquals("SETTLEMENT FULFILLED ✓", SettlementFulfilment.of(s, now).label)
        s = s.copy(status = SettlementStatus.rejected)
        assertEquals(SettlementFulfilment.none, SettlementFulfilment.of(s, now))
        for (f in listOf(SettlementFulfilment.due(2), SettlementFulfilment.overdue, SettlementFulfilment.outstanding, SettlementFulfilment.fulfilled)) {
            assertFalse(f.label.uppercase().contains("SERVED"))
        }
    }

    @Test fun markFulfilled() = runTest(main.dispatcher) {
        val store = PreviewData.settledStore()
        store.markSettlementFulfilled(PreviewData.settledCase.id)
        assertEquals(SettlementStatus.fulfilled, store.settlement(PreviewData.settledCase.id)?.status)
        assertTrue(store.outstandingSettlementCases.isEmpty())
    }
}

class DemoSettlementTests {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())
    private val caseId = PreviewData.trialCase.id

    private fun make(random: Double): Pair<CaseStore, DemoTrialSimulator> {
        val store = PreviewData.store()
        val sim = requireNotNull(store.demo)
        sim.cancelAll()
        sim.config = DemoTrialSimulator.Config(speed = DemoTrialSimulator.Speed.instant, autoplay = false, random = { random })
        return store to sim
    }

    @Test fun optionsComeFromTheCategoryPool() = runTest(main.dispatcher) {
        val (store, _) = make(0.0)
        val options = store.generateSettlementOptions(caseId)
        assertEquals(listOf(SettlementSuggestionKind.quick, SettlementSuggestionKind.fair, SettlementSuggestionKind.peace), options.map { it.kind })
        assertTrue(options.all { it.category == "food" && it.dueDays in 1..7 })
    }

    @Test fun proposePausesTheTimerAndAcceptCloses() = runTest(main.dispatcher) {
        val (store, sim) = make(0.1)   // partner accepts
        store.proposeSettlement(caseId, "Buy a fresh pizza.", SettlementSource.custom, dueDays = 2)
        var c = requireNotNull(store.caseById(caseId))
        assertTrue(c.deadlineAt == null && c.settlementId != null)
        val s = requireNotNull(store.settlement(caseId))
        assertTrue(s.status == SettlementStatus.proposed && s.initiatedBy == me && s.entryPoint == "trial" && s.pausedRemainingSeconds != null)
        assertEquals(CaseAction.awaitSettlement, store.nextAction(c))

        assertTrue(sim.settle())
        c = requireNotNull(store.caseById(caseId))
        assertTrue(c.status == CaseStatus.closedSettled && c.closedAt != null && c.settlementId == null)
        val accepted = requireNotNull(store.settlement(caseId))
        assertTrue(accepted.status == SettlementStatus.accepted && accepted.dueAt != null && accepted.acceptedOfferId != null)
        assertEquals("The parties have spared the court the trouble. Miracles do happen.", store.turns(caseId).lastOrNull()?.body)
        assertTrue(store.verdict(caseId) == null && store.judgement(caseId) == null)
        assertEquals(accepted.id, store.settlementToCelebrate?.id)
        store.markSettlementCelebrated(accepted.id)
        assertNull(store.settlementToCelebrate)
    }

    @Test fun rejectRestoresTheCourt() = runTest(main.dispatcher) {
        val (store, sim) = make(0.9)   // partner rejects
        val before = requireNotNull(store.caseById(caseId))
        store.proposeSettlement(caseId, "Buy a fresh pizza.", SettlementSource.custom, dueDays = 2)
        assertTrue(sim.settle())
        val c = requireNotNull(store.caseById(caseId))
        assertEquals(SettlementStatus.rejected, store.settlement(caseId)?.status)
        assertTrue(c.status == CaseStatus.trial && c.phase == before.phase && c.phaseTurnOwner == before.phaseTurnOwner)
        assertTrue(c.settlementId == null && c.deadlineAt != null)
        assertTrue(store.canProposeSettlement(caseId))
    }

    @Test fun partnerCountersOnceThenIRespond() = runTest(main.dispatcher) {
        val (store, sim) = make(0.6)   // partner counters (once)
        store.proposeSettlement(caseId, "Buy a fresh pizza.", SettlementSource.custom, dueDays = 2)
        assertTrue(sim.settle())
        var s = requireNotNull(store.settlement(caseId))
        assertTrue(s.status == SettlementStatus.countered && s.currentRound == 2)
        assertEquals(partner, store.latestOffer(caseId)?.proposedBy)
        assertTrue(store.pendingSettlementForMe(caseId))

        // I counter back (round 3); the partner has already countered once, so it answers accept/reject.
        store.counterSettlement(caseId, "Pizza and the dishes.", SettlementSource.custom, dueDays = 3)
        assertTrue(sim.settle())
        s = requireNotNull(store.settlement(caseId))
        assertTrue(s.currentRound == 3 && s.status == SettlementStatus.rejected)
        assertEquals(listOf(1, 2, 3), store.settlementOffers(caseId).map { it.roundNumber })
    }

    @Test fun receiverAcceptsAndWithdrawIsProposerOnly() = runTest(main.dispatcher) {
        val store = PreviewData.settlementOfferStore(round = 1)
        val sim = requireNotNull(store.demo)
        sim.cancelAll(); sim.config = DemoTrialSimulator.Config(speed = DemoTrialSimulator.Speed.instant, autoplay = false, random = { 0.0 })
        assertEquals("wrong_role", expectEdgeError { store.withdrawSettlement(caseId) }.code)
        store.respondToSettlement(caseId, accept = true)
        assertEquals(CaseStatus.closedSettled, store.caseById(caseId)?.status)
        assertEquals(SettlementStatus.accepted, store.settlement(caseId)?.status)
    }

    @Test fun withdrawResumesTheCourt() = runTest(main.dispatcher) {
        val (store, sim) = make(0.1)
        store.proposeSettlement(caseId, "Buy a fresh pizza.", SettlementSource.custom, dueDays = 2)
        sim.cancelAll()   // the partner hasn't answered yet
        store.withdrawSettlement(caseId)
        assertEquals(SettlementStatus.withdrawn, store.settlement(caseId)?.status)
        assertTrue(store.caseById(caseId)?.settlementId == null && store.caseById(caseId)?.deadlineAt != null)
    }
}

class SettlementEdgeFunctionTests {
    @get:Rule val main = MainDispatcherRule()
    private val caseId = UUID.fromString("AAAAAAAA-0000-0000-0000-000000000014")
    private val sid = UUID.fromString("BBBBBBBB-0000-0000-0000-000000000001")
    private val settlementRow = """{"id":"bbbbbbbb-0000-0000-0000-000000000001","case_id":"aaaaaaaa-0000-0000-0000-000000000014","status":"proposed","initiated_by":"11111111-1111-1111-1111-111111111111","current_round":1,"entry_point":"trial","paused_from_status":"trial","paused_phase":"plaintiff_exhibits","paused_turn_owner":"plaintiff","paused_remaining_seconds":32400,"expires_at":"2026-09-24T22:00:00+00:00","accepted_at":null,"rejected_at":null,"withdrawn_at":null,"expired_at":null,"fulfilled_at":null,"fulfilled_by":null,"due_at":null,"accepted_offer_id":null,"model_ref":null,"prompt_version":null,"created_at":"2026-09-24T10:00:00.123+00:00","updated_at":"2026-09-24T10:00:00+00:00"}"""
    private val offerRow = """{"id":"cccccccc-0000-0000-0000-000000000001","settlement_id":"bbbbbbbb-0000-0000-0000-000000000001","round_number":1,"proposed_by":"11111111-1111-1111-1111-111111111111","body":"Replace the meal.","source":"ai","category":"food","due_days":2,"created_at":"2026-09-24T10:00:00+00:00","expires_at":"2026-09-24T22:00:00+00:00"}"""

    private inline fun <reified B> json(body: B): String {
        val element = JSONCoding.json.encodeToJsonElement(kotlinx.serialization.serializer<B>(), body) as JsonObject
        return JsonObject(element.toSortedMap()).toString()
    }

    @Test fun bodies() {
        assertEquals("""{"case_id":"AAAAAAAA-0000-0000-0000-000000000014"}""", json(EdgeFunctions.SettlementCaseBody(caseId)))
        assertEquals(
            """{"body":"Replace the meal.","case_id":"AAAAAAAA-0000-0000-0000-000000000014","due_days":2,"source":"ai","suggestion_id":"food_quick"}""",
            json(EdgeFunctions.ProposeSettlementBody(caseId, "Replace the meal.", SettlementSource.ai, "food_quick", 2)),
        )
        assertEquals(
            """{"body":"Ours","case_id":"AAAAAAAA-0000-0000-0000-000000000014","source":"custom"}""",
            json(EdgeFunctions.ProposeSettlementBody(caseId, "Ours", SettlementSource.custom, null, null)),
        )
        assertEquals(
            """{"body":"Ours","due_days":4,"settlement_id":"BBBBBBBB-0000-0000-0000-000000000001","source":"custom"}""",
            json(EdgeFunctions.CounterSettlementBody(sid, "Ours", SettlementSource.custom, 4)),
        )
        assertEquals(
            """{"action":"reject","settlement_id":"BBBBBBBB-0000-0000-0000-000000000001"}""",
            json(EdgeFunctions.RespondSettlementBody(sid, EdgeFunctions.SettlementResponse.reject)),
        )
        assertEquals("""{"settlement_id":"BBBBBBBB-0000-0000-0000-000000000001"}""", json(EdgeFunctions.SettlementIdBody(sid)))
    }

    @Test fun callsDecodeAndMapErrors() = runTest(main.dispatcher) {
        StubSupabase.install {
            200 to """{"ok":true,"options":[{"id":"q","kind":"quick","body":"Replace the meal.","category":"food","due_days":2},{"id":"f","kind":"fair","body":"Meal and dishes.","category":"food","due_days":3,"generic":true}]}"""
        }
        val edge = EdgeFunctions(StubSupabase.client())
        val opts = edge.generateSettlementOptions(caseId)
        assertTrue(opts.options.map { it.kind } == listOf(SettlementSuggestionKind.quick, SettlementSuggestionKind.fair) && opts.options[1].generic == true && opts.options[0].dueDays == 2)
        assertNull(opts.summary)
        StubSupabase.install { 200 to """{"ok":true,"options":[],"summary":"A disagreement about a ruined dinner."}""" }
        assertEquals("A disagreement about a ruined dinner.", edge.generateSettlementOptions(caseId).summary)

        StubSupabase.install { 200 to """{"ok":true,"settlement":$settlementRow,"offer":$offerRow}""" }
        val r = edge.proposeSettlement(caseId, "Replace the meal.", SettlementSource.ai, "q", 2)
        assertTrue(r.settlement?.status == SettlementStatus.proposed && r.settlement?.pausedPhase == TrialPhase.plaintiffExhibits && r.settlement?.pausedRemainingSeconds == 32400)
        assertTrue(r.offer?.roundNumber == 1 && r.offer?.source == SettlementSource.ai && r.offer?.dueDays == 2)
        edge.counterSettlement(sid, "B", SettlementSource.custom, 3)
        edge.respondToSettlement(sid, accept = true)
        edge.withdrawSettlement(sid)
        edge.markSettlementFulfilled(sid)
        assertEquals(
            listOf(
                "/functions/v1/propose_settlement", "/functions/v1/counter_settlement", "/functions/v1/respond_to_settlement",
                "/functions/v1/withdraw_settlement", "/functions/v1/mark_settlement_fulfilled",
            ),
            StubSupabase.requests.map { it.path },
        )
        assertEquals("accept", StubSupabase.requests[2].json?.get("action")?.jsonPrimitive?.content)
        assertEquals("q", StubSupabase.requests[0].json?.get("suggestion_id")?.jsonPrimitive?.content)

        StubSupabase.install { 409 to """{"ok":false,"code":"limit_rounds","message":"Round cap."}""" }
        val limit = expectEdgeError { edge.counterSettlement(sid, "C", SettlementSource.custom, null) }
        assertTrue(limit.isSettlementRoundLimit)
        assertEquals(EdgeErrors.settlementRoundLimitMessage, EdgeErrors.settlementMessage(limit))
        assertEquals("It's your partner's move on this offer.", EdgeErrors.settlementMessage(EdgeError(code = "wrong_role", message = "")))

        StubSupabase.install { 422 to """{"ok":false,"code":"unsafe_terms","message":"Denied."}""" }
        val unsafe = expectEdgeError { edge.proposeSettlement(caseId, "x", SettlementSource.custom, null, 7) }
        assertTrue(unsafe.isUnsafeTerms)
        assertEquals("The court can't accept those terms. Keep it small, kind and doable.", EdgeErrors.settlementMessage(unsafe))
    }
}

/** Android-only: the relative countdown text and category titles. */
class SettlementCopyTests {
    @Test fun relativeAndCategory() {
        val now = Instant.ofEpochSecond(0)
        assertEquals("11 hr, 59 min", SettlementCopy.relative(now, now.plusSeconds(11 * 3600 + 59 * 60)))
        assertEquals("4 min, 12 sec", SettlementCopy.relative(now, now.plusSeconds(252)))
        assertEquals("1 day, 2 hr", SettlementCopy.relative(now, now.plusSeconds(86_400 + 7200)))
        assertEquals("Food", SettlementCopy.categoryTitle("food"))
        assertEquals("Quality time", SettlementCopy.categoryTitle("quality_time"))
    }
}
