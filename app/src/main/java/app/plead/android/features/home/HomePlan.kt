// Port of ArgueWin/Features/Home/HomePlan.swift (+ the `extension HomePlan` "Store → plan" and `ActiveCaseCard`
// from HomeView.swift, kept together so HomeTests can pin the rules on the JVM).
//
// Home's decision logic (CONTRACTS-v2 amendment af): which item leads, what the urgent label says,
// and the greeting. Pure functions over case data so HomeTests can pin the rules.
package app.plead.android.features.home

import app.plead.android.features.cases.SettlementDocket
import app.plead.android.models.Case
import app.plead.android.services.CaseAction
import app.plead.android.services.CaseStore
import app.plead.android.services.caseFileStatus
import app.plead.android.services.nextAction
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * One thing Home could lead with: an open case, or a post-verdict follow-up (an outstanding
 * judgement or settlement agreement) on a closed case.
 */
data class HomeItem(
    val caseId: UUID,
    val kind: Kind,
    /** The next step as this user sees it (drives the label, the button and the route). */
    val action: CaseAction,
    /** The user must act (the step is theirs, not their partner's or the court's). */
    val needsMe: Boolean,
    /** Only a real deadline from the case / judgement / agreement; null = none. */
    val deadline: Instant?,
    val updatedAt: Instant,
) {
    enum class Kind { openCase, judgement, agreement }
}

object HomePlan {
    /** What Home leads with. */
    sealed class Lead {
        /** Something the user must do now (urgent label, burgundy action). */
        data class action(val item: HomeItem) : Lead()

        /** Nothing needs the user, but a case is in progress (calm label; the new-case panel stays below). */
        data class waiting(val item: HomeItem) : Lead()

        /** Nothing urgent or active: "Bring a new case" leads. */
        data object newCase : Lead()
    }

    /**
     * Ranking, in order:
     * 1. items that need me before items that don't (a waiting case never displaces an action);
     * 2. among actions, an open case's court step before a post-verdict follow-up: court steps carry the
     *    court's own consequences (default judgment, a forfeited turn), follow-ups stay listed below;
     * 3. nearest real deadline first (overdue counts as nearest), no deadline last;
     * 4. most recently updated.
     */
    fun ranked(items: List<HomeItem>): List<HomeItem> = items.sortedWith { a, b ->
        if (a.needsMe != b.needsMe) return@sortedWith if (a.needsMe) -1 else 1
        val ta = if (a.kind == HomeItem.Kind.openCase) 0 else 1
        val tb = if (b.kind == HomeItem.Kind.openCase) 0 else 1
        if (ta != tb) return@sortedWith ta.compareTo(tb)
        val da = a.deadline ?: Instant.MAX
        val db = b.deadline ?: Instant.MAX
        if (da != db) return@sortedWith da.compareTo(db)
        b.updatedAt.compareTo(a.updatedAt)
    }

    /** The lead: the top-ranked action; else the top-ranked open case in progress; else the new-case panel. */
    fun lead(items: List<HomeItem>): Lead {
        val r = ranked(items)
        val first = r.firstOrNull()
        if (first != null && first.needsMe) return Lead.action(first)
        val open = r.firstOrNull { it.kind == HomeItem.Kind.openCase }
        if (open != null) return Lead.waiting(open)
        return Lead.newCase
    }

    /**
     * The docket preview: the next open case after the lead (the next action if there is one, else a
     * waiting case). null when there is no other open case.
     */
    fun preview(items: List<HomeItem>, lead: Lead): HomeItem? {
        val leadId: UUID? = when (lead) {
            is Lead.action -> lead.item.takeIf { it.kind == HomeItem.Kind.openCase }?.caseId
            is Lead.waiting -> lead.item.takeIf { it.kind == HomeItem.Kind.openCase }?.caseId
            Lead.newCase -> null
        }
        return ranked(items).firstOrNull { it.kind == HomeItem.Kind.openCase && it.caseId != leadId }
    }

    /** Identity of the lead for motion (the fade/rise runs when it changes). */
    fun key(lead: Lead): String = when (lead) {
        is Lead.action -> "action-${lead.item.caseId.toString().uppercase()}-${lead.item.kind}-${lead.item.action}"
        is Lead.waiting -> "waiting-${lead.item.caseId.toString().uppercase()}-${lead.item.action}"
        Lead.newCase -> "newCase"
    }

    // MARK: Copy

    /**
     * The burgundy caps label above the primary file ("YOUR DEFENCE IS DUE"). Written in title case;
     * the caller upper-cases it (`.pleadLabelCaps()`). null for steps that aren't the user's.
     */
    fun urgentLabel(action: CaseAction, overdue: Boolean = false): String? = when (action) {
        CaseAction.fileDefence -> "Your defence is due"
        CaseAction.yourTurnInCourt -> "Your turn in court"
        CaseAction.respondToTime -> "Agree the trial time"
        CaseAction.chooseJudgement, CaseAction.acceptJudgement -> "Answer the judge"
        CaseAction.enterPlea -> "Your plea is due"
        CaseAction.requestDefault -> "The plea deadline has passed"
        CaseAction.hearVerdict -> "The verdict is in"
        CaseAction.respondToSettlement -> "A settlement offer awaits you"
        CaseAction.markJudgementServed -> if (overdue) "Judgement overdue" else "Judgement due"
        CaseAction.markSettlementFulfilled -> if (overdue) "Agreement overdue" else "Agreement due"
        else -> null
    }

    /** The calm (walnut) label above a case that is in progress but not waiting on the user. */
    fun waitingLabel(action: CaseAction, partner: String): String = when (action) {
        CaseAction.awaitPlea -> "Waiting for $partner's plea"
        CaseAction.awaitDefence -> "Waiting for $partner's defence"
        CaseAction.awaitTime -> "Waiting for $partner to agree a time"
        CaseAction.watchCourt -> "$partner's turn in court"
        CaseAction.awaitVerdict -> "The judge is deliberating"
        CaseAction.awaitSettlement -> "Waiting for $partner to answer your offer"
        CaseAction.awaitJudgementChoice -> "The judgement is being chosen"
        else -> "In progress"
    }

    /** One plain line under the label ("Your turn to tell the court what happened."). */
    fun supportingLine(action: CaseAction, partner: String): String? = when (action) {
        CaseAction.fileDefence -> "Your turn to tell the court what happened."
        CaseAction.yourTurnInCourt -> "The court is waiting to hear from you."
        CaseAction.respondToTime -> "$partner has proposed a trial time. The court needs your answer."
        CaseAction.enterPlea -> "$partner has summoned you. Tell the court how you plead."
        CaseAction.requestDefault -> "$partner didn't enter a plea in time. You can ask for a default judgment."
        CaseAction.hearVerdict -> "The judge is ready to read the ruling."
        CaseAction.chooseJudgement -> "You prevailed. The court awaits your choice of judgement."
        CaseAction.acceptJudgement -> "The court has delivered its judgement. Give the court your answer."
        CaseAction.respondToSettlement -> "$partner has offered to settle out of court."
        CaseAction.markJudgementServed -> "Mark it served once it's done."
        CaseAction.markSettlementFulfilled -> "Mark it fulfilled once it's done."
        CaseAction.awaitPlea -> "$partner has been summoned. Nothing to do until they plead."
        CaseAction.awaitDefence -> "$partner is preparing their defence."
        CaseAction.awaitTime -> "Your proposed trial time is with $partner."
        CaseAction.watchCourt -> "Follow the trial from the gallery."
        CaseAction.awaitVerdict -> "The court is considering its ruling."
        CaseAction.awaitSettlement -> "The court waits while your offer is considered."
        else -> null
    }

    /** "Good morning" 05:00–11:59, "Good afternoon" 12:00–17:59, "Good evening" 18:00–04:59. */
    fun salutation(hour: Int): String = when (hour) {
        in 5 until 12 -> "Good morning"
        in 12 until 18 -> "Good afternoon"
        else -> "Good evening"
    }

    /** "Good evening, Sam" (first name only); just the salutation when no name is known. */
    fun greeting(name: String?, at: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val hello = salutation(at.atZone(zone).hour)
        val first = name?.trim()?.split(Regex("\\s+"))?.firstOrNull { it.isNotEmpty() }
        if (first.isNullOrEmpty()) return hello
        return "$hello, $first"
    }

    // MARK: Store → plan

    /** The primary file's button: a settlement step (the response sheet / room) or the court flow. */
    data class CardAction(val label: String, val route: Route) {
        sealed class Route {
            data class settlement(val route: SettlementDocket.RowRoute) : Route()
            data class court(val action: CaseAction) : Route()
        }
    }

    /**
     * The real next step for an open case's card, or null when the only thing to do is read the file
     * (waiting on a plea / defence: the card itself opens the record).
     */
    fun cardAction(kase: Case, store: CaseStore): CardAction? {
        val route = SettlementDocket.route(kase, store.settlement(kase.id), pendingForMe = store.pendingSettlementForMe(kase.id))
        if (route != SettlementDocket.RowRoute.record) {
            return CardAction(
                SettlementDocket.activeCardCopy(pendingForMe = route == SettlementDocket.RowRoute.settlementResponse).button,
                CardAction.Route.settlement(route),
            )
        }
        return when (val action = courtAction(kase, store)) {
            CaseAction.awaitPlea, CaseAction.awaitDefence, CaseAction.viewRecord, CaseAction.awaitJudgementChoice -> null
            else -> CardAction(action.title, CardAction.Route.court(action))
        }
    }

    /**
     * The open case's own step (a pending settlement shows as respond / await; judgement follow-ups
     * stay on their own card, `ActiveCaseCard.cardAction`).
     */
    fun courtAction(kase: Case, store: CaseStore): CaseAction {
        val full = store.nextAction(kase)
        if (full == CaseAction.respondToSettlement || full == CaseAction.awaitSettlement) return full
        return ActiveCaseCard.cardAction(full, base = store.me?.let { kase.nextAction(it.id) } ?: CaseAction.viewRecord)
    }

    /** Every candidate for the lead: open cases, outstanding judgements, outstanding agreements. */
    fun items(store: CaseStore, now: Instant = Instant.now()): List<HomeItem> {
        if (store.me == null || store.isSolo) return emptyList()
        val items = store.openCases.map { kase ->
            // The shared case-file status is the truth for "needs me" and the deadline (never invented).
            val status = store.caseFileStatus(kase, now)
            HomeItem(kase.id, HomeItem.Kind.openCase, courtAction(kase, store), status.needsMe, status.deadline, kase.updatedAt)
        }.toMutableList()
        val mine = setOf(CaseAction.chooseJudgement, CaseAction.acceptJudgement, CaseAction.markJudgementServed)
        for (kase in store.outstandingJudgementCases) {
            val action = store.nextAction(kase)
            items.add(HomeItem(kase.id, HomeItem.Kind.judgement, action, mine.contains(action), store.judgement(kase.id)?.dueAt, kase.updatedAt))
        }
        for (kase in store.outstandingSettlementCases) {
            items.add(
                HomeItem(kase.id, HomeItem.Kind.agreement, CaseAction.markSettlementFulfilled, true, store.settlement(kase.id)?.dueAt, kase.updatedAt),
            )
        }
        return items
    }
}

/** Kept for the card-action rule the docket tests pin (the active card's own step). */
object ActiveCaseCard {
    /**
     * The case file keeps the case's own step: "choose the judgement" stays here (the verdict is
     * still in court), but waiting on a choice, accepting and marking served belong to the separate
     * outstanding-judgement card.
     */
    fun cardAction(action: CaseAction, base: CaseAction): CaseAction = when (action) {
        CaseAction.awaitJudgementChoice, CaseAction.acceptJudgement, CaseAction.markJudgementServed -> base
        else -> action
    }
}
