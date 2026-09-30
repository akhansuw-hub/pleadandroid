// Port of ArgueWin/Features/CaseDetail/SettlementRecordSection.swift.
//
// The record's settlement (CONTRACTS-v2 amendment n):
//   settled   "Settlement" section: the negotiation history (each offer: round, who, body, source chip,
//             time; the accepted one highlighted), the fulfilment card with "Mark as fulfilled" (either
//             partner) and the judge's flavour line. No verdict, no judgement.
//   pending   a "Settlement offer pending" card with the current offer and a button to the sheet.
package app.plead.android.features.casedetail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.plead.android.app.AppRouter
import app.plead.android.designsystem.AWButtonKind
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.PrimaryButton
import app.plead.android.features.cases.SettlementDocket
import app.plead.android.features.cases.openSettlement
import app.plead.android.models.Case
import app.plead.android.models.JudgePersona
import app.plead.android.models.Settlement
import app.plead.android.models.SettlementOffer
import app.plead.android.models.SettlementSource
import app.plead.android.models.Speaker
import app.plead.android.services.CaseStore
import java.time.Instant
import java.util.UUID

/** Settled out of court: history, fulfilment, the judge's line. */
@Composable
fun SettlementRecordSection(kase: Case, settlement: Settlement, store: CaseStore, modifier: Modifier = Modifier) {
    val offers = store.settlementOffers(kase.id)
    val offer = store.latestOffer(kase.id)
    fun name(id: UUID): String = store.name(id, fallback = "Your partner")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
        Text("Settlement", style = PleadType.displayM, color = PleadColor.cocoa, modifier = Modifier.semantics { heading() })

        SettlementFulfilmentCardSlot(store, settlement, offer, Instant.now())

        if (offers.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                RecordLabel("Negotiation")
                offers.forEach { o ->
                    SettlementOfferHistoryRow(
                        o,
                        accepted = SettlementDocket.isAccepted(o, settlement),
                        heading = SettlementDocket.offerHeading(o, store.me?.id, name(o.proposedBy)),
                        source = SettlementDocket.sourceChip(o, store.me?.id, name(o.proposedBy)),
                    )
                }
            }
        }

        Column(
            Modifier.padding(top = PleadSpacing.xs).semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs),
        ) {
            Text("“${SettlementRecordSection.flavourLine(kase, settlement, store)}”", style = CaseType.judgeQuote, color = PleadColor.mahogany)
            Text(
                "— ${(store.couple?.judgePersona ?: JudgePersona.wigsworth).displayName}",
                style = PleadType.metadata,
                color = PleadColor.subtleText,
            )
        }
    }
}

object SettlementRecordSection {
    /** The backend's judge flavour turn, else the fixed line. */
    fun flavourLine(kase: Case, settlement: Settlement, store: CaseStore): String {
        val since = (settlement.acceptedAt ?: kase.closedAt ?: Instant.MIN.plusSeconds(60)).minusSeconds(60)
        val turn = store.turns(kase.id).lastOrNull { it.speaker == Speaker.judge && !it.isSafetyNotice && !it.createdAt.isBefore(since) }
        return turn?.body ?: SettledJudgeLine
    }
}

/** One offer in the negotiation history. */
@Composable
fun SettlementOfferHistoryRow(offer: SettlementOffer, accepted: Boolean, heading: String, source: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(PleadRadius.tile)
    Column(
        modifier
            .fillMaxWidth()
            .background(if (accepted) PleadColor.parchment else PleadColor.paperWhite, shape)
            .border(if (accepted) 1.5.dp else 1.dp, if (accepted) PleadColor.gold.copy(alpha = 0.8f) else PleadColor.separator, shape)
            .padding(PleadSpacing.m + 2.dp)
            .clearAndSetSemantics { contentDescription = "$heading. ${offer.body}. $source${if (accepted) ". Agreed" else ""}" },
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs + 2.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s), verticalAlignment = Alignment.CenterVertically) {
            Text(heading, style = CaseType.speakerName, color = PleadColor.walnut)
            CaseChip(source, foreground = PleadColor.walnut, systemImage = if (offer.source == SettlementSource.ai) "sparkles" else "pencil")
            Spacer(Modifier.weight(1f))
        }
        SelectionContainer {
            Text(offer.body, style = if (accepted) CaseType.bodyEmphasis else PleadType.body, color = PleadColor.cocoa)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(CaseDates.abbreviatedDateTime(offer.createdAt), style = PleadType.metadata, color = PleadColor.subtleText)
            Spacer(Modifier.weight(1f))
            if (accepted) Text("AGREED ✓", style = PleadType.labelCapsTracked, color = PleadColor.gold)
        }
    }
}

/** An open case with a pending offer: the current terms and the way to the sheet. */
@Composable
fun SettlementPendingCard(
    kase: Case,
    settlement: Settlement?,
    offer: SettlementOffer?,
    store: CaseStore,
    router: AppRouter,
    modifier: Modifier = Modifier,
) {
    val route = SettlementDocket.route(kase, settlement, pendingForMe = store.pendingSettlementForMe(kase.id))
    val copy = SettlementDocket.activeCardCopy(pendingForMe = route == SettlementDocket.RowRoute.settlementResponse)
    val shape = RoundedCornerShape(PleadRadius.card)
    Column(
        modifier
            .fillMaxWidth()
            .background(PleadColor.parchment, shape)
            .border(1.dp, PleadColor.gold.copy(alpha = 0.6f), shape)
            .padding(PleadSpacing.l),
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.s),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s), verticalAlignment = Alignment.CenterVertically) {
            RecordLabel("Settlement offer pending", color = PleadColor.walnut, modifier = Modifier.weight(1f))
            SettlementDocket.pendingRound(kase, settlement)?.let { Text(it, style = PleadType.metadataMedium, color = PleadColor.walnut) }
        }
        if (offer != null) {
            Text(offer.body, style = CaseType.bodyEmphasis, color = PleadColor.cocoa)
            val who = if (offer.proposedBy == store.me?.id) "Your offer" else "From ${store.name(offer.proposedBy, fallback = "your partner")}"
            Text("$who · ${CaseDates.abbreviatedDateTime(offer.createdAt)}", style = PleadType.metadata, color = PleadColor.subtleText)
        }
        Text("The court will wait. Timers are paused.", style = PleadType.metadata, color = PleadColor.subtleText)
        PrimaryButton(
            copy.button,
            modifier = Modifier.padding(top = PleadSpacing.xs),
            systemImage = if (route == SettlementDocket.RowRoute.settlementResponse) "arrow.right" else null,
            kind = if (route == SettlementDocket.RowRoute.settlementResponse) AWButtonKind.primary else AWButtonKind.secondary,
        ) { router.openSettlement(route, kase.id) }
    }
}
