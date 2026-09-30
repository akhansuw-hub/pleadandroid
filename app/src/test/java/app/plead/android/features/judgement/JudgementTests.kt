// Port of ArgueWinTests/JudgementTests.swift.
//
// Winner-selected court judgement (CONTRACTS-v2 amendment j): next-action matrix, the selection
// state machine, status-card actions by role, edge-function bodies, the demo simulator and routing.
package app.plead.android.features.judgement

import app.plead.android.app.AppRouter
import app.plead.android.app.AppSheet
import app.plead.android.app.AppTab
import app.plead.android.models.AICall
import app.plead.android.models.CaseStatus
import app.plead.android.models.EdgeError
import app.plead.android.models.Judgement
import app.plead.android.models.JudgementOption
import app.plead.android.models.JudgementOptionSet
import app.plead.android.models.JudgementOptionType
import app.plead.android.models.JudgementStatus
import app.plead.android.models.Role
import app.plead.android.models.Verdict
import app.plead.android.models.VerdictKind
import app.plead.android.services.CaseAction
import app.plead.android.services.CaseRoute
import app.plead.android.services.CaseScreen
import app.plead.android.services.CaseStore
import app.plead.android.services.DeepLink
import app.plead.android.services.DemoJudgementCatalog
import app.plead.android.services.DemoTrialSimulator
import app.plead.android.services.EdgeErrors
import app.plead.android.services.EdgeFunctions
import app.plead.android.services.JSONCoding
import app.plead.android.services.MainDispatcherRule
import app.plead.android.services.PreviewData
import app.plead.android.services.StubSupabase
import app.plead.android.services.isRerollLimit
import app.plead.android.services.nextAction
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

internal val me: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
internal val partner: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
internal val slice: UUID = UUID.fromString("AAAAAAAA-0000-0000-0000-000000000014")   // PreviewData.judgementCase

private fun judgement(status: JudgementStatus, chooser: UUID, caseId: UUID = slice): Judgement =
    Judgement(caseId = caseId, status = status, chooserId = chooser)

/** A `@Test` that expects an [EdgeError] (Swift `#expect(throws: EdgeError.self)`). */
internal suspend fun expectEdgeError(block: suspend () -> Unit): EdgeError {
    try {
        block()
    } catch (e: EdgeError) {
        return e
    }
    fail("expected an EdgeError")
    throw IllegalStateException()
}

// MARK: - CaseFlow next action

class JudgementNextActionTests {
    @get:Rule val main = MainDispatcherRule()
    private val kase = PreviewData.judgementCase   // status verdict, I'm the plaintiff

    @Test fun pendingSelection() {
        assertEquals(CaseAction.chooseJudgement, kase.nextAction(me, judgement(JudgementStatus.pendingSelection, me)))
        assertEquals(CaseAction.awaitJudgementChoice, kase.nextAction(partner, judgement(JudgementStatus.pendingSelection, me)))
        assertEquals("Choose the court's judgement", CaseAction.chooseJudgement.title)
        assertEquals("The prevailing party is choosing the court's judgement.", CaseAction.awaitJudgementChoice.title)
        assertTrue(CaseAction.chooseJudgement.isActionable && !CaseAction.awaitJudgementChoice.isActionable)
    }

    @Test fun deliveredAcceptedAndFinalStates() {
        assertEquals(CaseAction.acceptJudgement, kase.nextAction(partner, judgement(JudgementStatus.delivered, me)))
        assertEquals(CaseAction.markJudgementServed, kase.nextAction(me, judgement(JudgementStatus.delivered, me)))
        assertEquals(CaseAction.markJudgementServed, kase.nextAction(me, judgement(JudgementStatus.accepted, me)))
        assertEquals(CaseAction.markJudgementServed, kase.nextAction(partner, judgement(JudgementStatus.accepted, me)))
        // Served / declined fall back to the case's own step.
        assertEquals(CaseAction.hearVerdict, kase.nextAction(me, judgement(JudgementStatus.served, me)))
        assertEquals(CaseAction.hearVerdict, kase.nextAction(partner, judgement(JudgementStatus.declined, me)))
        assertEquals("Accept the judgement", CaseAction.acceptJudgement.title)
        assertEquals("Mark as served", CaseAction.markJudgementServed.title)
    }

    @Test fun closedCasesKeepTheirJudgementStep() {
        val closed = kase.copy(status = CaseStatus.closed)
        assertEquals(CaseAction.acceptJudgement, closed.nextAction(partner, judgement(JudgementStatus.delivered, me)))
        assertEquals(CaseAction.viewRecord, closed.nextAction(me, judgement(JudgementStatus.served, me)))
    }

    @Test fun ignoredBeforeRevealOrForAnotherCase() {
        val trial = kase.copy(status = CaseStatus.trial, phaseTurnOwner = Role.plaintiff)
        assertEquals(CaseAction.yourTurnInCourt, trial.nextAction(me, judgement(JudgementStatus.pendingSelection, me)))
        assertEquals(CaseAction.hearVerdict, kase.nextAction(me, judgement(JudgementStatus.pendingSelection, me, caseId = UUID.randomUUID())))
        assertEquals(CaseAction.hearVerdict, kase.nextAction(me, null))
    }

    @Test fun routerOpensTheRightPlace() {
        val router = AppRouter()
        router.open(CaseAction.chooseJudgement, kase)
        assertEquals(AppSheet.chooseJudgement(kase.id), router.sheet)
        router.sheet = null
        router.open(CaseAction.acceptJudgement, kase)          // still in court → the delivery in the courtroom
        assertTrue(router.tab == AppTab.court && router.courtCaseId == kase.id)
        val closed = kase.copy(status = CaseStatus.closed)
        router.tab = AppTab.cases
        router.open(CaseAction.acceptJudgement, closed)        // closed → the record's judgement card
        assertEquals(listOf(kase.id), router.casesPath)
        assertFalse(router.open(CaseAction.markJudgementServed, kase))   // handled inline
    }

    @Test fun storeOverlaysJudgementOnCurrentCase() {
        val store = PreviewData.judgementStore()
        assertEquals(PreviewData.judgementCase.id, store.currentCase?.id)
        assertEquals(CaseAction.chooseJudgement, store.nextAction(PreviewData.judgementCase))
        assertTrue(store.isChooser(PreviewData.judgementCase.id))
        // The pending choice on the open case lives on the active card; the outstanding cards carry the
        // accepted-but-overdue guilty-plea judgement (the served duvet case is done).
        assertEquals(listOf(PreviewData.guiltyCase.id), store.outstandingJudgementCases.map { it.id })
        val loser = PreviewData.judgementStore(iWon = false)
        assertEquals(CaseAction.awaitJudgementChoice, loser.nextAction(PreviewData.judgementCase))
        assertFalse(loser.isChooser(PreviewData.judgementCase.id))
    }
}

// MARK: - Status card actions by role

class JudgementCardActionTests {
    @Test fun actionVisibilityByRoleAndState() {
        fun actions(s: JudgementStatus, who: UUID) = JudgementCardAction.actions(judgement(s, me), who)
        assertEquals(listOf(JudgementCardAction.choose), actions(JudgementStatus.pendingSelection, me))
        assertEquals(emptyList<JudgementCardAction>(), actions(JudgementStatus.pendingSelection, partner))
        assertEquals(listOf(JudgementCardAction.accept, JudgementCardAction.markServed, JudgementCardAction.decline), actions(JudgementStatus.delivered, partner))
        assertEquals(listOf(JudgementCardAction.markServed), actions(JudgementStatus.delivered, me))
        assertEquals(listOf(JudgementCardAction.markServed), actions(JudgementStatus.accepted, me))
        assertEquals(listOf(JudgementCardAction.markServed), actions(JudgementStatus.accepted, partner))
        for (s in listOf(JudgementStatus.served, JudgementStatus.declined)) {
            assertTrue(actions(s, me).isEmpty() && actions(s, partner).isEmpty())
        }
    }

    @Test fun dueTextAndVerdictSentence() {
        val zone = ZoneOffset.UTC
        val now = LocalDateTime.of(2026, 9, 24, 10, 0).toInstant(zone)   // a Thursday
        assertEquals("Today", Judgement.dueText(now.plusSeconds(3600), now, zone, java.util.Locale.UK))
        assertEquals("Tomorrow", Judgement.dueText(now.plusSeconds(86_400), now, zone, java.util.Locale.UK))
        assertEquals("Sunday", Judgement.dueText(now.plusSeconds(3 * 86_400), now, zone, java.util.Locale.UK))
        assertEquals("due in 1 day", JudgementOption(id = "x", title = "", detail = "", type = JudgementOptionType.favour, dueDays = 1).dueLine)
        assertEquals("due in 3 days", JudgementOption(id = "x", title = "", detail = "", type = JudgementOptionType.favour, dueDays = 3).dueLine)
        val fixed = Verdict(
            id = UUID.randomUUID(), caseId = UUID.randomUUID(), kind = VerdictKind.ruling, winnerId = me, isTie = false, recap = "",
            findings = emptyList(), sentence = Verdict.judgementPendingSentence, closingLine = "",
        )
        assertFalse(fixed.hasLegacySentence)
        // Kept in sync by hand with the demo simulator's copy of the line.
        assertEquals(DemoTrialSimulator.judgementPendingSentence, Verdict.judgementPendingSentence)
    }
}

// MARK: - Selection state machine (screen B)

class JudgementSelectionModelTests {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())
    private val caseId = PreviewData.judgementCase.id

    private fun make(withOptions: Boolean = true, iWon: Boolean = true): Pair<CaseStore, JudgementSelectionModel> {
        val store = PreviewData.judgementStore(iWon = iWon, withOptions = withOptions)
        val sim = requireNotNull(store.demo)
        sim.cancelAll()
        // Normal speed: the simulated partner's later accept (4 s+) never races the assertions.
        sim.config = DemoTrialSimulator.Config(speed = DemoTrialSimulator.Speed.normal, autoplay = false, random = { 0.0 })
        return store to JudgementSelectionModel(caseId, store, pollInterval = 10.milliseconds, pollTimeout = 60.milliseconds)
    }

    @Test fun selectionGatesDelivery() {
        val (_, model) = make()
        assertEquals(JudgementSelectionModel.Phase.ready, model.phase)
        assertEquals(4, model.options.size)
        assertEquals("CHOOSE THE COURT'S JUDGEMENT", model.title)
        assertFalse(model.canDeliver)
        model.select("not-an-option")
        assertNull(model.selectedId)
        model.select("dinner_out")
        assertTrue(model.canDeliver)
        model.select("replace_slice")   // single selection
        assertEquals("replace_slice", model.selectedOption?.id)
    }

    @Test fun rerollClearsSelectionAndCapsAtTwo() = runTest(main.dispatcher) {
        val (store, model) = make()
        assertEquals("SUGGEST ANOTHER (2 left)", model.rerollTitle)
        model.select("dinner_out")
        model.reroll()
        assertNull(model.selectedId)
        assertEquals(1, store.judgementOptions(caseId)?.round)
        assertEquals("SUGGEST ANOTHER (1 left)", model.rerollTitle)
        assertTrue(model.options.map { it.id }.toSet().intersect(PreviewData.foodOptions(winner = "Sam").map { it.id }.toSet()).isEmpty())
        model.reroll()
        assertEquals(0, model.rerollsLeft)
        assertFalse(model.canReroll)
        model.reroll()   // no-op at the cap
        assertEquals(2, store.judgement(caseId)?.rerolls)
        assertNull(model.error)
        // The server's answer past the cap maps to friendly copy.
        expectEdgeError { store.rerollJudgement(caseId) }
        assertEquals(EdgeErrors.rerollLimitMessage, EdgeErrors.judgementMessage(EdgeError(code = "limit_rerolls", message = "x")))
    }

    @Test fun deliverPersistsAndLocksTheChoice() = runTest(main.dispatcher) {
        val (store, model) = make()
        assertFalse(model.deliver())   // nothing selected
        model.select("dinner_out")
        assertTrue(model.deliver())
        val j = requireNotNull(store.judgement(caseId))
        assertTrue(j.status == JudgementStatus.delivered && j.selected?.id == "dinner_out" && j.selectedBy == me)
        assertTrue(j.dueAt?.let { abs(Duration.between(Instant.now(), it).seconds - 7 * 86_400) < 60 } == true)
        val turn = requireNotNull(store.turns(caseId).lastOrNull())
        assertTrue(turn.aiCall == AICall.judgementDelivery && turn.id == j.deliveredTurnId)
        assertEquals(
            "The court finds for Sam. The prevailing party has selected their judgement. Alex is hereby ordered to plan and book dinner out for the two of you within 7 days. The court considers this matter settled.",
            turn.body,
        )
        assertTrue(model.isClosedForSelection && !model.canDeliver && !model.canReroll)
        requireNotNull(store.demo).cancelAll()
    }

    @Test fun preparingPollsThenTimesOut() = runTest(main.dispatcher) {
        val (store, model) = make(withOptions = false)
        assertEquals(JudgementSelectionModel.Phase.preparing, model.phase)
        model.start()
        assertEquals(JudgementSelectionModel.Phase.unavailable, model.phase)
        // Options land (e.g. the background generator finished): a retry shows them.
        val set = JudgementOptionSet(
            id = UUID.randomUUID(), caseId = caseId, round = 0, options = PreviewData.foodOptions(winner = "Sam"),
            generatedFor = me, createdAt = Instant.now(),
        )
        store.demoSetJudgementOptions(set)
        model.start()
        assertTrue(model.phase == JudgementSelectionModel.Phase.ready && model.options.size == 4)
    }

    @Test fun loserCannotChoose() {
        val (_, model) = make(iWon = false)
        assertFalse(model.isChooser)
        model.select("dinner_out")
        assertFalse(model.canDeliver)
    }
}

// MARK: - Demo simulator

class DemoJudgementTests {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())

    @Test fun revealOpensAJudgementForTheWinnerWithThemedOptions() {
        val store = PreviewData.store(cases = listOf(PreviewData.judgementCase))
        val sim = requireNotNull(store.demo)
        sim.cancelAll()
        sim.config = DemoTrialSimulator.Config(speed = DemoTrialSimulator.Speed.instant, random = { 0.0 })
        val v = PreviewData.judgementVerdict(iWon = true)
        store.demoAddVerdict(v, emptyList())
        sim.openJudgement(v.caseId, v)
        val j = requireNotNull(store.judgement(v.caseId))
        assertTrue(j.chooserId == me && j.status == JudgementStatus.pendingSelection && j.theme == "food")
        val set = requireNotNull(store.judgementOptions(v.caseId))
        assertTrue(set.options.size == 4 && set.round == 0)
        assertTrue(set.options.any { it.type == JudgementOptionType.directRemedy })
        assertTrue(set.options.all { it.dueDays <= 7 })
    }

    /** Amendment l: a tie generates 2–3 compromises and the court delivers one at once; nobody chooses. */
    @Test fun tieIsChosenAndDeliveredByTheCourt() = runTest(main.dispatcher) {
        val kase = PreviewData.judgementCase.copy(plaintiffId = partner, defendantId = me)
        val store = PreviewData.store(cases = listOf(kase))
        val sim = requireNotNull(store.demo)
        sim.cancelAll()
        sim.config = DemoTrialSimulator.Config(speed = DemoTrialSimulator.Speed.instant, random = { 0.0 })
        val v = Verdict(
            id = UUID.randomUUID(), caseId = kase.id, kind = VerdictKind.ruling, winnerId = null, isTie = true, recap = "",
            findings = emptyList(), sentence = Verdict.judgementPendingSentence, closingLine = "",
        )
        store.demoAddVerdict(v, emptyList())
        sim.openJudgement(kase.id, v)
        val j = requireNotNull(store.judgement(kase.id))
        assertTrue(j.chooserId == null && j.selectedBy == null && j.isCourtChosen)
        assertTrue(j.status == JudgementStatus.delivered && j.selected?.type == JudgementOptionType.compromise && j.dueAt != null)
        val pool = DemoJudgementCatalog.options(DemoJudgementCatalog.Theme.food, tie = true, round = 0, winner = "", loser = "")
        assertTrue(pool.size in 2..3 && pool.any { it.id == j.selected?.id })
        // Nobody was handed options, and nobody is the chooser.
        assertTrue(store.judgementOptions(kase.id) == null && !store.isChooser(kase.id))
        val turn = requireNotNull(store.turns(kase.id).firstOrNull { it.id == j.deliveredTurnId })
        assertTrue(turn.body.startsWith("The court could not separate you. It has chosen a resolution for you both. Both parties are hereby ordered to "))
        // Either partner may serve it; nobody "accepts" it; the simulated partner never acts on it.
        assertEquals(CaseAction.markJudgementServed, store.nextAction(kase))
        expectEdgeError { store.respondJudgement(kase.id, accept = true) }
        assertTrue(sim.settle())
        assertEquals(JudgementStatus.delivered, store.judgement(kase.id)?.status)
        store.markServed(kase.id)
        assertEquals(JudgementStatus.served, store.judgement(kase.id)?.status)
    }

    @Test fun tieResolutionCanBeDeclinedByEitherPartner() = runTest(main.dispatcher) {
        val store = PreviewData.store()
        store.demo?.cancelAll()
        val id = PreviewData.dinnerTieCase.id
        assertEquals(true, store.judgement(id)?.isCourtChosen)
        store.respondJudgement(id, accept = false)
        assertEquals(JudgementStatus.declined, store.judgement(id)?.status)
    }

    @Test fun respondAndServe() = runTest(main.dispatcher) {
        val store = PreviewData.judgementStore(iWon = false, status = JudgementStatus.delivered)
        store.demo?.cancelAll()
        val id = PreviewData.judgementCase.id
        store.respondJudgement(id, accept = true)
        assertEquals(JudgementStatus.accepted, store.judgement(id)?.status)
        store.markServed(id)
        val j = requireNotNull(store.judgement(id))
        assertTrue(j.status == JudgementStatus.served && j.servedBy == me && j.servedAt != null)
        expectEdgeError { store.markServed(id) }
    }

    @Test fun themes() {
        assertEquals(DemoJudgementCatalog.Theme.sleep, DemoJudgementCatalog.theme("The Great Duvet Heist stole the duvet at 3am"))
        assertEquals(DemoJudgementCatalog.Theme.food, DemoJudgementCatalog.theme("The Last Slice of pizza"))
        assertEquals(DemoJudgementCatalog.Theme.chores, DemoJudgementCatalog.theme("Bowls on the top rack of the dishwasher"))
        assertEquals(DemoJudgementCatalog.Theme.general, DemoJudgementCatalog.theme("Something vague"))
        val generic = DemoJudgementCatalog.options(DemoJudgementCatalog.Theme.general, tie = false, round = 0, winner = "Sam", loser = "Alex")
        assertTrue(generic.size == 4 && generic.all { it.generic == true })
    }
}

// MARK: - Edge functions + decoding + deep links

class JudgementEdgeFunctionTests {
    @get:Rule val main = MainDispatcherRule()
    private val caseId = UUID.fromString("AAAAAAAA-0000-0000-0000-000000000014")
    private val row = """{"case_id":"aaaaaaaa-0000-0000-0000-000000000014","verdict_id":null,"theme":"food","status":"delivered","chooser_id":"11111111-1111-1111-1111-111111111111","selected":{"id":"dinner_out","title":"Take Sam out","detail":"Plan and book dinner.","type":"effort","due_days":7},"selected_by":"11111111-1111-1111-1111-111111111111","selected_at":"2026-09-24T10:00:00.123+00:00","delivered_turn_id":null,"accepted_at":null,"declined_at":null,"served_at":null,"served_by":null,"due_at":"2026-10-01T10:00:00+00:00","rerolls":1,"model_ref":"x","prompt_version":"j1","created_at":"2026-09-24T09:50:00+00:00","updated_at":"2026-09-24T10:00:00+00:00"}"""

    /** Swift `JSONEncoder.supabase` with `.sortedKeys`. */
    private inline fun <reified B> json(body: B): String {
        val element = JSONCoding.json.encodeToJsonElement(kotlinx.serialization.serializer<B>(), body) as JsonObject
        return JsonObject(element.toSortedMap()).toString()
    }

    @Test fun bodies() {
        assertEquals("""{"case_id":"AAAAAAAA-0000-0000-0000-000000000014"}""", json(EdgeFunctions.JudgementCaseBody(caseId)))
        assertEquals(
            """{"case_id":"AAAAAAAA-0000-0000-0000-000000000014","option_id":"dinner_out"}""",
            json(EdgeFunctions.SelectJudgementBody(caseId, "dinner_out")),
        )
        assertEquals(
            """{"accept":false,"case_id":"AAAAAAAA-0000-0000-0000-000000000014"}""",
            json(EdgeFunctions.RespondJudgementBody(caseId, accept = false)),
        )
    }

    @Test fun selectRespondServeDecodeTheJudgement() = runTest(main.dispatcher) {
        StubSupabase.install { 200 to """{"ok":true,"judgement":$row}""" }
        val edge = EdgeFunctions(StubSupabase.client())
        val j = edge.selectJudgement(caseId, "dinner_out")
        assertTrue(j.status == JudgementStatus.delivered && j.selected?.dueDays == 7 && j.selected?.type == JudgementOptionType.effort && j.rerolls == 1)
        edge.respondJudgement(caseId, accept = true)
        edge.markServed(caseId)
        val paths = StubSupabase.requests.map { it.path }
        assertEquals(listOf("/functions/v1/select_judgement", "/functions/v1/respond_judgement", "/functions/v1/mark_served"), paths)
        assertEquals("dinner_out", StubSupabase.requests[0].json?.get("option_id")?.jsonPrimitive?.content)
        assertEquals(true, StubSupabase.requests[1].json?.get("accept")?.jsonPrimitive?.boolean)
    }

    @Test fun rerollDecodesOptionsAndMapsTheLimit() = runTest(main.dispatcher) {
        StubSupabase.install {
            200 to """{"ok":true,"options":{"id":"bbbbbbbb-0000-0000-0000-000000000001","case_id":"aaaaaaaa-0000-0000-0000-000000000014","round":1,"options":[{"id":"a","title":"A","detail":"Do a.","type":"favour","due_days":2,"generic":true}],"generated_for":"11111111-1111-1111-1111-111111111111","created_at":"2026-09-24T10:00:00+00:00"}}"""
        }
        val edge = EdgeFunctions(StubSupabase.client())
        val r = edge.rerollJudgement(caseId)
        assertTrue(r.options?.round == 1 && r.options?.options?.firstOrNull()?.generic == true)
        assertEquals("/functions/v1/reroll_judgement", StubSupabase.requests.firstOrNull()?.path)

        StubSupabase.install { 200 to """{"ok":true}""" }
        assertNull(edge.rerollJudgement(caseId).options)

        StubSupabase.install { 429 to """{"ok":false,"code":"limit_rerolls","message":"No more rerolls."}""" }
        val e = expectEdgeError { edge.rerollJudgement(caseId) }
        assertTrue(e.isRerollLimit)
        assertEquals(EdgeErrors.rerollLimitMessage, EdgeErrors.judgementMessage(e))
    }
}

class JudgementDeepLinkTests {
    @get:Rule val main = MainDispatcherRule()

    @Test fun pushJudgementLandsOnTheRecord() {
        val id = PreviewData.judgementCase.id
        val link = requireNotNull(DeepLink.parse(push = mapOf("case_id" to id.toString().uppercase(), "screen" to "judgement")))
        assertEquals(DeepLink.caseRoute(CaseRoute(id, CaseScreen.judgement)), link)
        val url = URI("plead://case/${id.toString().uppercase()}?screen=judgement")
        assertEquals(DeepLink.caseRoute(CaseRoute(id, CaseScreen.judgement)), DeepLink.parse(url))
        val router = AppRouter()
        router.sheet = AppSheet.settings
        router.route(CaseRoute(id, CaseScreen.judgement), PreviewData.judgementStore())
        assertTrue(router.tab == AppTab.cases && router.casesPath == listOf(id) && router.sheet == null)
    }

    @Test fun elapsedSeconds() {
        val t = Instant.ofEpochSecond(1_000)
        assertEquals("90", CaseStore.elapsedSeconds(t, t.plusMillis(90_400)))
        assertNull(CaseStore.elapsedSeconds(null, t))
        assertNull(CaseStore.elapsedSeconds(t.plusSeconds(5), t))
    }
}
