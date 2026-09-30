// Seams to the views wave 3d's screens embed from other features (waves 3a and 3e). Kept as one-line forwards so
// the 3d call sites read as in Swift; each names the view it shows.
//
//   DeliberationPanelSlot        → DeliberationPanel            (Features/Deliberation)
//   SettledJudgeLine             → CourtroomLogic.settledJudgeLine (Courtroom)
//   JudgementStatusCardSlot      → JudgementStatusCard          (Features/Judgement)
//   VerdictJudgementLineSlot     → VerdictJudgementLine         (Features/Judgement)
//   JudgementFulfilmentSlipSlot  → JudgementFulfilmentSlip      (Features/Judgement)
//   OutstandingJudgementCardSlot → OutstandingJudgementCard     (Features/Judgement)
//   SettlementFulfilmentCardSlot → SettlementFulfilmentCard     (Features/Settlement)
//   SettlementStatusRowSlot      → SettlementStatusRow          (Features/Settlement)
//   SettlementEntryButtonSlot    → SettlementEntryButton        (Features/Settlement)
//   SettlementRoomSheetSlot      → SettlementRoomView as a sheet (Features/Settlement)
package app.plead.android.features.casedetail

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.plead.android.app.AppRouter
import app.plead.android.courtroom.CourtroomLogic
import app.plead.android.features.deliberation.DeliberationPanel
import app.plead.android.features.judgement.JudgementFulfilmentSlip
import app.plead.android.features.judgement.JudgementStatusCard
import app.plead.android.features.judgement.OutstandingJudgementCard
import app.plead.android.features.judgement.VerdictJudgementLine
import app.plead.android.features.settlement.SettlementEntryButton
import app.plead.android.features.settlement.SettlementFulfilmentCard
import app.plead.android.features.settlement.SettlementRoomView
import app.plead.android.features.settlement.SettlementStatusRow
import app.plead.android.models.Case
import app.plead.android.models.Judgement
import app.plead.android.models.Settlement
import app.plead.android.models.SettlementOffer
import app.plead.android.models.Verdict
import app.plead.android.services.CaseStore
import java.time.Instant
import java.util.UUID

/** `CourtroomLogic.settledJudgeLine` (the settled case's fixed flavour line). */
const val SettledJudgeLine = CourtroomLogic.settledJudgeLine

/** `DeliberationPanel(kase:showsTitle:)`. */
@Composable
fun DeliberationPanelSlot(kase: Case, showsTitle: Boolean) = DeliberationPanel(kase, showsTitle = showsTitle)

/** `JudgementStatusCard(kase:judgement:)` (the record's fulfilment block). */
@Composable
fun JudgementStatusCardSlot(store: CaseStore, router: AppRouter, kase: Case, judgement: Judgement) =
    JudgementStatusCard(kase, judgement, store, router)

/** `VerdictJudgementLine(verdict:judgement:)` (a legacy ruling's sentence). */
@Composable
fun VerdictJudgementLineSlot(verdict: Verdict, judgement: Judgement?) = VerdictJudgementLine(verdict, judgement)

/** `JudgementFulfilmentSlip(kase:judgement:)` under a closed docket row. */
@Composable
fun JudgementFulfilmentSlipSlot(store: CaseStore, kase: Case, judgement: Judgement) = JudgementFulfilmentSlip(kase, judgement, store)

/** `OutstandingJudgementCard(kase:judgement:)` on Home. */
@Composable
fun OutstandingJudgementCardSlot(store: CaseStore, router: AppRouter, kase: Case, judgement: Judgement, modifier: Modifier = Modifier) =
    OutstandingJudgementCard(kase, judgement, store, router, modifier)

/** `SettlementFulfilmentCard(settlement:offer:now:)` on the record. */
@Composable
fun SettlementFulfilmentCardSlot(store: CaseStore, settlement: Settlement, offer: SettlementOffer?, now: Instant) =
    SettlementFulfilmentCard(settlement, offer, store, now = now)

/** `SettlementStatusRow(settlement:offer:now:)` in the docket's settled slip. */
@Composable
fun SettlementStatusRowSlot(settlement: Settlement, offer: SettlementOffer?, now: Instant) = SettlementStatusRow(settlement, offer, now = now)

/** `SettlementEntryButton(title:action:)` under the pleas (parchment on mahogany). [title] null = its default "Settle Outside Court". */
@Composable
fun SettlementEntryButtonSlot(title: String?, onClick: () -> Unit) =
    if (title != null) SettlementEntryButton(title = title, action = onClick) else SettlementEntryButton(action = onClick)

/** The summons' `.sheet { SettlementRoomView(caseId:) { onSent } }`. */
@Composable
fun SettlementRoomSheetSlot(store: CaseStore, router: AppRouter, caseId: UUID, onSent: () -> Unit, onDismiss: () -> Unit) {
    CaseSheetHost(onDismissRequest = onDismiss) {
        SettlementRoomView(caseId, store, router, onDismiss = onDismiss, onSent = onSent)
    }
}
