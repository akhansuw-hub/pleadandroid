// Port of ArgueWinTests/HomeTests.swift.
//
// CONTRACTS-v2 amendment af: Home is action-led. The lead is the earliest action the user must take
// (needs me, then court step before follow-up, then nearest real deadline); a waiting case never
// displaces an action; nothing urgent or active → "Bring a new case" leads.
package app.plead.android.features.home

import app.plead.android.services.CaseAction
import app.plead.android.services.MainDispatcherRule
import app.plead.android.services.PreviewData
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

private val now: Instant = Instant.ofEpochSecond(1_790_000_000)
private const val hour: Long = 3600

private fun item(
    n: Int,
    kind: HomeItem.Kind = HomeItem.Kind.openCase,
    action: CaseAction,
    needsMe: Boolean,
    due: Long? = null,
    updated: Long = 0,
): HomeItem = HomeItem(PreviewData.id(n), kind, action, needsMe, due?.let { now.plusSeconds(it) }, now.plusSeconds(updated))

private fun leadId(items: List<HomeItem>): UUID? = when (val l = HomePlan.lead(items)) {
    is HomePlan.Lead.action -> l.item.caseId
    is HomePlan.Lead.waiting -> l.item.caseId
    HomePlan.Lead.newCase -> null
}

class HomePrimarySelectionTests {
    @get:Rule val main = MainDispatcherRule()

    @Test fun needsMeBeatsWaiting() {
        // The waiting case is due sooner and updated more recently, and still loses.
        val items = listOf(
            item(1, action = CaseAction.awaitDefence, needsMe = false, due = 1 * hour, updated = 0),
            item(2, action = CaseAction.fileDefence, needsMe = true, due = 30 * hour, updated = -5 * hour),
        )
        assertEquals(HomePlan.Lead.action(items[1]), HomePlan.lead(items))
    }

    @Test fun aWaitingCaseNeverDisplacesAnActionWithADeadline() {
        val items = listOf(
            item(1, action = CaseAction.awaitVerdict, needsMe = false, due = 10 * 60),
            item(2, action = CaseAction.respondToTime, needsMe = true, due = 48 * hour),
        )
        assertEquals(PreviewData.id(2), leadId(items))
    }

    @Test fun nearestDeadlineWins() {
        val items = listOf(
            item(1, action = CaseAction.fileDefence, needsMe = true, due = 30 * hour),
            item(2, action = CaseAction.yourTurnInCourt, needsMe = true, due = 9 * hour),
            item(3, action = CaseAction.enterPlea, needsMe = true, due = 50 * hour),
        )
        assertEquals(PreviewData.id(2), leadId(items))
    }

    @Test fun noDeadlineRanksAfterADeadline() {
        val items = listOf(
            item(1, action = CaseAction.hearVerdict, needsMe = true, due = null, updated = 0),
            item(2, action = CaseAction.fileDefence, needsMe = true, due = 70 * hour, updated = -9 * hour),
        )
        assertEquals(PreviewData.id(2), leadId(items))
    }

    @Test fun tiesBreakOnRecency() {
        val items = listOf(
            item(1, action = CaseAction.hearVerdict, needsMe = true, updated = -2 * hour),
            item(2, action = CaseAction.chooseJudgement, needsMe = true, updated = -1 * hour),
        )
        assertEquals(PreviewData.id(2), leadId(items))
    }

    @Test fun judgementDueCanLead() {
        val items = listOf(
            item(1, action = CaseAction.awaitDefence, needsMe = false, due = 2 * hour),
            item(2, HomeItem.Kind.judgement, action = CaseAction.acceptJudgement, needsMe = true, due = 72 * hour),
        )
        assertEquals(HomePlan.Lead.action(items[1]), HomePlan.lead(items))
    }

    @Test fun agreementDueCanLead() {
        val items = listOf(item(3, HomeItem.Kind.agreement, action = CaseAction.markSettlementFulfilled, needsMe = true, due = 24 * hour))
        assertEquals(HomePlan.Lead.action(items[0]), HomePlan.lead(items))
    }

    @Test fun aCourtStepComesBeforeAFollowUp() {
        // An overdue judgement is listed below; the live court step with its own clock leads.
        val items = listOf(
            item(1, HomeItem.Kind.judgement, action = CaseAction.markJudgementServed, needsMe = true, due = -48 * hour),
            item(2, action = CaseAction.fileDefence, needsMe = true, due = 30 * hour),
        )
        assertEquals(PreviewData.id(2), leadId(items))
    }

    @Test fun followUpsCompeteOnDeadline() {
        val items = listOf(
            item(1, HomeItem.Kind.agreement, action = CaseAction.markSettlementFulfilled, needsMe = true, due = 5 * 24 * hour),
            item(2, HomeItem.Kind.judgement, action = CaseAction.markJudgementServed, needsMe = true, due = -2 * 24 * hour),
        )
        assertEquals(PreviewData.id(2), leadId(items))
    }

    @Test fun nothingNeedsMeButACaseIsActiveLeadsWithTheCase() {
        val items = listOf(
            item(1, HomeItem.Kind.judgement, action = CaseAction.awaitJudgementChoice, needsMe = false),
            item(2, action = CaseAction.awaitVerdict, needsMe = false, due = 40 * 60),
        )
        assertEquals(HomePlan.Lead.waiting(items[1]), HomePlan.lead(items))
    }

    @Test fun noneLeadsWithANewCase() {
        assertEquals(HomePlan.Lead.newCase, HomePlan.lead(emptyList()))
        // A follow-up that isn't mine (my partner's to do) doesn't lead either.
        assertEquals(
            HomePlan.Lead.newCase,
            HomePlan.lead(listOf(item(1, HomeItem.Kind.judgement, action = CaseAction.awaitJudgementChoice, needsMe = false))),
        )
    }

    @Test fun previewIsTheNextOpenCase() {
        val items = listOf(
            item(1, action = CaseAction.fileDefence, needsMe = true, due = 9 * hour),
            item(2, HomeItem.Kind.judgement, action = CaseAction.markJudgementServed, needsMe = true, due = -hour),
            item(3, action = CaseAction.awaitVerdict, needsMe = false, due = hour),
        )
        val lead = HomePlan.lead(items)
        assertEquals(PreviewData.id(3), HomePlan.preview(items, lead)?.caseId)
        assertNull(HomePlan.preview(listOf(items[0]), HomePlan.lead(listOf(items[0]))))
        // New-case lead: nothing to preview when no case is open.
        assertNull(HomePlan.preview(emptyList(), HomePlan.Lead.newCase))
    }

    @Test fun leadKeyChangesWhenThePrimaryChanges() {
        val a = item(1, action = CaseAction.fileDefence, needsMe = true)
        val b = a.copy(action = CaseAction.respondToTime)
        assertNotEquals(HomePlan.key(HomePlan.Lead.action(a)), HomePlan.key(HomePlan.Lead.action(b)))
        assertEquals(HomePlan.key(HomePlan.Lead.action(a)), HomePlan.key(HomePlan.Lead.action(a)))
    }

    // MARK: From the demo store

    @Test fun defaultStoreLeadsWithTheNearestCourtStep() {
        val store = PreviewData.store()
        val items = HomePlan.items(store)
        val lead = (HomePlan.lead(items) as? HomePlan.Lead.action)?.item ?: return fail("no action lead")
        assertEquals(HomeItem.Kind.openCase, lead.kind)
        // The Last Slice: my turn, 9 h on the clock (the defence is due in 30 h, the plea in 50 h).
        assertEquals(PreviewData.trialCase.id, lead.caseId)
        assertEquals(CaseAction.yourTurnInCourt, lead.action)
        // Every open case and each outstanding follow-up is a candidate.
        assertEquals(store.openCases.size, items.count { it.kind == HomeItem.Kind.openCase })
        assertTrue(items.any { it.kind == HomeItem.Kind.judgement && it.caseId == PreviewData.guiltyCase.id && it.needsMe })
    }

    @Test fun defenceOnlyStoreLeadsWithFileYourDefence() {
        val store = PreviewData.store(cases = listOf(PreviewData.defenceCase, PreviewData.wonCase))
        val lead = (HomePlan.lead(HomePlan.items(store)) as? HomePlan.Lead.action)?.item ?: return fail("no action lead")
        assertEquals(PreviewData.defenceCase.id, lead.caseId)
        assertEquals(
            HomePlan.CardAction("File your defence", HomePlan.CardAction.Route.court(CaseAction.fileDefence)),
            HomePlan.cardAction(PreviewData.defenceCase, store),
        )
        assertEquals(PreviewData.defenceCase.deadlineAt, lead.deadline)
    }

    @Test fun emptyAndSoloStoresLeadWithTheNewCase() {
        assertEquals(HomePlan.Lead.newCase, HomePlan.lead(HomePlan.items(PreviewData.emptyStore())))
        assertTrue(HomePlan.items(PreviewData.soloStore()).isEmpty())
    }

    @Test fun awaitingStoreLeadsWithTheWaitingCase() {
        val store = PreviewData.store(cases = listOf(PreviewData.awaitingCase, PreviewData.wonCase))
        val lead = (HomePlan.lead(HomePlan.items(store)) as? HomePlan.Lead.waiting)?.item ?: return fail("expected a waiting lead")
        assertEquals(PreviewData.awaitingCase.id, lead.caseId)
        assertEquals(CaseAction.awaitVerdict, lead.action)
        // The deliberation has a real reading time; the card's action opens the court.
        assertEquals(PreviewData.awaitingCase.trialAt, lead.deadline)
        assertEquals(HomePlan.CardAction.Route.court(CaseAction.awaitVerdict), HomePlan.cardAction(PreviewData.awaitingCase, store)?.route)
    }

    @Test fun waitingOnThePartnerHasNoDirectAction() {
        val d = PreviewData.defenceCase
        val kase = d.copy(plaintiffId = d.defendantId, defendantId = d.plaintiffId) // I'm the plaintiff: waiting for Alex's defence
        val store = PreviewData.store(cases = listOf(kase))
        assertNull(HomePlan.cardAction(kase, store))
        assertNotEquals(HomePlan.Lead.newCase, HomePlan.lead(HomePlan.items(store)))
    }

    @Test fun noDeadlineIsInvented() {
        val kase = PreviewData.defenceCase.copy(deadlineAt = null)
        val store = PreviewData.store(cases = listOf(kase))
        assertNull(HomePlan.items(store).firstOrNull { it.caseId == kase.id }?.deadline)
    }
}

// MARK: - Greeting

class HomeGreetingTests {
    private val zone = ZoneId.of("Europe/London")
    private fun at(h: Int, m: Int): Instant = ZonedDateTime.of(2026, 9, 24, h, m, 0, 0, zone).toInstant()

    @Test fun boundaries() {
        listOf(
            Triple(0, 0, "Good evening"), Triple(4, 59, "Good evening"), Triple(5, 0, "Good morning"), Triple(11, 59, "Good morning"),
            Triple(12, 0, "Good afternoon"), Triple(17, 59, "Good afternoon"), Triple(18, 0, "Good evening"), Triple(23, 59, "Good evening"),
        ).forEach { (h, m, expected) -> assertEquals("$h:$m", expected, HomePlan.greeting(null, at(h, m), zone)) }
    }

    @Test fun usesTheFirstName() {
        assertEquals("Good evening, Sam", HomePlan.greeting("Sam", at(19, 0), zone))
        assertEquals("Good morning, Sam", HomePlan.greeting("  Sam Taylor ", at(9, 0), zone))
        assertEquals("Good afternoon", HomePlan.greeting("   ", at(13, 0), zone))
    }
}

// MARK: - Urgent label

class HomeUrgentLabelTests {
    @Test fun labelPerNextStep() {
        listOf(
            CaseAction.fileDefence to "Your defence is due",
            CaseAction.yourTurnInCourt to "Your turn in court",
            CaseAction.chooseJudgement to "Answer the judge",
            CaseAction.acceptJudgement to "Answer the judge",
            CaseAction.respondToTime to "Agree the trial time",
            CaseAction.enterPlea to "Your plea is due",
            CaseAction.hearVerdict to "The verdict is in",
            CaseAction.respondToSettlement to "A settlement offer awaits you",
            CaseAction.markJudgementServed to "Judgement due",
            CaseAction.markSettlementFulfilled to "Agreement due",
        ).forEach { (action, expected) -> assertEquals(expected, HomePlan.urgentLabel(action)) }
    }

    @Test fun overdueFollowUps() {
        assertEquals("Judgement overdue", HomePlan.urgentLabel(CaseAction.markJudgementServed, overdue = true))
        assertEquals("Agreement overdue", HomePlan.urgentLabel(CaseAction.markSettlementFulfilled, overdue = true))
    }

    @Test fun waitingStepsAreNeverUrgent() {
        for (action in listOf(
            CaseAction.awaitPlea, CaseAction.awaitDefence, CaseAction.awaitTime, CaseAction.watchCourt,
            CaseAction.awaitVerdict, CaseAction.awaitSettlement, CaseAction.viewRecord,
        )) {
            assertNull("$action", HomePlan.urgentLabel(action))
        }
        assertEquals("Waiting for Alex's defence", HomePlan.waitingLabel(CaseAction.awaitDefence, "Alex"))
    }

    @Test fun supportingLineNamesThePartner() {
        assertEquals("Your turn to tell the court what happened.", HomePlan.supportingLine(CaseAction.fileDefence, "Alex"))
        assertTrue(HomePlan.supportingLine(CaseAction.respondToTime, "Alex")!!.startsWith("Alex "))
    }

}
