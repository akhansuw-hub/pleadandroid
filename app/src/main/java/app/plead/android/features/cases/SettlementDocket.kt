// Port of ArgueWin/Features/Cases/SettlementDocket.swift.
//
// Settle Outside Court on the docket, Home and the record (CONTRACTS-v2 amendment n, brief §7).
//
//   Closed, settled:   ribbon SETTLED (walnut, no crown) · stamp SETTLED OUT OF COURT (gold, like CLOSED)
//                      · the settlement's status row underneath (Agreement / FULFILMENT …)
//   Open, pending:     parchment chip "Settlement offer pending · Round 2 of 3"; the row opens the
//                      response sheet (an offer awaits me) or the Settlement Room (my offer is out)
//
// Never the word SERVED: that is reserved for court-ordered judgements. `SettlementDocket` is the pure
// state → label mapping (unit tested in SettlementDocketTests).

package app.plead.android.features.cases

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.plead.android.app.AppRouter
import app.plead.android.app.AppSheet
import app.plead.android.app.AppTab
import app.plead.android.app.DemoHarness
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.SFSymbol
import app.plead.android.designsystem.ribbonColor
import app.plead.android.models.Case
import app.plead.android.models.CaseStatus
import app.plead.android.models.Settlement
import app.plead.android.models.SettlementOffer
import app.plead.android.models.SettlementSource
import app.plead.android.services.CaseStore
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay
import app.plead.android.features.settlement.SettlementFulfilment

object SettlementDocket {
    /** Ribbon on a settled court file. */
    const val ribbonTitle = "Settled"

    /** Stamp on a settled court file (two lines so it fits where CLOSED sits). */
    const val stampText = "Settled\nout of court"

    /** Stamp on the record's header (one line; more room). */
    const val stampTextWide = "Settled out of court"

    /** Ribbon for any court file: SETTLED for a settled case, else the store's title. */
    fun ribbonTitle(kase: Case, storeTitle: String): String =
        if (kase.status == CaseStatus.closedSettled) ribbonTitle else storeTitle

    /** Ribbon colour: walnut for a settled case (a closed case, not a win or loss colour). */
    fun ribbonColor(kase: Case): Color = if (kase.status == CaseStatus.closedSettled) PleadColor.walnut else kase.status.ribbonColor

    /** Stamp text for a closed court file (null = no stamp: open cases and mistrials). */
    fun stamp(kase: Case, wide: Boolean = false): String? {
        if (!kase.status.isClosed || kase.status == CaseStatus.mistrial) return null
        if (kase.status == CaseStatus.closedSettled) return if (wide) stampTextWide else stampText
        return "Closed"
    }

    /** A settlement is pending on this open case (settlement row, or the case's pointer). */
    fun isPending(kase: Case, settlement: Settlement?): Boolean {
        if (!kase.status.isOpen) return false
        if (settlement != null && settlement.caseId == kase.id) return settlement.isPending
        return kase.settlementId != null
    }

    /** "Round 2 of 3" while a settlement is pending (null otherwise). */
    fun pendingRound(kase: Case, settlement: Settlement?): String? {
        if (!isPending(kase, settlement)) return null
        val round = min(max(settlement?.currentRound ?: 1, 1), Settlement.maxRounds)
        return "Round $round of ${Settlement.maxRounds}"
    }

    /** "Settlement offer pending · Round 2 of 3" (null when nothing is pending). */
    fun pendingChip(kase: Case, settlement: Settlement?): String? =
        pendingRound(kase, settlement)?.let { "Settlement offer pending · $it" }

    /** Where tapping a docket row goes. */
    enum class RowRoute {
        record,

        /** The response sheet: an offer awaits me. */
        settlementResponse,

        /** The Settlement Room (its waiting / withdraw view): my offer is out. */
        settlementRoom,
    }

    fun route(kase: Case, settlement: Settlement?, pendingForMe: Boolean): RowRoute {
        if (!isPending(kase, settlement)) return RowRoute.record
        return if (pendingForMe) RowRoute.settlementResponse else RowRoute.settlementRoom
    }

    // MARK: Home

    /** Swift's `(stateLine: String, button: String)` tuple. */
    data class ActiveCardCopy(val stateLine: String, val button: String)

    /** The active case card while a settlement is pending: (state line, button title). */
    fun activeCardCopy(pendingForMe: Boolean): ActiveCardCopy =
        ActiveCardCopy("Settlement offer pending", if (pendingForMe) "Respond to offer" else "View offer")

    // MARK: Fulfilment line (Home's outstanding-agreement card)

    /**
     * Home's compact line for the agreement's fulfilment: "DUE · 3 DAYS", "DUE · TODAY", "OVERDUE",
     * "SETTLEMENT FULFILLED ✓". Honour-based: overdue carries no penalty.
     */
    fun homeLabel(f: SettlementFulfilment): String = when (f) {
        is SettlementFulfilment.due -> when (f.days) {
            0 -> "DUE · TODAY"
            1 -> "DUE · 1 DAY"
            else -> "DUE · ${f.days} DAYS"
        }
        SettlementFulfilment.overdue -> "OVERDUE"
        SettlementFulfilment.outstanding -> "OUTSTANDING"
        SettlementFulfilment.fulfilled -> "SETTLEMENT FULFILLED ✓"
        SettlementFulfilment.none -> ""
    }

    // MARK: Record (negotiation history)

    /** "Round 2 · Sam" / "Round 1 · You". */
    fun offerHeading(offer: SettlementOffer, me: UUID?, name: String): String =
        "Round ${offer.roundNumber} · ${if (offer.proposedBy == me) "You" else name}"

    /** "Court suggestion" / "Written by you" / "Written by Sam". */
    fun sourceChip(offer: SettlementOffer, me: UUID?, name: String): String = when (offer.source) {
        SettlementSource.ai -> "Court suggestion"
        SettlementSource.custom -> if (offer.proposedBy == me) "Written by you" else "Written by $name"
    }

    /** The accepted offer (the settlement's `accepted_offer_id`), highlighted in the history. */
    fun isAccepted(offer: SettlementOffer, settlement: Settlement?): Boolean {
        if (settlement == null || !settlement.status.isAgreed) return false
        return settlement.acceptedOfferId == offer.id
    }

    // MARK: Demo harness (Swift `#if DEBUG` extension)

    /**
     * Demo harness: `AWSettlementPrompt off` closes the launch-time response sheet so the docket / Home
     * underneath can be screenshotted (the prompt is remembered as shown, so it stays closed).
     */
    suspend fun dismissLaunchPromptIfAsked(router: AppRouter) {
        if (!DemoHarness.settlementPromptOff) return
        delay(900)
        if (router.settlementSheetCaseId != null) router.sheet = null
    }

    /** Demo harness: `AWOpenCase 14` pushes that case's record on the Cases tab. */
    fun openCaseIfAsked(router: AppRouter, store: CaseStore) {
        val n = DemoHarness.openCase
        if (n <= 0) return
        val kase = store.cases.firstOrNull { it.caseNumber == n } ?: return
        router.tab = AppTab.cases
        router.casesPath = listOf(kase.id)
    }
}

/** The small parchment chip on an open court file with a pending offer. */
@Composable
fun SettlementPendingChip(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(PleadColor.parchment, CircleShape)
            .border(1.dp, PleadColor.gold.copy(alpha = 0.7f), CircleShape)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(SFSymbol.icon("hands.and.sparkles.fill"), contentDescription = null, tint = PleadColor.walnut, modifier = Modifier.size(10.dp))
        Text(text, style = PleadType.labelCaps, color = PleadColor.walnut, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Settlement sheets for a case with a pending offer (response when it awaits me, else the room). */
fun AppRouter.openSettlement(route: SettlementDocket.RowRoute, caseId: UUID) {
    when (route) {
        SettlementDocket.RowRoute.settlementResponse -> sheet = AppSheet.settlementResponse(caseId)
        SettlementDocket.RowRoute.settlementRoom -> sheet = AppSheet.settlementRoom(caseId)
        SettlementDocket.RowRoute.record -> showRecord(caseId)
    }
}
