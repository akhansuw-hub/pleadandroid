// Seams to the views wave 3d's screens embed from features built in parallel (waves 3a and 3e). Each function below
// has the Swift view's parameters and a clearly labelled placeholder body; after the merge the integrator replaces
// each body with the one-line call in its KDoc (nothing else in wave 3d changes).
//
//   DeliberationPanelSlot        → DeliberationPanel            (Features/Deliberation, wave 3a)
//   SettledJudgeLine             → CourtroomLogic.settledJudgeLine (Courtroom, wave 3a)
//   JudgementStatusCardSlot      → JudgementStatusCard          (Features/Judgement, wave 3e)
//   VerdictJudgementLineSlot     → VerdictJudgementLine         (Features/Judgement, wave 3e)
//   JudgementFulfilmentSlipSlot  → JudgementFulfilmentSlip      (Features/Judgement, wave 3e)
//   OutstandingJudgementCardSlot → OutstandingJudgementCard     (Features/Judgement, wave 3e)
//   SettlementFulfilmentCardSlot → SettlementFulfilmentCard     (Features/Settlement, wave 3e)
//   SettlementStatusRowSlot      → SettlementStatusRow          (Features/Settlement, wave 3e)
//   SettlementEntryButtonSlot    → SettlementEntryButton        (Features/Settlement, wave 3e)
//   SettlementRoomSheetSlot      → SettlementRoomView as a sheet (Features/Settlement, wave 3e)
package app.plead.android.features.casedetail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.plead.android.app.AppRouter
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.models.Case
import app.plead.android.models.Judgement
import app.plead.android.models.Settlement
import app.plead.android.models.SettlementOffer
import app.plead.android.models.Verdict
import app.plead.android.services.CaseStore
import java.time.Instant
import java.util.UUID

/** `CourtroomLogic.settledJudgeLine` (the settled case's fixed flavour line). → `CourtroomLogic.settledJudgeLine`. */
const val SettledJudgeLine = "The parties have spared the court the trouble. Miracles do happen."

/** `DeliberationPanel(kase:showsTitle:)`. → `DeliberationPanel(kase, showsTitle = showsTitle)`. */
@Composable
fun DeliberationPanelSlot(kase: Case, showsTitle: Boolean) {
    SlotPlaceholder("DeliberationPanel", "Features/Deliberation/DeliberationPanel.swift", "3a", dark = true)
}

/** `JudgementStatusCard(kase:judgement:)` (the record's fulfilment block). → `JudgementStatusCard(kase, judgement, …)`. */
@Composable
fun JudgementStatusCardSlot(store: CaseStore, router: AppRouter, kase: Case, judgement: Judgement) {
    SlotPlaceholder("JudgementStatusCard", "Features/Judgement/JudgementStatusCard.swift", "3e")
}

/** `VerdictJudgementLine(verdict:judgement:)` (a legacy ruling's sentence). → `VerdictJudgementLine(verdict, judgement)`. */
@Composable
fun VerdictJudgementLineSlot(verdict: Verdict, judgement: Judgement?) {
    SlotPlaceholder("VerdictJudgementLine", "Features/Judgement/JudgementStatusCard.swift", "3e")
}

/** `JudgementFulfilmentSlip(kase:judgement:)` under a closed docket row. → `JudgementFulfilmentSlip(kase, judgement, …)`. */
@Composable
fun JudgementFulfilmentSlipSlot(store: CaseStore, kase: Case, judgement: Judgement) {
    SlotPlaceholder("JudgementFulfilmentSlip", "Features/Judgement/JudgementFulfilment.swift", "3e")
}

/** `OutstandingJudgementCard(kase:judgement:)` on Home. → `OutstandingJudgementCard(kase, judgement, …)`. */
@Composable
fun OutstandingJudgementCardSlot(store: CaseStore, router: AppRouter, kase: Case, judgement: Judgement, modifier: Modifier = Modifier) {
    SlotPlaceholder("OutstandingJudgementCard", "Features/Judgement/JudgementFulfilment.swift", "3e", modifier = modifier)
}

/** `SettlementFulfilmentCard(settlement:offer:now:)` on the record. → `SettlementFulfilmentCard(settlement, offer, now, …)`. */
@Composable
fun SettlementFulfilmentCardSlot(store: CaseStore, settlement: Settlement, offer: SettlementOffer?, now: Instant) {
    SlotPlaceholder("SettlementFulfilmentCard", "Features/Settlement/SettlementFulfilment.swift", "3e")
}

/** `SettlementStatusRow(settlement:offer:now:)` in the docket's settled slip. → `SettlementStatusRow(settlement, offer, now)`. */
@Composable
fun SettlementStatusRowSlot(settlement: Settlement, offer: SettlementOffer?, now: Instant) {
    Text("SettlementStatusRow · wave 3e", style = PleadType.metadata, color = PleadColor.subtleText)
}

/**
 * `SettlementEntryButton(title:action:)` under the pleas (parchment on mahogany). [title] null = its default
 * "Settle Outside Court". → `SettlementEntryButton(title = title ?: default, onClick = onClick)`.
 */
@Composable
fun SettlementEntryButtonSlot(title: String?, onClick: () -> Unit) {
    Text(
        title ?: "Settle Outside Court",
        style = PleadType.uiButtonSecondary,
        color = PleadColor.parchment,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, PleadColor.parchment.copy(alpha = 0.28f), RoundedCornerShape(PleadRadius.button))
            .clickable(onClick = onClick)
            .padding(PleadSpacing.m),
    )
}

/**
 * The summons' `.sheet { SettlementRoomView(caseId:) { onSent } }`. → `CaseSheetHost(onDismiss) { SettlementRoomView(caseId,
 * onSent = onSent, onDismiss = onDismiss, …) }`.
 */
@Composable
fun SettlementRoomSheetSlot(store: CaseStore, router: AppRouter, caseId: UUID, onSent: () -> Unit, onDismiss: () -> Unit) {
    CaseSheetHost(onDismissRequest = onDismiss) {
        SlotPlaceholder("SettlementRoomView", "Features/Settlement/SettlementRoomView.swift", "3e")
    }
}

/** The placeholder every slot draws until the merge: names the Swift view and the wave that ports it. */
@Composable
private fun SlotPlaceholder(name: String, iosFile: String, wave: String, modifier: Modifier = Modifier, dark: Boolean = false) {
    val shape = RoundedCornerShape(PleadRadius.card)
    val fg: Color = if (dark) PleadColor.cream else PleadColor.cocoa
    Column(
        modifier
            .fillMaxWidth()
            .background(if (dark) PleadColor.mahogany else PleadColor.parchment, shape)
            .border(1.dp, PleadColor.walnut.copy(alpha = 0.22f), shape)
            .padding(PleadSpacing.l),
    ) {
        Text("$name — wave $wave", style = PleadType.titleM, color = fg)
        Text(iosFile, style = PleadType.metadata, color = fg.copy(alpha = 0.7f))
    }
}
