// Port of ArgueWin/Features/Judgement/JudgementStatusCard.swift.
package app.plead.android.features.judgement

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.plead.android.app.AppRouter
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.SectionLabel
import app.plead.android.designsystem.awCard
import app.plead.android.features.settlement.IconLabel
import app.plead.android.models.Case
import app.plead.android.models.Judgement
import app.plead.android.models.JudgementStatus
import app.plead.android.models.Verdict
import app.plead.android.services.CaseStore

/**
 * Screen D on the case record, restyled per amendment l: the fulfilment block (VERDICT FINAL /
 * Judgement: … / DUE · 3 DAYS) heads the card, then a state line and the actions this party may take
 * ([JudgementCardAction]: Mark as served / Accept / Decline by role and state).
 */
@Composable
fun JudgementStatusCard(kase: Case, judgement: Judgement, store: CaseStore, router: AppRouter, modifier: Modifier = Modifier) {
    Box(modifier) {
        Column(Modifier.awCard(padding = PleadSpacing.xl), verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
            JudgementFulfilmentBlock(kase, judgement, store, style = JudgementFulfilmentBlock.Style.full)
            JudgementStatusCardCopy.servedLine(judgement)?.let { meta ->
                IconLabel(meta, Icons.Outlined.Verified, color = PleadColor.walnut)   // checkmark.seal
            }
            Text(JudgementStatusCardCopy.stateLine(kase, judgement, store), style = PleadType.metadata, color = PleadColor.subtleText)
            JudgementActionButtons(kase, judgement, store, router, modifier = Modifier.padding(top = PleadSpacing.xs))
        }
        Box(Modifier.matchParentSize().border(1.5.dp, PleadColor.gold.copy(alpha = 0.55f), RoundedCornerShape(PleadRadius.card)))
    }
}

/** Copy (brief §6: dry, theatrical, never shaming). */
object JudgementStatusCardCopy {
    fun servedLine(judgement: Judgement): String? {
        val at = judgement.servedAt
        if (judgement.status != JudgementStatus.served || at == null) return null
        return "Served ${JudgementDates.weekdayDayMonthAbbreviated(at)}"
    }

    fun stateLine(kase: Case, judgement: Judgement, store: CaseStore): String {
        val me = store.me?.id
        val isChooser = judgement.chooserId != null && judgement.chooserId == me
        if (judgement.isCourtChosen) {
            // Tie (amendment l): the court chose for both; no accept step.
            return when (judgement.status) {
                JudgementStatus.pendingSelection -> "The court could not separate you. It is choosing a resolution for you both."
                JudgementStatus.delivered, JudgementStatus.accepted ->
                    "The court could not separate you and has chosen a resolution for you both. Either of you can mark it served."
                JudgementStatus.served -> "Resolution served. Case officially closed."
                JudgementStatus.declined -> "The resolution was declined. The court notes it, without comment."
            }
        }
        val other = store.judgementRecipientId(kase)?.let { if (it == me) "You" else store.name(it, fallback = "Your partner") } ?: "Your partner"
        return when (judgement.status) {
            JudgementStatus.pendingSelection ->
                if (isChooser) "You won the case. Now choose the court's judgement." else "The prevailing party is choosing the court's judgement."
            JudgementStatus.delivered ->
                if (isChooser) "The court has delivered your judgement. Waiting for $other to accept."
                else "The court has spoken. Accepting is ceremonial, not a contract."
            JudgementStatus.accepted ->
                if (isChooser) "$other accepts the court's judgement." else "You accepted the court's judgement. The court is grateful."
            JudgementStatus.served -> "Judgement served. Case officially closed."
            JudgementStatus.declined ->
                if (isChooser) "$other declined the judgement. The court notes it, without comment."
                else "You declined the judgement. The court notes it, without comment."
        }
    }
}

/**
 * The verdict card's judgement line (replaces the old "Sentence"): the chosen judgement, "Awaiting
 * the winner's choice", or a pre-amendment ruling's own sentence.
 */
@Composable
fun VerdictJudgementLine(verdict: Verdict, judgement: Judgement?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs)) {
        SectionLabel(if (verdict.isTie) "Resolution" else "Judgement")
        val selected = judgement?.selected
        if (selected != null) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs)) {
                    Text(selected.title, style = JudgementType.cardTitle, color = PleadColor.cocoa)
                    Text(selected.detail, style = PleadType.body, color = PleadColor.subtleText)
                }
            }
        } else if (judgement == null && verdict.hasLegacySentence) {
            SelectionContainer { Text(verdict.sentence, style = JudgementType.cardTitle, color = PleadColor.cocoa) }
        } else {
            Text(
                if (verdict.isTie) "Awaiting the court's resolution" else "Awaiting the winner's choice",
                style = PleadType.titleM,
                color = PleadColor.subtleText,
            )
        }
    }
}
